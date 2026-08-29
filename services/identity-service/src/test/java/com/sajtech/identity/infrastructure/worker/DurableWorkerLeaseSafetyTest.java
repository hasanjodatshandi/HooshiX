package com.sajtech.identity.infrastructure.worker;

import static com.sajtech.identity.application.transaction.model.TransactionProfile.WORK_CLAIM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sajtech.identity.application.erasure.model.ErasureCommandOutboxItem;
import com.sajtech.identity.application.erasure.port.out.ErasureCommandOutbox;
import com.sajtech.identity.application.notification.model.NotificationContentType;
import com.sajtech.identity.application.notification.model.NotificationOutboxRecord;
import com.sajtech.identity.application.notification.port.out.NotificationOutboxStore;
import com.sajtech.identity.application.notification.port.out.NotificationSubmissionPort;
import com.sajtech.identity.application.registration.model.DecryptedHandoff;
import com.sajtech.identity.application.registration.port.out.NotificationEscrowPort;
import com.sajtech.identity.application.tenant.model.AuthorizationOutboxItem;
import com.sajtech.identity.application.tenant.port.out.AuthorizationTenantPort;
import com.sajtech.identity.application.tenant.port.out.TenantStore;
import com.sajtech.identity.application.transaction.model.TransactionProfile;
import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import com.sajtech.identity.domain.registration.valueobject.RegistrationChannel;
import com.sajtech.identity.domain.registration.valueobject.RegistrationLocale;
import com.sajtech.identity.infrastructure.observability.AuthorizationOutboxMetrics;
import com.sajtech.identity.infrastructure.persistence.AuthorizationOutboxTelemetryQuery;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class DurableWorkerLeaseSafetyTest {
  private static final Instant NOW = Instant.parse("2026-08-29T09:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void notificationOutboxClaimsEachItemOnlyAfterPriorRemoteWorkCompletes() {
    NotificationOutboxStore store = mock(NotificationOutboxStore.class);
    NotificationEscrowPort escrow = mock(NotificationEscrowPort.class);
    NotificationSubmissionPort notification = mock(NotificationSubmissionPort.class);
    List<String> events = new ArrayList<>();
    AtomicInteger claims = new AtomicInteger();
    List<NotificationOutboxRecord> records = List.of(notificationRecord(), notificationRecord());
    when(store.claimDue(eq(NOW), eq(1), eq(Duration.ofSeconds(30))))
        .thenAnswer(
            ignored -> {
              int index = claims.getAndIncrement();
              events.add("claim-" + index);
              return index < records.size() ? List.of(records.get(index)) : List.of();
            });
    when(escrow.decrypt(any(), anyString(), any(), any()))
        .thenReturn(
            new DecryptedHandoff(
                RegistrationChannel.EMAIL, "person@example.com", RegistrationLocale.EN, "123456"));
    when(notification.submit(any(), any()))
        .thenAnswer(
            invocation -> {
              events.add("remote-" + records.indexOf(invocation.getArgument(0)));
              return UUID.randomUUID();
            });
    doAnswer(
            invocation -> {
              events.add("complete-" + indexOf(records, invocation.getArgument(0)));
              return null;
            })
        .when(store)
        .markSubmitted(any(), any(), eq(NOW));

    boolean busy =
        new NotificationOutboxDispatcher(store, escrow, notification, CLOCK).dispatchDue();

    assertThat(busy).isTrue();
    assertThat(events)
        .containsExactly(
            "claim-0", "remote-0", "complete-0", "claim-1", "remote-1", "complete-1", "claim-2");
  }

  @Test
  void authorizationOutboxUsesWorkClaimProfileAndFreshLeasePerRemoteCall() {
    TenantStore store = mock(TenantStore.class);
    AuthorizationTenantPort authorization = mock(AuthorizationTenantPort.class);
    AuthorizationOutboxTelemetryQuery telemetry = mock(AuthorizationOutboxTelemetryQuery.class);
    List<String> events = new ArrayList<>();
    AtomicInteger claims = new AtomicInteger();
    AtomicInteger remoteCalls = new AtomicInteger();
    List<AuthorizationOutboxItem> items = List.of(authorizationItem(), authorizationItem());
    when(store.claimAuthorizationOutbox(eq(NOW), eq(1), eq(NOW.plusSeconds(30))))
        .thenAnswer(
            ignored -> {
              int index = claims.getAndIncrement();
              events.add("claim-" + index);
              return index < items.size() ? List.of(items.get(index)) : List.of();
            });
    doAnswer(
            invocation -> {
              events.add("remote-" + remoteCalls.getAndIncrement());
              return null;
            })
        .when(authorization)
        .provisionOwner(any(), any(), any(), any());
    doAnswer(
            invocation -> {
              events.add("complete-" + indexOf(items, invocation.getArgument(0)));
              return null;
            })
        .when(store)
        .completeAuthorizationOutbox(any(), eq(NOW));

    boolean busy =
        new AuthorizationOutboxDispatcher(
                store,
                telemetry,
                authorization,
                immediateTransactions(),
                CLOCK,
                new AuthorizationOutboxMetrics(new SimpleMeterRegistry()))
            .dispatchDue();

    assertThat(busy).isTrue();
    assertThat(events)
        .containsExactly(
            "claim-0", "remote-0", "complete-0", "claim-1", "remote-1", "complete-1", "claim-2");
  }

  @Test
  void erasureOutboxClaimsOneItemImmediatelyBeforeEachBoundedKafkaSend() throws Exception {
    ErasureCommandOutbox outbox = mock(ErasureCommandOutbox.class);
    @SuppressWarnings("unchecked")
    KafkaTemplate<String, byte[]> kafka = mock(KafkaTemplate.class);
    List<String> events = new ArrayList<>();
    AtomicInteger claims = new AtomicInteger();
    List<ErasureCommandOutboxItem> items = List.of(erasureItem(), erasureItem());
    when(outbox.claimDue(eq(1), eq(NOW), eq(Duration.ofSeconds(30))))
        .thenAnswer(
            ignored -> {
              int index = claims.getAndIncrement();
              events.add("claim-" + index);
              return index < items.size() ? List.of(items.get(index)) : List.of();
            });
    when(kafka.send(eq("identity.erasure.commands.v1"), anyString(), any(byte[].class)))
        .thenAnswer(
            ignored -> {
              events.add("remote-" + (events.contains("remote-0") ? 1 : 0));
              return CompletableFuture.completedFuture(mock(SendResult.class));
            });
    doAnswer(
            invocation -> {
              events.add("complete-" + indexOf(items, invocation.getArgument(0)));
              return null;
            })
        .when(outbox)
        .markPublished(any(), eq(NOW));

    new ErasureCommandOutboxDispatcher(
            outbox,
            kafka,
            immediateTransactions(),
            CLOCK,
            "identity.erasure.commands.v1",
            new SimpleMeterRegistry())
        .dispatch();

    assertThat(events)
        .containsExactly(
            "claim-0", "remote-0", "complete-0", "claim-1", "remote-1", "complete-1", "claim-2");
  }

  private static NotificationOutboxRecord notificationRecord() {
    return new NotificationOutboxRecord(
        UUID.randomUUID(),
        UUID.randomUUID(),
        NotificationContentType.REGISTRATION_VERIFICATION,
        RegistrationChannel.EMAIL,
        RegistrationLocale.EN,
        "key-v1",
        new byte[12],
        new byte[32],
        NOW.plusSeconds(300),
        0);
  }

  private static AuthorizationOutboxItem authorizationItem() {
    return new AuthorizationOutboxItem(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "PROVISION_OWNER",
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        null,
        0);
  }

  private static ErasureCommandOutboxItem erasureItem() {
    return new ErasureCommandOutboxItem(
        UUID.randomUUID(), UUID.randomUUID(), "v1", 0, NOW.minusSeconds(1));
  }

  private static int indexOf(List<? extends Object> records, UUID id) {
    for (int index = 0; index < records.size(); index++) {
      Object record = records.get(index);
      UUID recordId =
          switch (record) {
            case NotificationOutboxRecord notification -> notification.outboxId();
            case AuthorizationOutboxItem authorization -> authorization.outboxId();
            case ErasureCommandOutboxItem erasure -> erasure.eventId();
            default -> throw new IllegalArgumentException("Unknown outbox record");
          };
      if (recordId.equals(id)) return index;
    }
    return -1;
  }

  private static TransactionRunner immediateTransactions() {
    return new TransactionRunner() {
      @Override
      public <T> T required(Supplier<T> work) {
        return work.get();
      }

      @Override
      public <T> T required(TransactionProfile profile, Supplier<T> work) {
        assertThat(profile).isEqualTo(WORK_CLAIM);
        return work.get();
      }
    };
  }
}

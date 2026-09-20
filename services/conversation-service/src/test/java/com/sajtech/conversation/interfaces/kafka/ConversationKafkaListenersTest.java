package com.sajtech.conversation.interfaces.kafka;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.google.protobuf.Timestamp;
import com.sajtech.conversation.infrastructure.erasure.JdbcConversationErasureRepository;
import com.sajtech.conversation.infrastructure.lifecycle.JdbcTenantLifecycleRepository;
import com.sajtech.identity.contract.v1.ErasureCommandEvent;
import com.sajtech.identity.contract.v1.TenantLifecycleEvent;
import com.sajtech.identity.contract.v1.TenantLifecycleEventState;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

class ConversationKafkaListenersTest {
  private static final Instant NOW = Instant.parse("2026-09-20T13:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void tenantListenerValidatesPersistsThenAcknowledges() {
    var repository = mock(JdbcTenantLifecycleRepository.class);
    var acknowledgment = mock(Acknowledgment.class);
    var event =
        TenantLifecycleEvent.newBuilder()
            .setEventId(UUID.randomUUID().toString())
            .setTenantId(UUID.randomUUID().toString())
            .setLifecycleVersion(1)
            .setState(TenantLifecycleEventState.TENANT_LIFECYCLE_EVENT_STATE_ACTIVE)
            .setOccurredAt(timestamp())
            .build();

    new TenantLifecycleListener(repository, CLOCK).receive(event.toByteArray(), acknowledgment);

    verify(repository).receive(event, NOW);
    verify(acknowledgment).acknowledge();
  }

  @Test
  void tenantListenerRejectsMalformedAndContractInvalidPayloadWithoutAcknowledging() {
    var repository = mock(JdbcTenantLifecycleRepository.class);
    var acknowledgment = mock(Acknowledgment.class);
    var listener = new TenantLifecycleListener(repository, CLOCK);

    assertThatThrownBy(() -> listener.receive(new byte[] {(byte) 0xff}, acknowledgment))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                listener.receive(
                    TenantLifecycleEvent.getDefaultInstance().toByteArray(), acknowledgment))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(repository, acknowledgment);
  }

  @Test
  void erasureListenerValidatesPersistsThenAcknowledges() {
    var repository = mock(JdbcConversationErasureRepository.class);
    var acknowledgment = mock(Acknowledgment.class);
    var event =
        ErasureCommandEvent.newBuilder()
            .setEventId(UUID.randomUUID().toString())
            .setErasureRequestId(UUID.randomUUID().toString())
            .setParticipantPolicyVersion("2")
            .setOccurredAt(timestamp())
            .build();

    new ConversationErasureListener(repository, CLOCK).receive(event.toByteArray(), acknowledgment);

    verify(repository).receive(event, NOW);
    verify(acknowledgment).acknowledge();
  }

  @Test
  void erasureListenerRejectsMalformedAndContractInvalidPayloadWithoutAcknowledging() {
    var repository = mock(JdbcConversationErasureRepository.class);
    var acknowledgment = mock(Acknowledgment.class);
    var listener = new ConversationErasureListener(repository, CLOCK);

    assertThatThrownBy(() -> listener.receive(new byte[] {(byte) 0xff}, acknowledgment))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                listener.receive(
                    ErasureCommandEvent.getDefaultInstance().toByteArray(), acknowledgment))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(repository, acknowledgment);
  }

  private static Timestamp timestamp() {
    return Timestamp.newBuilder().setSeconds(NOW.getEpochSecond()).setNanos(NOW.getNano()).build();
  }
}

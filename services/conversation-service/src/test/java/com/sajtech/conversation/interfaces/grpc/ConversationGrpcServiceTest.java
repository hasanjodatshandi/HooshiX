package com.sajtech.conversation.interfaces.grpc;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sajtech.conversation.application.ConversationError;
import com.sajtech.conversation.application.ConversationException;
import com.sajtech.conversation.application.service.ConversationService;
import com.sajtech.conversation.contract.v1.*;
import com.sajtech.conversation.domain.Conversation;
import com.sajtech.conversation.domain.ConversationLifecycle;
import com.sajtech.conversation.interfaces.observability.grpc.ConversationTracingInterceptor;
import com.sajtech.hooshix.contract.validation.ContractValidationServerInterceptor;
import io.grpc.*;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.*;

class ConversationGrpcServiceTest {
  private final ConversationService application = mock(ConversationService.class);
  private Server server;
  private ManagedChannel channel;
  private SimpleMeterRegistry meters;

  @BeforeEach
  void start() throws Exception {
    String name = InProcessServerBuilder.generateName();
    meters = new SimpleMeterRegistry();
    server =
        InProcessServerBuilder.forName(name)
            .directExecutor()
            .addService(
                ServerInterceptors.interceptForward(
                    new ConversationGrpcService(application),
                    new ConversationTracingInterceptor(OpenTelemetry.noop(), meters),
                    new BearerTokenServerInterceptor(),
                    new ContractValidationServerInterceptor(ignored -> {})))
            .build()
            .start();
    channel = InProcessChannelBuilder.forName(name).directExecutor().build();
  }

  @AfterEach
  void stop() {
    channel.shutdownNow();
    server.shutdownNow();
  }

  @Test
  void createUsesBearerTokenAndMapsOwnedConversation() {
    UUID requestId = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    Instant now = Instant.parse("2026-09-13T08:00:00Z");
    when(application.create("token", requestId, "private"))
        .thenReturn(
            new Conversation(
                conversationId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "private",
                ConversationLifecycle.ACTIVE,
                1,
                now,
                now));

    var response =
        authorized()
            .createConversation(
                CreateConversationRequest.newBuilder()
                    .setRequestId(requestId.toString())
                    .setTitle("private")
                    .build());

    assertThat(response.getConversation().getConversationId()).isEqualTo(conversationId.toString());
    assertThat(response.getConversation().getLifecycle())
        .isEqualTo(
            com.sajtech.conversation.contract.v1.ConversationLifecycle
                .CONVERSATION_LIFECYCLE_ACTIVE);
    verify(application).create("token", requestId, "private");
    assertThat(
            meters
                .get("conversation.grpc.duration")
                .tag("operation", "CreateConversation")
                .tag("outcome", "OK")
                .timer()
                .count())
        .isEqualTo(1);
  }

  @Test
  void rejectsMissingBearerBeforeApplication() {
    var stub = ConversationServiceGrpc.newBlockingStub(channel);
    var request =
        GetConversationRequest.newBuilder().setConversationId(UUID.randomUUID().toString()).build();

    assertThatThrownBy(() -> stub.getConversation(request))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            exception ->
                assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
    verifyNoInteractions(application);
  }

  @Test
  void authenticationPrecedesContractValidation() {
    var stub = ConversationServiceGrpc.newBlockingStub(channel);
    assertThatThrownBy(
            () -> stub.createConversation(CreateConversationRequest.getDefaultInstance()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            exception ->
                assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
    verifyNoInteractions(application);
  }

  @Test
  void rejectsMalformedDuplicateEmptyAndOversizedBearerValues() {
    assertUnauthenticated(metadata("Basic token"));
    assertUnauthenticated(metadata("Bearer "));
    assertUnauthenticated(metadata("Bearer " + "x".repeat(4097)));
    Metadata duplicate = metadata("Bearer token");
    duplicate.put(
        Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer second");
    assertUnauthenticated(duplicate);
    verifyNoInteractions(application);
  }

  @Test
  void validationAndStableApplicationErrorsDoNotLeakDetails() {
    assertThatThrownBy(
            () ->
                authorized()
                    .createConversation(
                        CreateConversationRequest.newBuilder()
                            .setRequestId(UUID.randomUUID().toString())
                            .build()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            exception ->
                assertThat(exception.getStatus().getCode())
                    .isEqualTo(Status.Code.INVALID_ARGUMENT));

    UUID id = UUID.randomUUID();
    when(application.get("token", id))
        .thenThrow(
            new ConversationException(
                ConversationError.CONVERSATION_NOT_FOUND, "sensitive internal detail"));
    assertThatThrownBy(
            () ->
                authorized()
                    .getConversation(
                        GetConversationRequest.newBuilder()
                            .setConversationId(id.toString())
                            .build()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            exception -> {
              assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
              assertThat(exception.getStatus().getDescription())
                  .isEqualTo("CONVERSATION_NOT_FOUND");
            });
  }

  private ConversationServiceGrpc.ConversationServiceBlockingStub authorized() {
    Metadata metadata = metadata("Bearer token");
    return ConversationServiceGrpc.newBlockingStub(channel)
        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
  }

  private void assertUnauthenticated(Metadata metadata) {
    var stub =
        ConversationServiceGrpc.newBlockingStub(channel)
            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
    var request =
        GetConversationRequest.newBuilder().setConversationId(UUID.randomUUID().toString()).build();
    assertThatThrownBy(() -> stub.getConversation(request))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            exception ->
                assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
  }

  private static Metadata metadata(String value) {
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), value);
    return metadata;
  }
}

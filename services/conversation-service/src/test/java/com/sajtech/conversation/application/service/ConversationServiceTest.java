package com.sajtech.conversation.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sajtech.conversation.application.ConversationError;
import com.sajtech.conversation.application.ConversationException;
import com.sajtech.conversation.application.model.ConversationActor;
import com.sajtech.conversation.application.model.ConversationPage;
import com.sajtech.conversation.application.model.MessagePage;
import com.sajtech.conversation.application.model.ModelExecutionPolicy;
import com.sajtech.conversation.application.port.out.AccessTokenVerifier;
import com.sajtech.conversation.application.port.out.ConversationRepository;
import com.sajtech.conversation.application.port.out.ModelPolicyProvider;
import com.sajtech.conversation.application.port.out.ModelRunRepository;
import com.sajtech.conversation.application.port.out.PermissionAuthorizer;
import com.sajtech.conversation.domain.Conversation;
import com.sajtech.conversation.domain.ConversationLifecycle;
import com.sajtech.conversation.domain.ConversationMessage;
import com.sajtech.conversation.domain.MessageRole;
import com.sajtech.conversation.domain.ModelRun;
import com.sajtech.conversation.domain.ModelRunFailure;
import com.sajtech.conversation.domain.ModelRunState;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ConversationServiceTest {
  private static final Instant NOW = Instant.parse("2026-09-13T08:00:00Z");
  private static final ConversationActor ACTOR =
      new ConversationActor(
          UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "s".repeat(43));
  private final AccessTokenVerifier tokens = mock(AccessTokenVerifier.class);
  private final PermissionAuthorizer permissions = mock(PermissionAuthorizer.class);
  private final ConversationRepository repository = mock(ConversationRepository.class);
  private final ModelRunRepository modelRuns = mock(ModelRunRepository.class);
  private final ModelPolicyProvider modelPolicy = mock(ModelPolicyProvider.class);
  private final ConversationService service =
      new ConversationService(
          new ConversationAuthority(tokens, permissions),
          repository,
          modelRuns,
          modelPolicy,
          Clock.fixed(NOW, ZoneOffset.UTC));

  @BeforeEach
  void verifyActor() {
    when(tokens.verify("token")).thenReturn(ACTOR);
  }

  @Test
  void createCanonicalizesBeforeAuthorizationAndUsesCreatePermission() {
    UUID requestId = UUID.randomUUID();
    ArgumentCaptor<UUID> id = ArgumentCaptor.forClass(UUID.class);
    when(repository.create(eq(ACTOR), eq(requestId), id.capture(), eq("Café"), eq(NOW)))
        .thenAnswer(
            invocation ->
                conversation(invocation.getArgument(2), "Café", ConversationLifecycle.ACTIVE, 1));

    Conversation created = service.create("token", requestId, "  Cafe\u0301  ");

    assertThat(created.title()).isEqualTo("Café");
    assertThat(id.getValue().version()).isEqualTo(4);
    verify(permissions).check(ACTOR, "conversation.create");
    verifyNoMoreInteractions(permissions);
  }

  @Test
  void invalidInputDoesNotCallAnyAuthorityOrPersistence() {
    assertThatThrownBy(() -> service.create("token", UUID.randomUUID(), "\u0000"))
        .isInstanceOfSatisfying(
            ConversationException.class,
            exception ->
                assertThat(exception.error()).isEqualTo(ConversationError.INVALID_REQUEST));

    verifyNoInteractions(tokens, permissions, repository, modelRuns, modelPolicy);
  }

  @Test
  void rejectsNonV4IdsAndMalformedUnicodeBeforeAuthority() {
    UUID versionOne = UUID.fromString("12345678-1234-1234-9234-123456789abc");
    assertThatThrownBy(() -> service.get("token", versionOne))
        .isInstanceOf(ConversationException.class);
    assertThatThrownBy(() -> service.create("token", UUID.randomUUID(), "broken\ud800"))
        .isInstanceOf(ConversationException.class);
    verifyNoInteractions(tokens, permissions, repository, modelRuns, modelPolicy);
  }

  @Test
  void readAndMutationOperationsUseTheirExactPermissionAndActor() {
    UUID id = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    Conversation value = conversation(id, "title", ConversationLifecycle.ACTIVE, 1);
    when(repository.getOwned(ACTOR, id)).thenReturn(value);
    when(repository.listOwned(ACTOR, 20, "")).thenReturn(new ConversationPage(List.of(value), ""));
    when(repository.archiveOwned(ACTOR, requestId, id, 1, NOW))
        .thenReturn(conversation(id, "title", ConversationLifecycle.ARCHIVED, 2));

    service.list("token", 0, "");
    service.get("token", id);
    service.archive("token", requestId, id, 1);
    service.delete("token", requestId, id, 2);

    verify(permissions, times(2)).check(ACTOR, "conversation.read");
    verify(permissions, times(2)).check(ACTOR, "conversation.delete");
    verify(repository).deleteOwned(ACTOR, requestId, id, 2, NOW);
  }

  @Test
  void createRunCanonicalizesAuthorizesAndUsesApprovedPolicy() {
    UUID requestId = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    var policy = new ModelExecutionPolicy("conversation-primary", "1.0.0", "2026-09-12", 70_000);
    when(modelPolicy.requireApprovedPolicy()).thenReturn(policy);
    when(modelRuns.findAcceptedReplay(ACTOR, requestId, conversationId, "  Café  "))
        .thenReturn(null);
    when(modelRuns.accept(
            eq(ACTOR),
            eq(requestId),
            eq(conversationId),
            any(UUID.class),
            any(UUID.class),
            eq("  Café  "),
            eq(policy),
            eq(NOW)))
        .thenAnswer(
            invocation ->
                run(invocation.getArgument(4), conversationId, ModelRunState.QUEUED, null));

    ModelRun accepted = service.createRun("token", requestId, conversationId, "  Cafe\u0301  ");

    assertThat(accepted.state()).isEqualTo(ModelRunState.QUEUED);
    verify(permissions).check(ACTOR, "conversation.generate");
    verify(modelPolicy).requireApprovedPolicy();
  }

  @Test
  void equalRunReplayDoesNotRequireCurrentlyEnabledPolicy() {
    UUID requestId = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    ModelRun replay = run(UUID.randomUUID(), conversationId, ModelRunState.QUEUED, null);
    when(modelRuns.findAcceptedReplay(ACTOR, requestId, conversationId, "same")).thenReturn(replay);

    assertThat(service.createRun("token", requestId, conversationId, "same")).isSameAs(replay);

    verifyNoInteractions(modelPolicy);
    verify(modelRuns, never()).accept(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void invalidMessageFailsBeforeAuthorityAndPolicy() {
    assertThatThrownBy(
            () -> service.createRun("token", UUID.randomUUID(), UUID.randomUUID(), "\u0000"))
        .isInstanceOfSatisfying(
            ConversationException.class,
            exception ->
                assertThat(exception.error()).isEqualTo(ConversationError.INVALID_REQUEST));
    verifyNoInteractions(tokens, permissions, repository, modelRuns, modelPolicy);
  }

  @Test
  void messageAndRunReadsAndCancellationUseBoundedInputsAndGenerateAuthority() {
    UUID requestId = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    ConversationMessage message =
        new ConversationMessage(
            UUID.randomUUID(), conversationId, MessageRole.USER, "hello", 1, NOW);
    MessagePage page = new MessagePage(List.of(message), "");
    ModelRun queued = run(runId, conversationId, ModelRunState.QUEUED, null);
    when(modelRuns.listMessagesOwned(ACTOR, conversationId, 20, "")).thenReturn(page);
    when(modelRuns.getOwned(ACTOR, conversationId, runId)).thenReturn(queued);
    when(modelRuns.cancelOwned(ACTOR, requestId, conversationId, runId, NOW)).thenReturn(queued);

    assertThat(service.listMessages("token", conversationId, 0, "")).isSameAs(page);
    assertThat(service.getRun("token", conversationId, runId)).isSameAs(queued);
    assertThat(service.cancelRun("token", requestId, conversationId, runId)).isSameAs(queued);

    verify(permissions).check(ACTOR, "conversation.read");
    verify(permissions, times(2)).check(ACTOR, "conversation.generate");
  }

  @Test
  void messageHistoryRejectsOversizedPageBeforeAuthority() {
    assertThatThrownBy(() -> service.listMessages("token", UUID.randomUUID(), 101, ""))
        .isInstanceOfSatisfying(
            ConversationException.class,
            exception ->
                assertThat(exception.error()).isEqualTo(ConversationError.INVALID_REQUEST));

    verifyNoInteractions(tokens, permissions, repository, modelRuns, modelPolicy);
  }

  private static Conversation conversation(
      UUID id, String title, ConversationLifecycle lifecycle, long version) {
    return new Conversation(
        id, ACTOR.tenantId(), ACTOR.membershipId(), title, lifecycle, version, NOW, NOW);
  }

  private static ModelRun run(
      UUID id, UUID conversationId, ModelRunState state, Instant completedAt) {
    return new ModelRun(
        id,
        conversationId,
        state,
        "conversation-primary",
        "1.0.0",
        "2026-09-12",
        70_000,
        0,
        ModelRunFailure.NONE,
        false,
        NOW,
        null,
        completedAt);
  }
}

package com.sajtech.conversation.application.service;

import com.sajtech.conversation.application.ConversationError;
import com.sajtech.conversation.application.ConversationException;
import com.sajtech.conversation.application.model.ConversationPage;
import com.sajtech.conversation.application.model.ConversationPermission;
import com.sajtech.conversation.application.model.MessagePage;
import com.sajtech.conversation.application.port.out.ConversationRepository;
import com.sajtech.conversation.application.port.out.ModelPolicyProvider;
import com.sajtech.conversation.application.port.out.ModelRunRepository;
import com.sajtech.conversation.domain.Conversation;
import com.sajtech.conversation.domain.ModelRun;
import java.text.Normalizer;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

public final class ConversationService {
  private static final int DEFAULT_PAGE_SIZE = 20;
  private final ConversationAuthority authority;
  private final ConversationRepository repository;
  private final ModelRunRepository modelRuns;
  private final ModelPolicyProvider modelPolicy;
  private final Clock clock;

  public ConversationService(
      ConversationAuthority authority,
      ConversationRepository repository,
      ModelRunRepository modelRuns,
      ModelPolicyProvider modelPolicy,
      Clock clock) {
    this.authority = Objects.requireNonNull(authority);
    this.repository = Objects.requireNonNull(repository);
    this.modelRuns = Objects.requireNonNull(modelRuns);
    this.modelPolicy = Objects.requireNonNull(modelPolicy);
    this.clock = Objects.requireNonNull(clock);
  }

  public Conversation create(String token, UUID requestId, String title) {
    requireUuidV4(requestId);
    String canonicalTitle = canonicalTitle(title);
    var actor = authority.authorize(token, ConversationPermission.CREATE);
    return repository.create(actor, requestId, UUID.randomUUID(), canonicalTitle, clock.instant());
  }

  public ConversationPage list(String token, int pageSize, String pageToken) {
    int boundedSize = pageSize == 0 ? DEFAULT_PAGE_SIZE : pageSize;
    if (boundedSize < 1 || boundedSize > 100 || pageToken == null || pageToken.length() > 256) {
      throw invalidRequest();
    }
    var actor = authority.authorize(token, ConversationPermission.READ);
    return repository.listOwned(actor, boundedSize, pageToken);
  }

  public Conversation get(String token, UUID conversationId) {
    requireUuidV4(conversationId);
    var actor = authority.authorize(token, ConversationPermission.READ);
    return repository.getOwned(actor, conversationId);
  }

  public Conversation archive(
      String token, UUID requestId, UUID conversationId, long expectedVersion) {
    requireUuidV4(requestId);
    requireMutation(conversationId, expectedVersion);
    var actor = authority.authorize(token, ConversationPermission.DELETE);
    return repository.archiveOwned(
        actor, requestId, conversationId, expectedVersion, clock.instant());
  }

  public void delete(String token, UUID requestId, UUID conversationId, long expectedVersion) {
    requireUuidV4(requestId);
    requireMutation(conversationId, expectedVersion);
    var actor = authority.authorize(token, ConversationPermission.DELETE);
    repository.deleteOwned(actor, requestId, conversationId, expectedVersion, clock.instant());
  }

  public MessagePage listMessages(
      String token, UUID conversationId, int pageSize, String pageToken) {
    requireUuidV4(conversationId);
    int boundedSize = pageSize == 0 ? DEFAULT_PAGE_SIZE : pageSize;
    if (boundedSize < 1 || boundedSize > 100 || pageToken == null || pageToken.length() > 256) {
      throw invalidRequest();
    }
    var actor = authority.authorize(token, ConversationPermission.READ);
    return modelRuns.listMessagesOwned(actor, conversationId, boundedSize, pageToken);
  }

  public ModelRun createRun(String token, UUID requestId, UUID conversationId, String userMessage) {
    requireUuidV4(requestId);
    requireUuidV4(conversationId);
    String canonicalMessage = canonicalMessage(userMessage);
    var actor = authority.authorize(token, ConversationPermission.GENERATE);
    ModelRun replay =
        modelRuns.findAcceptedReplay(actor, requestId, conversationId, canonicalMessage);
    if (replay != null) return replay;
    var policy = modelPolicy.requireApprovedPolicy();
    return modelRuns.accept(
        actor,
        requestId,
        conversationId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        canonicalMessage,
        policy,
        clock.instant());
  }

  public ModelRun getRun(String token, UUID conversationId, UUID runId) {
    requireUuidV4(conversationId);
    requireUuidV4(runId);
    var actor = authority.authorize(token, ConversationPermission.GENERATE);
    return modelRuns.getOwned(actor, conversationId, runId);
  }

  public ModelRun cancelRun(String token, UUID requestId, UUID conversationId, UUID runId) {
    requireUuidV4(requestId);
    requireUuidV4(conversationId);
    requireUuidV4(runId);
    var actor = authority.authorize(token, ConversationPermission.GENERATE);
    return modelRuns.cancelOwned(actor, requestId, conversationId, runId, clock.instant());
  }

  private static String canonicalTitle(String value) {
    if (value == null) throw invalidRequest();
    String title = Normalizer.normalize(value, Normalizer.Form.NFC).strip();
    int length = title.codePointCount(0, title.length());
    if (length < 1
        || length > 120
        || hasInvalidUnicode(title)
        || title.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint))) {
      throw invalidRequest();
    }
    return title;
  }

  private static String canonicalMessage(String value) {
    if (value == null) throw invalidRequest();
    String message = Normalizer.normalize(value, Normalizer.Form.NFC).strip();
    int length = message.codePointCount(0, message.length());
    if (length < 1 || length > 16_000 || hasInvalidUnicode(message) || message.indexOf('\0') >= 0) {
      throw invalidRequest();
    }
    return message;
  }

  private static void requireMutation(UUID conversationId, long expectedVersion) {
    requireUuidV4(conversationId);
    if (expectedVersion < 1) throw invalidRequest();
  }

  private static boolean hasInvalidUnicode(String value) {
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (Character.isHighSurrogate(current)) {
        if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index)))
          return true;
      } else if (Character.isLowSurrogate(current)) {
        return true;
      }
    }
    return false;
  }

  private static void requireUuidV4(UUID value) {
    if (value == null || value.version() != 4 || value.variant() != 2) throw invalidRequest();
  }

  private static ConversationException invalidRequest() {
    return new ConversationException(
        ConversationError.INVALID_REQUEST, "Conversation request is invalid");
  }
}

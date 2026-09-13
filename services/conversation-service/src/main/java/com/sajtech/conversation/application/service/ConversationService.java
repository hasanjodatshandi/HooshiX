package com.sajtech.conversation.application.service;

import com.sajtech.conversation.application.ConversationError;
import com.sajtech.conversation.application.ConversationException;
import com.sajtech.conversation.application.model.ConversationPage;
import com.sajtech.conversation.application.model.ConversationPermission;
import com.sajtech.conversation.application.port.out.ConversationRepository;
import com.sajtech.conversation.domain.Conversation;
import java.text.Normalizer;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

public final class ConversationService {
  private static final int DEFAULT_PAGE_SIZE = 20;
  private final ConversationAuthority authority;
  private final ConversationRepository repository;
  private final Clock clock;

  public ConversationService(
      ConversationAuthority authority, ConversationRepository repository, Clock clock) {
    this.authority = Objects.requireNonNull(authority);
    this.repository = Objects.requireNonNull(repository);
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
    Objects.requireNonNull(conversationId);
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

  private static String canonicalTitle(String value) {
    if (value == null) throw invalidRequest();
    String title = Normalizer.normalize(value, Normalizer.Form.NFC).strip();
    int length = title.codePointCount(0, title.length());
    if (length < 1
        || length > 120
        || title.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint))) {
      throw invalidRequest();
    }
    return title;
  }

  private static void requireMutation(UUID conversationId, long expectedVersion) {
    if (conversationId == null || expectedVersion < 1) throw invalidRequest();
  }

  private static void requireUuidV4(UUID value) {
    if (value == null || value.version() != 4 || value.variant() != 2) throw invalidRequest();
  }

  private static ConversationException invalidRequest() {
    return new ConversationException(
        ConversationError.INVALID_REQUEST, "Conversation request is invalid");
  }
}

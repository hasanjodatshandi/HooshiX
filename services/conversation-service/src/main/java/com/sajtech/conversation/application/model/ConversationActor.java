package com.sajtech.conversation.application.model;

import java.util.UUID;

public record ConversationActor(UUID userId, UUID tenantId, UUID membershipId, String sessionId) {
  public ConversationActor {
    if (userId == null
        || tenantId == null
        || membershipId == null
        || sessionId == null
        || sessionId.length() != 43
        || sessionId.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("Conversation actor is invalid");
    }
  }
}

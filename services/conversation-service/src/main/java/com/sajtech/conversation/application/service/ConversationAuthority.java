package com.sajtech.conversation.application.service;

import com.sajtech.conversation.application.model.ConversationActor;
import com.sajtech.conversation.application.model.ConversationPermission;
import com.sajtech.conversation.application.port.out.AccessTokenVerifier;
import com.sajtech.conversation.application.port.out.PermissionAuthorizer;
import java.util.Objects;

public final class ConversationAuthority {
  private final AccessTokenVerifier tokens;
  private final PermissionAuthorizer permissions;

  public ConversationAuthority(AccessTokenVerifier tokens, PermissionAuthorizer permissions) {
    this.tokens = Objects.requireNonNull(tokens);
    this.permissions = Objects.requireNonNull(permissions);
  }

  public ConversationActor authorize(String accessToken, ConversationPermission permission) {
    ConversationActor actor = tokens.verify(accessToken);
    permissions.check(actor, Objects.requireNonNull(permission).key());
    return actor;
  }
}

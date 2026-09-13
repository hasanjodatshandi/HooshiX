package com.sajtech.conversation.application.port.out;

import com.sajtech.conversation.application.model.ConversationActor;

public interface PermissionAuthorizer {
  void check(ConversationActor actor, String permissionKey);
}

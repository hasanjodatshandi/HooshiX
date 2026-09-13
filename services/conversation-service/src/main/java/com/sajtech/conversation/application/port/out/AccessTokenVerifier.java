package com.sajtech.conversation.application.port.out;

import com.sajtech.conversation.application.model.ConversationActor;

public interface AccessTokenVerifier {
  ConversationActor verify(String accessToken);
}

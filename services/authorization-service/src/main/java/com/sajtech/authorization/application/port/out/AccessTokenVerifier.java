package com.sajtech.authorization.application.port.out;

import com.sajtech.authorization.application.model.ActorContext;

public interface AccessTokenVerifier {
  ActorContext verify(String token);
}

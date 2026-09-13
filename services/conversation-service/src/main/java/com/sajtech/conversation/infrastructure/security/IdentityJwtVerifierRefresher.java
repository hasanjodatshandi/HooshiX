package com.sajtech.conversation.infrastructure.security;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public final class IdentityJwtVerifierRefresher {
  private static final Logger LOG = LoggerFactory.getLogger(IdentityJwtVerifierRefresher.class);
  private final IdentityJwtVerifier verifier;

  public IdentityJwtVerifierRefresher(IdentityJwtVerifier verifier) {
    this.verifier = Objects.requireNonNull(verifier);
  }

  @Scheduled(fixedDelayString = "${conversation.jwt-verifier-refresh-interval}")
  public void refresh() {
    try {
      verifier.refresh();
    } catch (RuntimeException exception) {
      LOG.atWarn()
          .addKeyValue("event_code", "conversation_identity_jwt_refresh_failed")
          .log("Conversation Identity JWT verifier refresh failed");
    }
  }
}

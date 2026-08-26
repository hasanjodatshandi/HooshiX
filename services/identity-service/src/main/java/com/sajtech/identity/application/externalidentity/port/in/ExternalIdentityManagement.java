package com.sajtech.identity.application.externalidentity.port.in;

import com.sajtech.identity.application.authentication.model.AuthenticationSession;
import com.sajtech.identity.application.externalidentity.model.ExternalIdentityEvidence;
import java.util.UUID;

public interface ExternalIdentityManagement {
  AuthenticationSession establish(
      UUID requestId, ExternalIdentityEvidence evidence, byte[] clientAddress);

  AuthenticationSession link(
      UUID requestId,
      String refreshCredential,
      ExternalIdentityEvidence evidence,
      byte[] clientAddress);

  AuthenticationSession unlink(UUID requestId, String refreshCredential, String issuer);

  boolean googleLinked(UUID requestId, String refreshCredential);
}

package com.sajtech.webbff.application.port.out;

import com.sajtech.webbff.application.model.VerifiedGoogleIdentity;
import java.net.URI;

public interface GoogleOidcProvider {
  URI authorizationUri(String state, String nonce, String codeChallenge, String redirectUri);

  VerifiedGoogleIdentity exchangeAndValidate(
      String code, String verifier, String expectedNonce, String redirectUri);
}

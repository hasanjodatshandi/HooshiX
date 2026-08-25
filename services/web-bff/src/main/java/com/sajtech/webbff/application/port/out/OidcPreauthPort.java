package com.sajtech.webbff.application.port.out;

import com.sajtech.webbff.application.model.*;
import java.util.Optional;

public interface OidcPreauthPort {
  OidcAuthorizationStart begin(
      String existingCookie,
      OidcPurpose purpose,
      String browserSessionLocator,
      String redirectUri,
      String returnTarget);

  Optional<OidcPreauthTransaction> consume(String cookie, String state);
}

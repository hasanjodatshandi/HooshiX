package com.sajtech.identity.application.authentication.port.out;

import com.sajtech.identity.application.authentication.model.AuthenticationTenantSelection;
import java.util.UUID;

public interface AuthenticationTenantSelectionPort {
  AuthenticationTenantSelection resolveAfterPrimaryAuthentication(UUID userId);
}

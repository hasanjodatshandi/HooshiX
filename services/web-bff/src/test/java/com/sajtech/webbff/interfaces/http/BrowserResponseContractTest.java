package com.sajtech.webbff.interfaces.http;

import static org.assertj.core.api.Assertions.*;

import java.lang.reflect.RecordComponent;
import java.util.*;
import org.junit.jupiter.api.Test;

class BrowserResponseContractTest {
  @Test
  void reviewedBrowserResponsesExposeNoInternalCredentialOrIdentitySessionFields() {
    List<Class<?>> responses =
        List.of(
            BrowserAuthController.SessionResponse.class,
            IdentityTenantController.TenantChoice.class,
            IdentityTenantController.TenantList.class,
            IdentityTenantController.TenantCreated.class,
            IdentityTenantController.TenantSelectionResponse.class,
            IdentityTenantController.InvitationCreated.class,
            IdentityTenantController.AcceptedInvitation.class,
            IdentityTenantController.RemovalResult.class);
    for (Class<?> type : responses) {
      assertThat(type.isRecord()).isTrue();
      for (RecordComponent component : type.getRecordComponents()) {
        assertThat(component.getName())
            .as(type.getSimpleName() + "." + component.getName())
            .doesNotMatch(
                "(?i).*(refresh|access.?token|identity.?session|refresh.?family|user.?id|audience|jwt).*");
      }
    }
  }
}

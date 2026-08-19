package com.sajtech.identity.interfaces.authentication.grpc;

import static org.assertj.core.api.Assertions.*;

import com.sajtech.identity.contract.v1.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class IdentityAuthenticationContractTest {
  @Test
  void
      authenticationContractKeepsRefreshSecretsServerToBffAndNoOrdinaryAccessTokenOnLoginOrRefresh() {
    assertThat(AuthenticateLocalRequest.getDescriptor().findFieldByName("request_id").getNumber())
        .isEqualTo(1);
    assertThat(
            AuthenticateLocalRequest.getDescriptor().findFieldByName("client_address").getNumber())
        .isEqualTo(5);
    assertThat(AuthenticateLocalResponse.getDescriptor().findFieldByName("refresh_credential"))
        .isNotNull();
    assertThat(AuthenticateLocalResponse.getDescriptor().findFieldByName("access_token")).isNull();
    assertThat(RefreshSessionResponse.getDescriptor().findFieldByName("refresh_credential"))
        .isNotNull();
    assertThat(RefreshSessionResponse.getDescriptor().findFieldByName("access_token")).isNull();
    assertThat(
            AuthenticationSessionMode.AUTHENTICATION_SESSION_MODE_AUTHENTICATED_ONBOARDING
                .getNumber())
        .isNotZero();
  }

  @Test
  void authenticationServiceExposesOnlyReviewedV1Operations() {
    List<String> methods =
        IdentityAuthenticationServiceGrpc.getServiceDescriptor().getMethods().stream()
            .map(method -> method.getBareMethodName())
            .toList();
    assertThat(methods)
        .containsExactly(
            "AuthenticateLocal",
            "RefreshSession",
            "LogoutCurrent",
            "LogoutAll",
            "IssueAudienceAccessToken");
  }
}

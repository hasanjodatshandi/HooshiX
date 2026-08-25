package com.sajtech.identity.interfaces.mfa.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajtech.identity.contract.v1.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class IdentityMfaContractTest {
  @Test
  void mfaServiceExposesOnlyTheReviewedVersionOneOperations() {
    List<String> methods =
        IdentityMfaServiceGrpc.getServiceDescriptor().getMethods().stream()
            .map(method -> method.getBareMethodName())
            .toList();

    assertThat(methods)
        .containsExactly(
            "GetMfaStatus",
            "StartTotpEnrollment",
            "ConfirmTotpEnrollment",
            "DisableTotp",
            "RotateRecoveryCodes",
            "CompleteMfaAuthentication");
  }

  @Test
  void proofAndOneTimeSecretFieldsKeepStableNumbersAndExplicitTypes() {
    assertThat(MfaProof.getDescriptor().findFieldByName("type").getNumber()).isEqualTo(1);
    assertThat(MfaProof.getDescriptor().findFieldByName("code").getNumber()).isEqualTo(2);
    assertThat(
            CompleteMfaAuthenticationRequest.getDescriptor()
                .findFieldByName("mfa_challenge")
                .getNumber())
        .isEqualTo(2);
    assertThat(
            ConfirmTotpEnrollmentResponse.getDescriptor()
                .findFieldByName("recovery_codes")
                .getNumber())
        .isEqualTo(1);
    assertThat(MfaProofType.MFA_PROOF_TYPE_TOTP.getNumber()).isNotZero();
    assertThat(MfaProofType.MFA_PROOF_TYPE_RECOVERY_CODE.getNumber()).isNotZero();
  }
}

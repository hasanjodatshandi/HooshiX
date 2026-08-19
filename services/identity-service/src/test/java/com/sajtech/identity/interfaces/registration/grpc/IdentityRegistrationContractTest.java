package com.sajtech.identity.interfaces.registration.grpc;

import static org.assertj.core.api.Assertions.*;

import com.sajtech.identity.contract.v1.*;
import org.junit.jupiter.api.Test;

class IdentityRegistrationContractTest {
  @Test
  void registrationLocaleIsPinnedToFieldFiveAndResendCannotChangeLocale() {
    assertThat(RegisterLocalRequest.getDescriptor().findFieldByName("locale").getNumber())
        .isEqualTo(5);
    assertThat(ResendRegistrationVerificationRequest.getDescriptor().findFieldByName("locale"))
        .isNull();
    assertThat(RegistrationLocale.REGISTRATION_LOCALE_FA.getNumber()).isNotZero();
    assertThat(RegistrationLocale.REGISTRATION_LOCALE_EN.getNumber()).isNotZero();
  }
}

package com.sajtech.identity.application.mfa.port.in;

import com.sajtech.identity.application.mfa.model.MfaSessionMutation;
import com.sajtech.identity.application.mfa.model.MfaStatus;
import com.sajtech.identity.application.mfa.model.TotpEnrollmentStart;

public interface MfaManagement {
  MfaStatus status(GetMfaStatusCommand command);

  TotpEnrollmentStart startEnrollment(StartTotpEnrollmentCommand command);

  MfaSessionMutation confirmEnrollment(ConfirmTotpEnrollmentCommand command);

  MfaSessionMutation disable(DisableTotpCommand command);

  MfaSessionMutation rotateRecoveryCodes(RotateRecoveryCodesCommand command);
}

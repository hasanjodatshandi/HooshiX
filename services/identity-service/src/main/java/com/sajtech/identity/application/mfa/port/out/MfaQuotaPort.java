package com.sajtech.identity.application.mfa.port.out;

import com.sajtech.identity.application.mfa.model.MfaQuotaOperation;
import java.util.UUID;

public interface MfaQuotaPort {
  void consume(MfaQuotaOperation operation, UUID userId, byte[] clientAddress);

  void consumeRecoverySource(byte[] clientAddress);

  void recordRecoveryFailure(UUID userId);
}

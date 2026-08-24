package com.sajtech.notification.application.delivery.port.out;

import com.sajtech.notification.application.delivery.model.DeliveryAttemptClaim;
import com.sajtech.notification.application.delivery.model.ProviderDispatchOutcome;
import java.time.Duration;
import java.util.List;

public interface DeliveryAttemptRepository {
  List<DeliveryAttemptClaim> claimDue(int batchSize, Duration lease);

  void recordProviderAccepted(
      DeliveryAttemptClaim claim, ProviderDispatchOutcome outcome, Duration reconciliationDelay);

  void recordTransientRetry(
      DeliveryAttemptClaim claim, ProviderDispatchOutcome outcome, Duration retryDelay);

  void recordAmbiguous(
      DeliveryAttemptClaim claim, ProviderDispatchOutcome outcome, Duration reconciliationDelay);

  void recordPermanentFailure(DeliveryAttemptClaim claim, ProviderDispatchOutcome outcome);

  void recordLocalPermanentFailure(DeliveryAttemptClaim claim);

  void recordExpired(DeliveryAttemptClaim claim, ProviderDispatchOutcome outcome);
}

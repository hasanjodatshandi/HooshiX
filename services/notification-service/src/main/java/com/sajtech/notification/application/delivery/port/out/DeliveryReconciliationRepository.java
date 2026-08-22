package com.sajtech.notification.application.delivery.port.out;

import com.sajtech.notification.application.delivery.model.DeliveryReconciliationClaim;
import com.sajtech.notification.application.delivery.model.ProviderReconciliationOutcome;
import java.time.Duration;
import java.util.List;

public interface DeliveryReconciliationRepository {
  int recoverStaleDispatches(int batchSize);

  List<DeliveryReconciliationClaim> claimDue(int batchSize, Duration lease);

  void recordDelivered(DeliveryReconciliationClaim claim, ProviderReconciliationOutcome outcome);

  void recordPermanentFailure(
      DeliveryReconciliationClaim claim, ProviderReconciliationOutcome outcome);

  void reschedule(
      DeliveryReconciliationClaim claim, ProviderReconciliationOutcome outcome, Duration delay);

  void recordStatusUnknown(
      DeliveryReconciliationClaim claim, ProviderReconciliationOutcome outcome);
}

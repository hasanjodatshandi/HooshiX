package com.sajtech.notification.application.delivery.usecase;

import com.sajtech.notification.application.delivery.model.DeliveryBatchResult;
import com.sajtech.notification.application.delivery.model.ProviderDispatchMessage;
import com.sajtech.notification.application.delivery.port.in.RunDeliveryBatch;
import com.sajtech.notification.application.delivery.port.out.DeliveryAttemptRepository;
import com.sajtech.notification.application.delivery.port.out.NotificationProviderGateway;
import java.util.List;

public final class RunDeliveryBatchService implements RunDeliveryBatch {
  private final DeliveryAttemptRepository repository;
  private final List<NotificationProviderGateway> providers;

  public RunDeliveryBatchService(
      DeliveryAttemptRepository repository, List<NotificationProviderGateway> providers) {
    this.repository = repository;
    this.providers = List.copyOf(providers);
  }

  @Override
  public DeliveryBatchResult run(int batchSize) {
    if (batchSize <= 0) {
      throw new IllegalArgumentException("Batch size must be positive");
    }
    List<ProviderDispatchMessage> messages = repository.claimDue(batchSize);
    int completed = 0;
    for (ProviderDispatchMessage message : messages) {
      NotificationProviderGateway provider = findProvider(message);
      provider.dispatch(message);
      repository.markCompleted(message);
      completed++;
    }
    return new DeliveryBatchResult(messages.size(), completed);
  }

  private NotificationProviderGateway findProvider(ProviderDispatchMessage message) {
    return providers.stream()
        .filter(provider -> provider.channel() == message.channel())
        .findFirst()
        .orElseThrow(
            () -> new IllegalStateException("No notification provider for channel"));
  }
}

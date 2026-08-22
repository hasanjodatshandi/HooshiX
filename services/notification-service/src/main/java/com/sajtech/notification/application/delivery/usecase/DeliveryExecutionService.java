package com.sajtech.notification.application.delivery.usecase;

import com.sajtech.notification.application.delivery.model.ProviderDispatchMessage;
import com.sajtech.notification.application.delivery.model.ProviderDispatchOutcome;
import com.sajtech.notification.application.delivery.port.out.DeliveryExecutionRepository;
import com.sajtech.notification.application.delivery.port.out.NotificationProviderGateway;
import java.util.List;

public final class DeliveryExecutionService {
  private final DeliveryExecutionRepository repository;
  private final List<NotificationProviderGateway> providers;

  public DeliveryExecutionService(DeliveryExecutionRepository repository, List<NotificationProviderGateway> providers) {
    this.repository = repository;
    this.providers = List.copyOf(providers);
  }

  public void execute(ProviderDispatchMessage message) {
    NotificationProviderGateway provider = providers.stream()
        .filter(candidate -> candidate.channel() == message.channel())
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Provider is unavailable"));

    ProviderDispatchOutcome outcome = provider.dispatch(message);
    repository.recordOutcome(message, outcome);
  }
}

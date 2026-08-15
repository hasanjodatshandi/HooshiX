package com.sajtech.compromisedpassword.infrastructure.observability.health;

import com.sajtech.compromisedpassword.infrastructure.lookup.dataset.DatasetGuard;
import com.sajtech.compromisedpassword.infrastructure.lookup.dataset.DatasetState;
import java.util.Objects;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

public final class DatasetHealthIndicator implements HealthIndicator {
  private final DatasetGuard datasetGuard;

  public DatasetHealthIndicator(DatasetGuard datasetGuard) {
    this.datasetGuard = Objects.requireNonNull(datasetGuard, "datasetGuard");
  }

  @Override
  public Health health() {
    DatasetState state = datasetGuard.state();
    if (state == DatasetState.READY) {
      return Health.up().withDetail("state", state.name()).build();
    }
    return Health.down().withDetail("state", state.name()).build();
  }
}

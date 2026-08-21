package com.sajtech.identity.infrastructure.observability;

import com.sajtech.identity.application.registration.RegistrationException;
import com.sajtech.identity.application.registration.model.QuotaRequest;
import com.sajtech.identity.application.registration.port.out.SemanticQuotaPort;
import io.micrometer.observation.*;

public final class ObservedSemanticQuota implements SemanticQuotaPort {
  private final SemanticQuotaPort delegate;
  private final ObservationRegistry observations;

  public ObservedSemanticQuota(SemanticQuotaPort delegate, ObservationRegistry observations) {
    this.delegate = delegate;
    this.observations = observations;
  }

  @Override
  public void consume(QuotaRequest request) {
    Observation o = start(request);
    String outcome = "INTERNAL";
    try {
      delegate.consume(request);
      outcome = "ALLOWED";
    } catch (RegistrationException e) {
      outcome = e.error().name();
      throw e;
    } finally {
      stop(o, outcome);
    }
  }

  private Observation start(QuotaRequest request) {
    try {
      return Observation.start("identity.dependency", observations)
          .lowCardinalityKeyValue("dependency", "security-redis")
          .lowCardinalityKeyValue("operation", request.operation().name());
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static void stop(Observation o, String outcome) {
    if (o == null) return;
    try {
      o.lowCardinalityKeyValue("outcome", outcome);
      o.stop();
    } catch (RuntimeException ignored) {
    }
  }
}

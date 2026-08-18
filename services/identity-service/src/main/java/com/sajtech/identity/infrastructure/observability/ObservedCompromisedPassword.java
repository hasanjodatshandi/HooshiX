package com.sajtech.identity.infrastructure.observability;

import com.sajtech.identity.application.registration.RegistrationException;
import com.sajtech.identity.application.registration.port.out.CompromisedPasswordPort;
import io.micrometer.observation.*;

public final class ObservedCompromisedPassword implements CompromisedPasswordPort {
  private final CompromisedPasswordPort delegate;
  private final ObservationRegistry observations;

  public ObservedCompromisedPassword(
      CompromisedPasswordPort delegate, ObservationRegistry observations) {
    this.delegate = delegate;
    this.observations = observations;
  }

  @Override
  public void requireNotCompromised(String password) {
    Observation o = start();
    String outcome = "INTERNAL";
    try {
      delegate.requireNotCompromised(password);
      outcome = "CLEAN";
    } catch (RegistrationException e) {
      outcome = e.error().name();
      throw e;
    } finally {
      stop(o, outcome);
    }
  }

  private Observation start() {
    try {
      return Observation.start("identity.dependency", observations)
          .lowCardinalityKeyValue("dependency", "compromised-password");
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

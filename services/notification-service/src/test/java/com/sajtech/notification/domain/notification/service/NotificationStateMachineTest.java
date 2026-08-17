package com.sajtech.notification.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sajtech.notification.domain.notification.model.NotificationLifecycle;
import org.junit.jupiter.api.Test;

class NotificationStateMachineTest {
  private final NotificationStateMachine stateMachine = new NotificationStateMachine();

  @Test
  void permitsCanonicalAcceptedToSendingProgression() {
    assertThat(
            stateMachine.transition(
                NotificationLifecycle.ACCEPTED, NotificationLifecycle.SENDING))
        .isEqualTo(NotificationLifecycle.SENDING);
  }

  @Test
  void permitsAmbiguousToReconciliationProgression() {
    assertThat(
            stateMachine.transition(
                NotificationLifecycle.AMBIGUOUS, NotificationLifecycle.RECONCILING))
        .isEqualTo(NotificationLifecycle.RECONCILING);
  }

  @Test
  void rejectsTerminalStateMutation() {
    assertThatThrownBy(
            () ->
                stateMachine.transition(
                    NotificationLifecycle.DELIVERED, NotificationLifecycle.SENDING))
        .isInstanceOf(IllegalStateException.class);
  }
}

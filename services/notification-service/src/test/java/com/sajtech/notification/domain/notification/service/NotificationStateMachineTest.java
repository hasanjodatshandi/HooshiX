package com.sajtech.notification.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sajtech.notification.domain.notification.model.NotificationLifecycle;
import org.junit.jupiter.api.Test;

class NotificationStateMachineTest {
  private final NotificationStateMachine stateMachine = new NotificationStateMachine();

  @Test
  void allowsCanonicalDispatchAndDeliveryTransitions() {
    assertThatCode(
            () ->
                stateMachine.requireTransition(
                    NotificationLifecycle.ACCEPTED, NotificationLifecycle.DISPATCHING))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                stateMachine.requireTransition(
                    NotificationLifecycle.DISPATCHING,
                    NotificationLifecycle.PROVIDER_ACCEPTED))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                stateMachine.requireTransition(
                    NotificationLifecycle.PROVIDER_ACCEPTED, NotificationLifecycle.DELIVERED))
        .doesNotThrowAnyException();
  }

  @Test
  void terminalStateCannotTransition() {
    assertThatThrownBy(
            () ->
                stateMachine.requireTransition(
                    NotificationLifecycle.DELIVERED, NotificationLifecycle.DISPATCHING))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Terminal");
  }
}

package com.sajtech.conversation.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sajtech.conversation.application.model.ConversationActor;
import com.sajtech.conversation.application.model.ConversationPermission;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ConversationAuthorityTest {
  @Test
  void validTokenIsVerifiedBeforeExactlyOnePermissionCheck() {
    ConversationActor actor = actor();
    AtomicInteger verifications = new AtomicInteger();
    AtomicInteger checks = new AtomicInteger();
    var authority =
        new ConversationAuthority(
            token -> {
              verifications.incrementAndGet();
              assertThat(token).isEqualTo("access-token");
              return actor;
            },
            (actual, permission) -> {
              checks.incrementAndGet();
              assertThat(actual).isSameAs(actor);
              assertThat(permission).isEqualTo("conversation.generate");
            });

    assertThat(authority.authorize("access-token", ConversationPermission.GENERATE))
        .isSameAs(actor);
    assertThat(verifications).hasValue(1);
    assertThat(checks).hasValue(1);
  }

  @Test
  void tokenFailurePreventsAuthorizationCall() {
    AtomicInteger checks = new AtomicInteger();
    var authority =
        new ConversationAuthority(
            token -> {
              throw new IllegalStateException("invalid");
            },
            (actor, permission) -> checks.incrementAndGet());

    assertThatThrownBy(() -> authority.authorize("bad", ConversationPermission.READ))
        .isInstanceOf(IllegalStateException.class);
    assertThat(checks).hasValue(0);
  }

  private static ConversationActor actor() {
    return new ConversationActor(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "s".repeat(43));
  }
}

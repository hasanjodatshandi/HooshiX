package com.sajtech.notification.application.submit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sajtech.notification.application.submit.NotificationSubmissionException;
import com.sajtech.notification.domain.notification.model.NotificationChannel;
import org.junit.jupiter.api.Test;

class NotificationRecipientCanonicalizerTest {
  private final NotificationRecipientCanonicalizer canonicalizer =
      new NotificationRecipientCanonicalizer();

  @Test
  void canonicalizesProviderNeutralEmailWithoutMailboxRewriting() {
    assertThat(canonicalizer.canonicalize(NotificationChannel.EMAIL, "User+tag@EXAMPLE.com"))
        .isEqualTo("User+tag@example.com");
  }

  @Test
  void rejectsDisplayNameAndNonE164Phone() {
    assertThatThrownBy(
            () ->
                canonicalizer.canonicalize(
                    NotificationChannel.EMAIL, "Person <person@example.com>"))
        .isInstanceOf(NotificationSubmissionException.class);
    assertThatThrownBy(() -> canonicalizer.canonicalize(NotificationChannel.SMS, "09121234567"))
        .isInstanceOf(NotificationSubmissionException.class);
  }

  @Test
  void rejectsInvalidDotAtomEmailLocalPart() {
    for (String email :
        new String[] {".person@example.com", "person.@example.com", "per..son@example.com"}) {
      assertThatThrownBy(() -> canonicalizer.canonicalize(NotificationChannel.EMAIL, email))
          .isInstanceOf(NotificationSubmissionException.class);
    }
  }
}

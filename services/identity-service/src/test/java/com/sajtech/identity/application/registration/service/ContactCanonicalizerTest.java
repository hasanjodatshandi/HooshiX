package com.sajtech.identity.application.registration.service;

import static org.assertj.core.api.Assertions.*;

import com.sajtech.identity.domain.registration.valueobject.*;
import org.junit.jupiter.api.Test;

class ContactCanonicalizerTest {
  private final ContactCanonicalizer canonicalizer = new ContactCanonicalizer();

  @Test
  void emailUsesLowercaseIdentityButPreservesDeliverySpelling() {
    var c = canonicalizer.canonicalize(RegistrationChannel.EMAIL, " User.Name+tag@Example.COM ");
    assertThat(c.canonicalValue()).isEqualTo("user.name+tag@example.com");
    assertThat(c.deliveryValue()).isEqualTo("User.Name+tag@Example.COM");
  }

  @Test
  void providerSpecificGmailRewritingIsNotApplied() {
    var c = canonicalizer.canonicalize(RegistrationChannel.EMAIL, "First.Last+tag@gmail.com");
    assertThat(c.canonicalValue()).isEqualTo("first.last+tag@gmail.com");
  }

  @Test
  void phoneRequiresE164() {
    assertThat(
            canonicalizer.canonicalize(RegistrationChannel.PHONE, "+989121234567").canonicalValue())
        .isEqualTo("+989121234567");
    assertThatThrownBy(() -> canonicalizer.canonicalize(RegistrationChannel.PHONE, "09121234567"))
        .isInstanceOf(RuntimeException.class);
  }
}

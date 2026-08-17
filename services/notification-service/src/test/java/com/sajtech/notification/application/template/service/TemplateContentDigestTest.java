package com.sajtech.notification.application.template.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TemplateContentDigestTest {
  private final TemplateContentDigest digest = new TemplateContentDigest();

  @Test
  void computesCanonicalSeedDigest() {
    assertThat(
            digest.compute(
                "Verify your SajTech account",
                "Your verification code is {code}. It expires in {expires_minutes} minutes.",
                "<p>Your verification code is <strong>{code}</strong>.</p><p>It expires in {expires_minutes} minutes.</p>"))
        .isEqualTo("8b9fd6864ad48cfcb74fdea0a56a500245545e7a40092623cd41be7cbf34c81d");
  }
}

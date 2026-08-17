package com.sajtech.notification.application.template.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sajtech.notification.application.submit.NotificationSubmissionException;
import com.sajtech.notification.application.submit.model.VerificationCodeContent;
import com.sajtech.notification.application.template.model.NotificationTemplateVersion;
import com.sajtech.notification.domain.notification.model.NotificationChannel;
import com.sajtech.notification.domain.notification.model.NotificationSemanticType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BoundedTemplateRendererTest {
  private final BoundedTemplateRenderer renderer = new BoundedTemplateRenderer();

  @Test
  void rendersOnlyTypedPlaceholdersAndEscapesHtmlValues() {
    var template =
        new NotificationTemplateVersion(
            UUID.randomUUID(),
            NotificationChannel.EMAIL,
            NotificationSemanticType.REGISTRATION_VERIFICATION_CODE,
            "en",
            "a".repeat(64),
            "Code {code}",
            "Code {code} expires {expires_minutes}",
            "<strong>{code}</strong>");
    var content =
        new VerificationCodeContent(
            NotificationSemanticType.REGISTRATION_VERIFICATION_CODE, "12345678", 10);

    var rendered = renderer.render(template, content);

    assertThat(rendered.subject()).isEqualTo("Code 12345678");
    assertThat(rendered.text()).contains("12345678", "10");
    assertThat(rendered.html()).isEqualTo("<strong>12345678</strong>");
  }

  @Test
  void rejectsUnknownPlaceholder() {
    var template =
        new NotificationTemplateVersion(
            UUID.randomUUID(),
            NotificationChannel.SMS,
            NotificationSemanticType.REGISTRATION_VERIFICATION_CODE,
            "en",
            "a".repeat(64),
            null,
            "Code {unknown}",
            null);
    var content =
        new VerificationCodeContent(
            NotificationSemanticType.REGISTRATION_VERIFICATION_CODE, "12345678", 10);

    assertThatThrownBy(() -> renderer.render(template, content))
        .isInstanceOf(NotificationSubmissionException.class);
  }
}

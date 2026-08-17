package com.sajtech.notification.infrastructure.persistence;

import com.sajtech.notification.application.submit.port.out.NotificationTemplateCatalog;
import com.sajtech.notification.application.template.model.NotificationTemplateVersion;
import com.sajtech.notification.application.template.service.TemplateContentDigest;
import com.sajtech.notification.domain.notification.model.NotificationChannel;
import com.sajtech.notification.domain.notification.model.NotificationSemanticType;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

public final class JooqNotificationTemplateCatalog implements NotificationTemplateCatalog {
  private final DSLContext dsl;
  private final TemplateContentDigest contentDigest;

  public JooqNotificationTemplateCatalog(DSLContext dsl, TemplateContentDigest contentDigest) {
    this.dsl = dsl;
    this.contentDigest = contentDigest;
  }

  @Override
  public Optional<NotificationTemplateVersion> findActive(
      NotificationChannel channel, NotificationSemanticType semanticType, String locale) {
    return dsl.fetchOptional(
            """
            SELECT v.version_id, d.channel, d.semantic_type, d.locale, v.content_sha256,
                   v.subject_template, v.text_template, v.html_template
            FROM notification_template_definition d
            JOIN notification_template_activation a ON a.definition_id = d.definition_id
            JOIN notification_template_version v ON v.version_id = a.active_version_id
            WHERE d.channel = ? AND d.semantic_type = ? AND d.locale = ?
              AND v.state = 'PUBLISHED'
            """,
            channel.name(),
            semanticType.name(),
            locale)
        .map(
            record -> {
              String expectedDigest = record.get("content_sha256", String.class).trim();
              String subject = record.get("subject_template", String.class);
              String text = record.get("text_template", String.class);
              String html = record.get("html_template", String.class);
              if (!contentDigest.matches(expectedDigest, subject, text, html)) {
                throw new IllegalStateException("Active notification template digest mismatch");
              }
              return new NotificationTemplateVersion(
                  record.get("version_id", UUID.class),
                  NotificationChannel.valueOf(record.get("channel", String.class)),
                  NotificationSemanticType.valueOf(record.get("semantic_type", String.class)),
                  record.get("locale", String.class),
                  expectedDigest,
                  subject,
                  text,
                  html);
            });
  }
}

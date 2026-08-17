package com.sajtech.notification.application.template.service;

import com.sajtech.notification.application.submit.model.SemanticContent;
import com.sajtech.notification.application.template.model.NotificationTemplateVersion;
import com.sajtech.notification.application.template.model.RenderedNotification;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BoundedTemplateRenderer {
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z_]+)}");
  private static final int MAX_TEMPLATE_LENGTH = 16_384;
  private static final int MAX_RENDERED_LENGTH = 32_768;
  private static final int MAX_PLACEHOLDERS = 32;

  public RenderedNotification render(
      NotificationTemplateVersion template, SemanticContent content) {
    if (template == null || content == null) {
      throw new IllegalArgumentException("Template and semantic content are required");
    }
    Map<String, String> variables = content.templateVariables();
    String subject = renderPart(template.subjectTemplate(), variables, true);
    String text = renderPart(template.textTemplate(), variables, false);
    String html = renderPart(template.htmlTemplate(), variables, true);
    return new RenderedNotification(subject, text, html);
  }

  private static String renderPart(
      String source, Map<String, String> variables, boolean nullableOrOptional) {
    if (source == null) {
      if (nullableOrOptional) {
        return null;
      }
      throw new IllegalArgumentException("Required notification template body is missing");
    }
    if (source.length() > MAX_TEMPLATE_LENGTH) {
      throw new IllegalArgumentException("Notification template exceeds size limit");
    }
    Matcher matcher = PLACEHOLDER.matcher(source);
    StringBuffer rendered = new StringBuffer(source.length());
    int placeholderCount = 0;
    Set<String> usedKeys = new java.util.HashSet<>();
    while (matcher.find()) {
      placeholderCount++;
      if (placeholderCount > MAX_PLACEHOLDERS) {
        throw new IllegalArgumentException("Notification template has too many placeholders");
      }
      String key = matcher.group(1);
      String replacement = variables.get(key);
      if (replacement == null) {
        throw new IllegalArgumentException("Notification template references unknown variable");
      }
      usedKeys.add(key);
      matcher.appendReplacement(rendered, Matcher.quoteReplacement(replacement));
      if (rendered.length() > MAX_RENDERED_LENGTH) {
        throw new IllegalArgumentException("Rendered notification exceeds size limit");
      }
    }
    matcher.appendTail(rendered);
    if (rendered.length() > MAX_RENDERED_LENGTH) {
      throw new IllegalArgumentException("Rendered notification exceeds size limit");
    }
    if (!usedKeys.containsAll(variables.keySet())) {
      throw new IllegalArgumentException("Notification semantic variable is not used by template");
    }
    return rendered.toString();
  }

  public static String sha256(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}

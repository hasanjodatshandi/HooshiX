package com.sajtech.notification.application.template.service;

import com.sajtech.notification.application.submit.NotificationSubmissionError;
import com.sajtech.notification.application.submit.NotificationSubmissionException;
import com.sajtech.notification.application.submit.model.SemanticContent;
import com.sajtech.notification.application.submit.model.VerificationCodeContent;
import com.sajtech.notification.application.template.model.NotificationTemplateVersion;
import com.sajtech.notification.application.template.model.RenderedNotification;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BoundedTemplateRenderer {
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z_]+)}");
  private static final int MAX_SUBJECT_CHARS = 200;
  private static final int MAX_TEXT_CHARS = 4096;
  private static final int MAX_HTML_CHARS = 8192;

  public RenderedNotification render(
      NotificationTemplateVersion template, SemanticContent semanticContent) {
    Map<String, String> parameters = parameters(semanticContent);
    String subject = renderOptional(template.subjectTemplate(), parameters, false, MAX_SUBJECT_CHARS);
    String text = renderRequired(template.textTemplate(), parameters, false, MAX_TEXT_CHARS);
    String html = renderOptional(template.htmlTemplate(), parameters, true, MAX_HTML_CHARS);
    return new RenderedNotification(subject, text, html);
  }

  private static Map<String, String> parameters(SemanticContent content) {
    if (content instanceof VerificationCodeContent verification) {
      return Map.of(
          "code", verification.code(),
          "expires_minutes", Integer.toString(verification.expiresMinutes()));
    }
    return Map.of();
  }

  private static String renderRequired(
      String template, Map<String, String> parameters, boolean html, int maximumLength) {
    if (template == null || template.isBlank()) {
      throw invalidTemplate();
    }
    return render(template, parameters, html, maximumLength);
  }

  private static String renderOptional(
      String template, Map<String, String> parameters, boolean html, int maximumLength) {
    if (template == null) {
      return null;
    }
    return render(template, parameters, html, maximumLength);
  }

  private static String render(
      String template, Map<String, String> parameters, boolean html, int maximumLength) {
    Matcher matcher = PLACEHOLDER.matcher(template);
    StringBuffer result = new StringBuffer(template.length() + 64);
    Set<String> allowed = parameters.keySet();
    while (matcher.find()) {
      String name = matcher.group(1);
      if (!allowed.contains(name)) {
        throw invalidTemplate();
      }
      String value = html ? escapeHtml(parameters.get(name)) : parameters.get(name);
      matcher.appendReplacement(result, Matcher.quoteReplacement(value));
    }
    matcher.appendTail(result);
    if (PLACEHOLDER.matcher(result).find() || result.length() > maximumLength) {
      throw invalidTemplate();
    }
    return result.toString();
  }

  private static String escapeHtml(String value) {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }

  private static NotificationSubmissionException invalidTemplate() {
    return new NotificationSubmissionException(
        NotificationSubmissionError.TEMPLATE_NOT_ACTIVE, "Active notification template is invalid");
  }
}

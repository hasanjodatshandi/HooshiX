package com.sajtech.notification.infrastructure.provider;

import java.util.Locale;
import java.util.regex.Pattern;

public record SmtpEmailProviderConfiguration(
    EmailProviderKind provider,
    String host,
    int port,
    String username,
    String password,
    String fromAddress,
    String fromName) {
  public static final String GOOGLE_HOST = "smtp.gmail.com";
  public static final int GOOGLE_STARTTLS_PORT = 587;
  private static final Pattern DNS_NAME =
      Pattern.compile(
          "(?=.{1,253}\\z)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?");
  private static final Pattern MAILBOX =
      Pattern.compile(
          "[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]{1,64}@[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?");

  public SmtpEmailProviderConfiguration {
    if (provider == null) {
      throw new IllegalArgumentException("Email provider is required");
    }
    host = requireText(host, "SMTP host", 253).toLowerCase(Locale.ROOT);
    username = requireText(username, "SMTP username", 255);
    password = requireSecret(password, "SMTP password", 1024);
    fromAddress = requireText(fromAddress, "SMTP from address", 254);
    fromName = requireText(fromName, "SMTP from name", 100);
    if (!DNS_NAME.matcher(host).matches()) {
      throw new IllegalArgumentException("SMTP host must be a DNS name");
    }
    if (port <= 0 || port > 65_535) {
      throw new IllegalArgumentException("SMTP port is invalid");
    }
    if (!MAILBOX.matcher(fromAddress).matches()) {
      throw new IllegalArgumentException("SMTP from address is invalid");
    }
    if (provider == EmailProviderKind.GOOGLE_GMAIL) {
      if (!GOOGLE_HOST.equals(host) || port != GOOGLE_STARTTLS_PORT) {
        throw new IllegalArgumentException("Google Gmail SMTP endpoint is fixed");
      }
      String canonicalUsername = username.toLowerCase(Locale.ROOT);
      if (!(canonicalUsername.endsWith("@gmail.com")
              || canonicalUsername.endsWith("@googlemail.com"))
          || !username.equalsIgnoreCase(fromAddress)) {
        throw new IllegalArgumentException(
            "Google Gmail SMTP username and from address must be the same Gmail mailbox");
      }
      String compactPassword = password.replace(" ", "");
      if (!compactPassword.matches("[A-Za-z0-9]{16}")) {
        throw new IllegalArgumentException("Google Gmail app password is invalid");
      }
      password = compactPassword;
    }
  }

  public static SmtpEmailProviderConfiguration googleGmail(
      String username, String appPassword, String fromName) {
    return new SmtpEmailProviderConfiguration(
        EmailProviderKind.GOOGLE_GMAIL,
        GOOGLE_HOST,
        GOOGLE_STARTTLS_PORT,
        username,
        appPassword,
        username,
        fromName);
  }

  private static String requireText(String value, String name, int maximum) {
    if (value == null
        || value.isBlank()
        || value.length() > maximum
        || value.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(name + " is invalid");
    }
    return value.trim();
  }

  private static String requireSecret(String value, String name, int maximum) {
    if (value == null
        || value.isBlank()
        || value.length() > maximum
        || value.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(name + " is invalid");
    }
    return value;
  }
}

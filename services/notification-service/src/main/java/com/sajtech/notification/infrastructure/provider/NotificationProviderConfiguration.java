package com.sajtech.notification.infrastructure.provider;

import java.net.URI;

public record NotificationProviderConfiguration(
    String smtpHost,
    int smtpPort,
    String smtpUsername,
    String smtpPassword,
    URI ipPanelBaseUri,
    String ipPanelApiKey,
    String ipPanelFromNumber) {
  public NotificationProviderConfiguration {
    requireText(smtpHost, "SMTP host", 255);
    requireText(smtpUsername, "SMTP username", 255);
    requireText(smtpPassword, "SMTP password", 1024);
    requireText(ipPanelApiKey, "IPPanel API key", 512);
    requireText(ipPanelFromNumber, "IPPanel sender", 32);
    if (smtpPort <= 0 || smtpPort > 65_535) {
      throw new IllegalArgumentException("SMTP port is invalid");
    }
    if (ipPanelBaseUri == null
        || !"https".equalsIgnoreCase(ipPanelBaseUri.getScheme())
        || ipPanelBaseUri.getHost() == null
        || ipPanelBaseUri.getUserInfo() != null
        || ipPanelBaseUri.getQuery() != null
        || ipPanelBaseUri.getFragment() != null) {
      throw new IllegalArgumentException("IPPanel base URI must be an HTTPS origin/path");
    }
    if (!ipPanelFromNumber.matches("[+]?[0-9]{3,20}")) {
      throw new IllegalArgumentException("IPPanel sender number is invalid");
    }
  }

  private static void requireText(String value, String name, int maximum) {
    if (value == null
        || value.isBlank()
        || value.length() > maximum
        || value.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(name + " is invalid");
    }
  }
}

package com.sajtech.notification.infrastructure.provider.ippanel;

import com.sajtech.notification.application.delivery.model.ProviderDispatchMessage;
import com.sajtech.notification.application.delivery.model.ProviderDispatchOutcome;
import com.sajtech.notification.application.delivery.model.ProviderReconciliationOutcome;
import com.sajtech.notification.application.delivery.model.ProviderReconciliationRequest;
import com.sajtech.notification.application.delivery.model.ProviderReconciliationStatus;
import com.sajtech.notification.application.delivery.port.out.NotificationProviderGateway;
import com.sajtech.notification.domain.notification.model.NotificationChannel;
import com.sajtech.notification.domain.notification.model.ProviderAttemptClassification;
import com.sajtech.notification.infrastructure.provider.NotificationProviderConfiguration;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class IpPanelSmsProviderAdapter implements NotificationProviderGateway {
  private static final Duration TOTAL_TIMEOUT = Duration.ofMillis(1500);
  private static final ObjectMapper JSON = new ObjectMapper();
  private final URI baseUri;
  private final String apiKey;
  private final String fromNumber;
  private final HttpClient client;

  public IpPanelSmsProviderAdapter(NotificationProviderConfiguration configuration) {
    this.baseUri = configuration.ipPanelBaseUri();
    this.apiKey = configuration.ipPanelApiKey();
    this.fromNumber = configuration.ipPanelFromNumber();
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(500))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  @Override
  public NotificationChannel channel() {
    return NotificationChannel.SMS;
  }

  @Override
  public boolean liveDelivery() {
    return true;
  }

  @Override
  public ProviderDispatchOutcome dispatch(ProviderDispatchMessage message) {
    if (message == null || message.channel() != NotificationChannel.SMS) {
      throw new IllegalArgumentException("IPPanel accepts SMS dispatch only");
    }
    if (!message.recipient().matches("[+]98[0-9]{10}")) {
      return ProviderDispatchOutcome.live(
          ProviderAttemptClassification.DEFINITIVE_PERMANENT_FAILURE,
          "IPPANEL_RECIPIENT_REJECTED",
          null);
    }
    try {
      String body =
          JSON.writeValueAsString(
              Map.of(
                  "sending_type",
                  "webservice",
                  "from_number",
                  fromNumber,
                  "message",
                  message.text(),
                  "params",
                  Map.of("recipients", List.of(message.recipient()))));
      HttpRequest request =
          HttpRequest.newBuilder(endpoint("/api/send"))
              .timeout(TOTAL_TIMEOUT)
              .header("Authorization", apiKey)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
              .build();
      HttpResponse<String> response =
          client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      return classifySendResponse(response.statusCode(), response.body());
    } catch (HttpTimeoutException exception) {
      return ambiguous();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return ambiguous();
    } catch (IOException | RuntimeException exception) {
      return ambiguous();
    }
  }

  @Override
  public ProviderReconciliationOutcome reconcile(ProviderReconciliationRequest request) {
    if (request == null || request.channel() != NotificationChannel.SMS) {
      throw new IllegalArgumentException("IPPanel reconciliation accepts SMS only");
    }
    String correlationId = request.providerCorrelationId();
    if (correlationId == null || !correlationId.matches("[0-9]{1,32}")) {
      return inconclusive(correlationId);
    }
    try {
      String query =
          "/api/report/recipients?page=1&per_page=10&bulk_id="
              + URLEncoder.encode(correlationId, StandardCharsets.UTF_8);
      HttpRequest httpRequest =
          HttpRequest.newBuilder(endpoint(query))
              .timeout(TOTAL_TIMEOUT)
              .header("Authorization", apiKey)
              .header("Content-Type", "application/json")
              .GET()
              .build();
      HttpResponse<String> response =
          client.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() != 200) {
        return inconclusive(correlationId);
      }
      return classifyReportResponse(response.body(), correlationId);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return inconclusive(correlationId);
    } catch (IOException | RuntimeException exception) {
      return inconclusive(correlationId);
    }
  }

  static ProviderDispatchOutcome classifySendResponse(int statusCode, String body) {
    if (statusCode != 200) {
      ProviderAttemptClassification classification =
          statusCode == 429 || statusCode >= 500
              ? ProviderAttemptClassification.DEFINITIVE_TRANSIENT_FAILURE
              : ProviderAttemptClassification.DEFINITIVE_PERMANENT_FAILURE;
      return ProviderDispatchOutcome.live(classification, "IPPANEL_HTTP_" + statusCode, null);
    }
    try {
      JsonNode root = JSON.readTree(body);
      JsonNode meta = root.path("meta");
      JsonNode ids = root.path("data").path("message_outbox_ids");
      if (!meta.path("status").asBoolean(false) || !ids.isArray() || ids.size() != 1) {
        return ambiguous();
      }
      String correlation = ids.get(0).asText();
      if (!correlation.matches("[0-9]{1,32}")) {
        return ambiguous();
      }
      return ProviderDispatchOutcome.live(
          ProviderAttemptClassification.DEFINITIVE_ACCEPTED, "IPPANEL_ACCEPTED", correlation);
    } catch (RuntimeException exception) {
      return ambiguous();
    }
  }

  static ProviderReconciliationOutcome classifyReportResponse(String body, String correlationId) {
    try {
      JsonNode root = JSON.readTree(body);
      JsonNode data = root.path("data");
      if (!root.path("meta").path("status").asBoolean(false)
          || !data.isArray()
          || data.size() != 1) {
        return inconclusive(correlationId);
      }
      String rawStatus = data.get(0).path("message_status").asText();
      return switch (rawStatus) {
        case "2" ->
            ProviderReconciliationOutcome.live(
                ProviderReconciliationStatus.DELIVERED, "IPPANEL_STATUS_2", correlationId);
        case "3", "4" ->
            ProviderReconciliationOutcome.live(
                ProviderReconciliationStatus.PERMANENT_FAILURE,
                "IPPANEL_STATUS_" + rawStatus,
                correlationId);
        case "0", "1" ->
            ProviderReconciliationOutcome.live(
                ProviderReconciliationStatus.PENDING, "IPPANEL_STATUS_" + rawStatus, correlationId);
        default -> inconclusive(correlationId);
      };
    } catch (RuntimeException exception) {
      return inconclusive(correlationId);
    }
  }

  private URI endpoint(String suffix) {
    String value = baseUri.toString();
    return URI.create(
        (value.endsWith("/") ? value.substring(0, value.length() - 1) : value) + suffix);
  }

  private static ProviderDispatchOutcome ambiguous() {
    return ProviderDispatchOutcome.live(ProviderAttemptClassification.AMBIGUOUS, null, null);
  }

  private static ProviderReconciliationOutcome inconclusive(String correlationId) {
    return ProviderReconciliationOutcome.live(
        ProviderReconciliationStatus.INCONCLUSIVE, null, correlationId);
  }
}

package com.sajtech.notification.infrastructure.provider.smsir;

import com.sajtech.notification.application.delivery.model.ProviderDispatchMessage;
import com.sajtech.notification.application.delivery.model.ProviderDispatchOutcome;
import com.sajtech.notification.application.delivery.model.ProviderReconciliationOutcome;
import com.sajtech.notification.application.delivery.model.ProviderReconciliationRequest;
import com.sajtech.notification.application.delivery.model.ProviderReconciliationStatus;
import com.sajtech.notification.application.delivery.port.out.NotificationProviderGateway;
import com.sajtech.notification.domain.notification.model.NotificationChannel;
import com.sajtech.notification.domain.notification.model.ProviderAttemptClassification;
import com.sajtech.notification.infrastructure.provider.SmsIrSmsProviderConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLParameters;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class SmsIrSmsProviderAdapter implements NotificationProviderGateway {
  private static final Duration TOTAL_TIMEOUT = Duration.ofMillis(1500);
  private static final int MAXIMUM_RESPONSE_BYTES = 64 * 1024;
  private static final ObjectMapper JSON = new ObjectMapper();
  private final URI baseUri;
  private final String apiKey;
  private final long lineNumber;
  private final HttpClient client;

  public SmsIrSmsProviderAdapter(SmsIrSmsProviderConfiguration configuration) {
    this.baseUri = configuration.baseUri();
    this.apiKey = configuration.apiKey();
    this.lineNumber = configuration.lineNumber();
    SSLParameters tls = new SSLParameters();
    tls.setProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(500))
            .followRedirects(HttpClient.Redirect.NEVER)
            .sslParameters(tls)
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
      throw new IllegalArgumentException("SMS.ir accepts SMS dispatch only");
    }
    if (!message.recipient().matches("[+]989[0-9]{9}")) {
      return ProviderDispatchOutcome.live(
          ProviderAttemptClassification.DEFINITIVE_PERMANENT_FAILURE,
          "SMSIR_RECIPIENT_REJECTED",
          null);
    }
    try {
      String body =
          JSON.writeValueAsString(
              Map.of(
                  "lineNumber", lineNumber,
                  "messageText", message.text(),
                  "mobiles", List.of(toProviderMobile(message.recipient()))));
      HttpRequest request =
          HttpRequest.newBuilder(endpoint("/v1/send/bulk"))
              .timeout(TOTAL_TIMEOUT)
              .header("X-API-KEY", apiKey)
              .header("Accept", "application/json")
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
              .build();
      HttpResponse<InputStream> response =
          client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      return classifySendResponse(response.statusCode(), boundedBody(response));
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
      throw new IllegalArgumentException("SMS.ir reconciliation accepts SMS only");
    }
    String correlationId = request.providerCorrelationId();
    if (correlationId == null || !correlationId.matches("[1-9][0-9]{0,18}")) {
      return inconclusive(correlationId);
    }
    try {
      HttpRequest httpRequest =
          HttpRequest.newBuilder(endpoint("/v1/send/" + correlationId))
              .timeout(TOTAL_TIMEOUT)
              .header("X-API-KEY", apiKey)
              .header("Accept", "application/json")
              .GET()
              .build();
      HttpResponse<InputStream> response =
          client.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
      if (response.statusCode() != 200) {
        closeQuietly(response.body());
        return inconclusive(correlationId);
      }
      return classifyReportResponse(boundedBody(response), correlationId);
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
      return ProviderDispatchOutcome.live(classification, "SMSIR_HTTP_" + statusCode, null);
    }
    try {
      JsonNode root = JSON.readTree(body);
      JsonNode status = root.path("status");
      if (!status.isIntegralNumber() || !status.canConvertToInt()) {
        return ambiguous();
      }
      int providerStatus = status.intValue();
      if (providerStatus != 1) {
        ProviderAttemptClassification classification =
            providerStatus == 0 || providerStatus == 20
                ? ProviderAttemptClassification.DEFINITIVE_TRANSIENT_FAILURE
                : ProviderAttemptClassification.DEFINITIVE_PERMANENT_FAILURE;
        return ProviderDispatchOutcome.live(classification, "SMSIR_STATUS_" + providerStatus, null);
      }
      JsonNode ids = root.path("data").path("messageIds");
      if (!ids.isArray() || ids.size() != 1) {
        return ambiguous();
      }
      JsonNode id = ids.get(0);
      if (id == null || id.isNull() || (id.isIntegralNumber() && id.longValue() == 0)) {
        return ProviderDispatchOutcome.live(
            ProviderAttemptClassification.DEFINITIVE_PERMANENT_FAILURE,
            "SMSIR_MESSAGE_REJECTED",
            null);
      }
      if (!id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() <= 0) {
        return ambiguous();
      }
      return ProviderDispatchOutcome.live(
          ProviderAttemptClassification.DEFINITIVE_ACCEPTED,
          "SMSIR_ACCEPTED",
          Long.toString(id.longValue()));
    } catch (RuntimeException exception) {
      return ambiguous();
    }
  }

  static ProviderReconciliationOutcome classifyReportResponse(String body, String correlationId) {
    try {
      JsonNode root = JSON.readTree(body);
      JsonNode status = root.path("status");
      JsonNode data = root.path("data");
      JsonNode messageId = data.path("messageId");
      if (!status.isIntegralNumber()
          || status.intValue() != 1
          || !messageId.isIntegralNumber()
          || !messageId.canConvertToLong()
          || !Long.toString(messageId.longValue()).equals(correlationId)
          || !data.has("deliveryState")) {
        return inconclusive(correlationId);
      }
      JsonNode deliveryState = data.get("deliveryState");
      if (deliveryState == null || deliveryState.isNull()) {
        return ProviderReconciliationOutcome.live(
            ProviderReconciliationStatus.PENDING, "SMSIR_DELIVERY_PENDING", correlationId);
      }
      if (!deliveryState.isIntegralNumber() || !deliveryState.canConvertToInt()) {
        return inconclusive(correlationId);
      }
      int value = deliveryState.intValue();
      return switch (value) {
        case 1 ->
            ProviderReconciliationOutcome.live(
                ProviderReconciliationStatus.DELIVERED, "SMSIR_DELIVERY_1", correlationId);
        case 2, 4, 6, 7 ->
            ProviderReconciliationOutcome.live(
                ProviderReconciliationStatus.PERMANENT_FAILURE,
                "SMSIR_DELIVERY_" + value,
                correlationId);
        case 3, 5 ->
            ProviderReconciliationOutcome.live(
                ProviderReconciliationStatus.PENDING, "SMSIR_DELIVERY_" + value, correlationId);
        default -> inconclusive(correlationId);
      };
    } catch (RuntimeException exception) {
      return inconclusive(correlationId);
    }
  }

  private static String boundedBody(HttpResponse<InputStream> response) throws IOException {
    try (InputStream input = response.body()) {
      byte[] bytes = input.readNBytes(MAXIMUM_RESPONSE_BYTES + 1);
      if (bytes.length > MAXIMUM_RESPONSE_BYTES) {
        throw new IOException("SMS.ir response exceeded the configured bound");
      }
      return new String(bytes, StandardCharsets.UTF_8);
    }
  }

  private static void closeQuietly(InputStream input) {
    try (input) {
      // Closing an unused provider body releases its connection resources.
    } catch (IOException ignored) {
      // The provider outcome is already inconclusive; no payload or exception is logged.
    }
  }

  private URI endpoint(String suffix) {
    return URI.create(baseUri + suffix);
  }

  static String toProviderMobile(String canonicalRecipient) {
    if (canonicalRecipient == null || !canonicalRecipient.matches("[+]989[0-9]{9}")) {
      throw new IllegalArgumentException("SMS.ir recipient must be canonical Iran E.164");
    }
    return canonicalRecipient.substring(3);
  }

  private static ProviderDispatchOutcome ambiguous() {
    return ProviderDispatchOutcome.live(ProviderAttemptClassification.AMBIGUOUS, null, null);
  }

  private static ProviderReconciliationOutcome inconclusive(String correlationId) {
    return ProviderReconciliationOutcome.live(
        ProviderReconciliationStatus.INCONCLUSIVE, null, correlationId);
  }
}

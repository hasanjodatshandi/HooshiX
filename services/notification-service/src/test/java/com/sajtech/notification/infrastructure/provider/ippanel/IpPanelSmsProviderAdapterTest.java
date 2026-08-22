package com.sajtech.notification.infrastructure.provider.ippanel;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajtech.notification.application.delivery.model.ProviderReconciliationStatus;
import com.sajtech.notification.domain.notification.model.ProviderAttemptClassification;
import org.junit.jupiter.api.Test;

class IpPanelSmsProviderAdapterTest {
  @Test
  void acceptedWebserviceFixtureRequiresExactlyOneCorrelationIdentifier() {
    String accepted =
        "{\"data\":{\"message_outbox_ids\":[1123544244]},\"meta\":{\"status\":true,\"message_code\":\"200-1\"}}";
    var result = IpPanelSmsProviderAdapter.classifySendResponse(200, accepted);
    assertThat(result.classification())
        .isEqualTo(ProviderAttemptClassification.DEFINITIVE_ACCEPTED);
    assertThat(result.providerCorrelationId()).isEqualTo("1123544244");

    String malformed = "{\"data\":{\"message_outbox_ids\":[]},\"meta\":{\"status\":true}}";
    assertThat(IpPanelSmsProviderAdapter.classifySendResponse(200, malformed).classification())
        .isEqualTo(ProviderAttemptClassification.AMBIGUOUS);
  }

  @Test
  void explicitHttpRejectionsDoNotBecomeAmbiguousSubmission() {
    assertThat(IpPanelSmsProviderAdapter.classifySendResponse(422, "{}").classification())
        .isEqualTo(ProviderAttemptClassification.DEFINITIVE_PERMANENT_FAILURE);
    assertThat(IpPanelSmsProviderAdapter.classifySendResponse(503, "{}").classification())
        .isEqualTo(ProviderAttemptClassification.DEFINITIVE_TRANSIENT_FAILURE);
  }

  @Test
  void recipientReportFixtureUsesOnlyRecipientLevelDeliveryStatus() {
    String delivered =
        "{\"data\":[{\"recipient\":\"+989120000000\",\"message_status\":\"2\"}],\"meta\":{\"status\":true}}";
    assertThat(IpPanelSmsProviderAdapter.classifyReportResponse(delivered, "1123544244").status())
        .isEqualTo(ProviderReconciliationStatus.DELIVERED);

    String failed = "{\"data\":[{\"message_status\":\"4\"}],\"meta\":{\"status\":true}}";
    assertThat(IpPanelSmsProviderAdapter.classifyReportResponse(failed, "1123544244").status())
        .isEqualTo(ProviderReconciliationStatus.PERMANENT_FAILURE);

    String pending = "{\"data\":[{\"message_status\":\"1\"}],\"meta\":{\"status\":true}}";
    assertThat(IpPanelSmsProviderAdapter.classifyReportResponse(pending, "1123544244").status())
        .isEqualTo(ProviderReconciliationStatus.PENDING);
  }
}

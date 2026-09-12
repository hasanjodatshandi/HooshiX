package com.sajtech.notification.infrastructure.provider.smsir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sajtech.notification.application.delivery.model.ProviderReconciliationStatus;
import com.sajtech.notification.domain.notification.model.ProviderAttemptClassification;
import com.sajtech.notification.infrastructure.provider.SmsIrSmsProviderConfiguration;
import java.net.URI;
import org.junit.jupiter.api.Test;

class SmsIrSmsProviderAdapterTest {
  @Test
  void canonicalIranRecipientUsesSmsIrLocalWireFormat() {
    assertThat(SmsIrSmsProviderAdapter.toProviderMobile("+989120000000")).isEqualTo("9120000000");
    assertThatThrownBy(() -> SmsIrSmsProviderAdapter.toProviderMobile("09120000000"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void configurationPinsOfficialApiBaseUriAndValidatesLineNumber() {
    var configuration = SmsIrSmsProviderConfiguration.production("fixture-token", "30004505000017");
    assertThat(configuration.baseUri().toString()).isEqualTo("https://api.sms.ir");
    assertThat(configuration.lineNumber()).isEqualTo(30004505000017L);

    assertThatThrownBy(
            () ->
                new SmsIrSmsProviderConfiguration(
                    URI.create("https://attacker.invalid"), "fixture-token", 30004505000017L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> SmsIrSmsProviderConfiguration.production("fixture-token", "+983000505"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptedBulkFixtureRequiresExactlyOnePositiveMessageIdentifier() {
    String accepted =
        "{\"status\":1,\"message\":\"ok\",\"data\":{\"packId\":\"2b99e63c-9bf8-4a21-9bfe-3f72dc1b46f1\",\"messageIds\":[86522023],\"cost\":1.0}}";
    var result = SmsIrSmsProviderAdapter.classifySendResponse(200, accepted);
    assertThat(result.classification())
        .isEqualTo(ProviderAttemptClassification.DEFINITIVE_ACCEPTED);
    assertThat(result.providerCorrelationId()).isEqualTo("86522023");

    String malformed = "{\"status\":1,\"data\":{\"messageIds\":[]}}";
    assertThat(SmsIrSmsProviderAdapter.classifySendResponse(200, malformed).classification())
        .isEqualTo(ProviderAttemptClassification.AMBIGUOUS);
  }

  @Test
  void providerAndHttpFailuresRetainRetryOwnershipAndCertainty() {
    assertThat(
            SmsIrSmsProviderAdapter.classifySendResponse(
                    200, "{\"status\":1,\"data\":{\"messageIds\":[0]}}")
                .classification())
        .isEqualTo(ProviderAttemptClassification.DEFINITIVE_PERMANENT_FAILURE);
    assertThat(SmsIrSmsProviderAdapter.classifySendResponse(200, "{\"status\":0}").classification())
        .isEqualTo(ProviderAttemptClassification.DEFINITIVE_TRANSIENT_FAILURE);
    assertThat(
            SmsIrSmsProviderAdapter.classifySendResponse(200, "{\"status\":101}").classification())
        .isEqualTo(ProviderAttemptClassification.DEFINITIVE_PERMANENT_FAILURE);
    assertThat(SmsIrSmsProviderAdapter.classifySendResponse(429, "{}").classification())
        .isEqualTo(ProviderAttemptClassification.DEFINITIVE_TRANSIENT_FAILURE);
    assertThat(SmsIrSmsProviderAdapter.classifySendResponse(503, "{}").classification())
        .isEqualTo(ProviderAttemptClassification.DEFINITIVE_TRANSIENT_FAILURE);
    assertThat(SmsIrSmsProviderAdapter.classifySendResponse(401, "{}").classification())
        .isEqualTo(ProviderAttemptClassification.DEFINITIVE_PERMANENT_FAILURE);
  }

  @Test
  void messageReportUsesOnlyCorrelatedRecipientDeliveryState() {
    String delivered = "{\"status\":1,\"data\":{\"messageId\":89545112,\"deliveryState\":1}}";
    assertThat(SmsIrSmsProviderAdapter.classifyReportResponse(delivered, "89545112").status())
        .isEqualTo(ProviderReconciliationStatus.DELIVERED);

    String failed = "{\"status\":1,\"data\":{\"messageId\":89545112,\"deliveryState\":7}}";
    assertThat(SmsIrSmsProviderAdapter.classifyReportResponse(failed, "89545112").status())
        .isEqualTo(ProviderReconciliationStatus.PERMANENT_FAILURE);

    String pending = "{\"status\":1,\"data\":{\"messageId\":89545112,\"deliveryState\":3}}";
    assertThat(SmsIrSmsProviderAdapter.classifyReportResponse(pending, "89545112").status())
        .isEqualTo(ProviderReconciliationStatus.PENDING);

    assertThat(SmsIrSmsProviderAdapter.classifyReportResponse(delivered, "89545113").status())
        .isEqualTo(ProviderReconciliationStatus.INCONCLUSIVE);
  }
}

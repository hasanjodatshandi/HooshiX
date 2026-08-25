package com.sajtech.identity.interfaces.externalidentity.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajtech.identity.contract.v1.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class IdentityExternalIdentityContractTest {
  @Test
  void serviceExposesOnlyTheReviewedVersionOneOperations() {
    List<String> methods =
        IdentityExternalIdentityServiceGrpc.getServiceDescriptor().getMethods().stream()
            .map(method -> method.getBareMethodName())
            .toList();

    assertThat(methods).containsExactly("EstablishSession", "Link", "Unlink", "GetStatus");
  }

  @Test
  void evidenceHasStableBindingFieldsAndNoProviderCredentialSurface() {
    assertThat(ExternalIdentityEvidence.getDescriptor().findFieldByName("evidence_id").getNumber())
        .isEqualTo(1);
    assertThat(
            ExternalIdentityEvidence.getDescriptor()
                .findFieldByName("evidence_issued_at")
                .getNumber())
        .isEqualTo(2);
    assertThat(ExternalIdentityEvidence.getDescriptor().findFieldByName("issuer").getNumber())
        .isEqualTo(3);
    assertThat(ExternalIdentityEvidence.getDescriptor().findFieldByName("subject").getNumber())
        .isEqualTo(4);
    assertThat(
            ExternalIdentityEvidence.getDescriptor()
                .findFieldByName("metadata_version")
                .getNumber())
        .isEqualTo(5);
    assertThat(ExternalIdentityEvidence.getDescriptor().findFieldByName("id_token")).isNull();
    assertThat(ExternalIdentityEvidence.getDescriptor().findFieldByName("access_token")).isNull();
    assertThat(ExternalIdentityEvidence.getDescriptor().findFieldByName("refresh_token")).isNull();
    assertThat(
            EstablishSessionRequest.getDescriptor().findFieldByName("client_address").getNumber())
        .isEqualTo(3);
  }
}

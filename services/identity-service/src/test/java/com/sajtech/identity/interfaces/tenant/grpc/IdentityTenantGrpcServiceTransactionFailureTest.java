package com.sajtech.identity.interfaces.tenant.grpc;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sajtech.identity.application.tenant.port.in.TenantLifecycle;
import com.sajtech.identity.application.transaction.model.TransactionFailure;
import com.sajtech.identity.application.transaction.model.TransactionUnavailableException;
import com.sajtech.identity.contract.v1.CreateTenantRequest;
import com.sajtech.identity.contract.v1.CreateTenantResponse;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdentityTenantGrpcServiceTransactionFailureTest {
  @Test
  void leavesDatabaseCapacityFailureForSafeServerInterceptorMapping() {
    TenantLifecycle lifecycle = mock(TenantLifecycle.class);
    TransactionUnavailableException failure =
        new TransactionUnavailableException(
            TransactionFailure.LOCK_TIMEOUT, new IllegalStateException("database detail"));
    when(lifecycle.createTenant(any(), anyString(), anyString(), anyString())).thenThrow(failure);
    @SuppressWarnings("unchecked")
    StreamObserver<CreateTenantResponse> observer = mock(StreamObserver.class);
    CreateTenantRequest request =
        CreateTenantRequest.newBuilder()
            .setRequestId(UUID.randomUUID().toString())
            .setRefreshCredential("credential")
            .setName("Tenant")
            .setSlug("tenant")
            .build();

    assertThatThrownBy(
            () -> new IdentityTenantGrpcService(lifecycle).createTenant(request, observer))
        .isSameAs(failure);
    verifyNoInteractions(observer);
  }
}

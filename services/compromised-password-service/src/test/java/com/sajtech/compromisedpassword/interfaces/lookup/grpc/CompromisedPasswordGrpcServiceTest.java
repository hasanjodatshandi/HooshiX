package com.sajtech.compromisedpassword.interfaces.lookup.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajtech.compromisedpassword.contract.v1.LookupPrefixRequest;
import com.sajtech.compromisedpassword.contract.v1.LookupPrefixResponse;
import com.sajtech.compromisedpassword.domain.lookup.valueobject.CompromisedHashMatch;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CompromisedPasswordGrpcServiceTest {
  private static final long SAFE_RESPONSE_BOUND = 4096;

  @Test
  void mapsDomainMatchesWithoutReturningThePrefix() {
    CompromisedPasswordGrpcService service =
        new CompromisedPasswordGrpcService(
            prefix -> List.of(new CompromisedHashMatch("A".repeat(35), 3)), SAFE_RESPONSE_BOUND);
    CapturingObserver observer = new CapturingObserver();

    service.lookupPrefix(LookupPrefixRequest.newBuilder().setPrefix("ABCDE").build(), observer);

    assertThat(observer.error.get()).isNull();
    assertThat(observer.response.get().getMatchesList()).hasSize(1);
    assertThat(observer.response.get().getMatches(0).getSuffix()).isEqualTo("A".repeat(35));
    assertThat(observer.response.get().toString()).doesNotContain("ABCDE");
  }

  @Test
  void rejectsNonCanonicalPrefixWithoutEchoingIt() {
    CompromisedPasswordGrpcService service =
        new CompromisedPasswordGrpcService(prefix -> List.of(), SAFE_RESPONSE_BOUND);
    CapturingObserver observer = new CapturingObserver();

    service.lookupPrefix(LookupPrefixRequest.newBuilder().setPrefix("abcde").build(), observer);

    Status status = Status.fromThrowable(observer.error.get());
    assertThat(status.getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(status.getDescription()).isEqualTo("invalid prefix");
  }

  @Test
  void failsClosedWhenSerializedResponseExceedsApprovedBound() {
    CompromisedPasswordGrpcService service =
        new CompromisedPasswordGrpcService(
            prefix -> List.of(new CompromisedHashMatch("A".repeat(35), 3)), 1);
    CapturingObserver observer = new CapturingObserver();

    service.lookupPrefix(LookupPrefixRequest.newBuilder().setPrefix("ABCDE").build(), observer);

    Status status = Status.fromThrowable(observer.error.get());
    assertThat(status.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(status.getDescription()).isEqualTo("dataset unavailable");
    assertThat(observer.response.get()).isNull();
  }

  private static final class CapturingObserver implements StreamObserver<LookupPrefixResponse> {
    private final AtomicReference<LookupPrefixResponse> response = new AtomicReference<>();
    private final AtomicReference<Throwable> error = new AtomicReference<>();

    @Override
    public void onNext(LookupPrefixResponse value) {
      response.set(value);
    }

    @Override
    public void onError(Throwable throwable) {
      error.set(throwable);
    }

    @Override
    public void onCompleted() {}
  }
}

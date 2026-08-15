package com.sajtech.compromisedpassword.interfaces.lookup.grpc;

import com.sajtech.compromisedpassword.application.lookup.port.in.LookupCompromisedPasswords;
import com.sajtech.compromisedpassword.contract.v1.CompromisedPasswordServiceGrpc;
import com.sajtech.compromisedpassword.contract.v1.LookupPrefixRequest;
import com.sajtech.compromisedpassword.contract.v1.LookupPrefixResponse;
import com.sajtech.compromisedpassword.domain.lookup.valueobject.Sha1Prefix;
import com.sajtech.compromisedpassword.infrastructure.lookup.dataset.DatasetUnavailableException;
import com.sajtech.compromisedpassword.infrastructure.lookup.runtime.LookupCapacityExceededException;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Objects;

public final class CompromisedPasswordGrpcService
    extends CompromisedPasswordServiceGrpc.CompromisedPasswordServiceImplBase {
  private final LookupCompromisedPasswords lookup;

  public CompromisedPasswordGrpcService(LookupCompromisedPasswords lookup) {
    this.lookup = Objects.requireNonNull(lookup, "lookup");
  }

  @Override
  public void lookupPrefix(
      LookupPrefixRequest request, StreamObserver<LookupPrefixResponse> responseObserver) {
    final Sha1Prefix prefix;
    try {
      prefix = Sha1Prefix.parse(request.getPrefix());
    } catch (IllegalArgumentException exception) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT.withDescription("invalid prefix").asRuntimeException());
      return;
    }

    try {
      LookupPrefixResponse.Builder response = LookupPrefixResponse.newBuilder();
      lookup
          .lookup(prefix)
          .forEach(
              match ->
                  response.addMatches(
                      com.sajtech.compromisedpassword.contract.v1.CompromisedHashMatch.newBuilder()
                          .setSuffix(match.suffix())
                          .setOccurrenceCount(match.occurrenceCount())
                          .build()));
      responseObserver.onNext(response.build());
      responseObserver.onCompleted();
    } catch (LookupCapacityExceededException exception) {
      responseObserver.onError(
          Status.RESOURCE_EXHAUSTED.withDescription("lookup overloaded").asRuntimeException());
    } catch (DatasetUnavailableException exception) {
      responseObserver.onError(
          Status.UNAVAILABLE.withDescription("dataset unavailable").asRuntimeException());
    } catch (RuntimeException exception) {
      responseObserver.onError(
          Status.INTERNAL.withDescription("lookup failed").asRuntimeException());
    }
  }
}

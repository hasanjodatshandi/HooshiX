package com.sajtech.compromisedpassword.interfaces.lookup.grpc;

import com.sajtech.compromisedpassword.application.lookup.LookupOverloadedException;
import com.sajtech.compromisedpassword.application.lookup.LookupUnavailableException;
import com.sajtech.compromisedpassword.application.lookup.port.in.LookupCompromisedPasswords;
import com.sajtech.compromisedpassword.contract.v1.CompromisedPasswordServiceGrpc;
import com.sajtech.compromisedpassword.contract.v1.LookupPrefixRequest;
import com.sajtech.compromisedpassword.contract.v1.LookupPrefixResponse;
import com.sajtech.compromisedpassword.domain.lookup.valueobject.Sha1Prefix;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Objects;

public final class CompromisedPasswordGrpcService
    extends CompromisedPasswordServiceGrpc.CompromisedPasswordServiceImplBase {
  private final LookupCompromisedPasswords lookup;
  private final long maxSerializedResponseBytes;

  public CompromisedPasswordGrpcService(
      LookupCompromisedPasswords lookup, long maxSerializedResponseBytes) {
    this.lookup = Objects.requireNonNull(lookup, "lookup");
    if (maxSerializedResponseBytes <= 0) {
      throw new IllegalArgumentException("Maximum serialized response bytes must be positive");
    }
    this.maxSerializedResponseBytes = maxSerializedResponseBytes;
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
      LookupPrefixResponse built = response.build();
      if (built.getSerializedSize() > maxSerializedResponseBytes) {
        throw new LookupUnavailableException("Lookup response exceeds approved compatibility bound");
      }
      responseObserver.onNext(built);
      responseObserver.onCompleted();
    } catch (LookupOverloadedException exception) {
      responseObserver.onError(
          Status.RESOURCE_EXHAUSTED.withDescription("lookup overloaded").asRuntimeException());
    } catch (LookupUnavailableException exception) {
      responseObserver.onError(
          Status.UNAVAILABLE.withDescription("dataset unavailable").asRuntimeException());
    } catch (RuntimeException exception) {
      responseObserver.onError(
          Status.INTERNAL.withDescription("lookup failed").asRuntimeException());
    }
  }
}

package com.sajtech.webbff.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajtech.webbff.application.BffError;
import com.sajtech.webbff.application.BffException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class IdentityGrpcClientSupportTest {
  @ParameterizedTest
  @MethodSource("exhaustionMappings")
  void onlyReviewedQuotaExhaustionBecomesRateLimited(
      String description,
      BffError expected,
      Function<StatusRuntimeException, BffException> mapper) {
    BffException failure =
        mapper.apply(Status.RESOURCE_EXHAUSTED.withDescription(description).asRuntimeException());
    assertThat(failure.error()).isEqualTo(expected);
    assertThat(failure.getMessage()).doesNotContain("private upstream detail");
  }

  @Test
  void existingContactCountBusinessLimitMappingIsPreserved() {
    assertThat(
            IdentityGrpcClientSupport.map(
                    Status.RESOURCE_EXHAUSTED
                        .withDescription("CONTACT_LIMIT_REACHED")
                        .asRuntimeException())
                .error())
        .isEqualTo(BffError.RATE_LIMITED);
  }

  private static Stream<Arguments> exhaustionMappings() {
    return Stream.<Function<StatusRuntimeException, BffException>>of(
            IdentityGrpcClientSupport::mapRegistration,
            IdentityGrpcClientSupport::mapPassword,
            IdentityGrpcClientSupport::mapMfa,
            IdentityGrpcClientSupport::map)
        .flatMap(
            mapper ->
                Stream.of(
                    Arguments.of("QUOTA_EXCEEDED", BffError.RATE_LIMITED, mapper),
                    Arguments.of(
                        "IDENTITY_DATABASE_POOL_UNAVAILABLE",
                        BffError.DEPENDENCY_UNAVAILABLE,
                        mapper),
                    Arguments.of(
                        "AUTHENTICATION_OVERLOADED", BffError.DEPENDENCY_UNAVAILABLE, mapper),
                    Arguments.of("IDENTITY_UNAVAILABLE", BffError.DEPENDENCY_UNAVAILABLE, mapper),
                    Arguments.of(
                        "private upstream detail", BffError.DEPENDENCY_UNAVAILABLE, mapper),
                    Arguments.of(null, BffError.DEPENDENCY_UNAVAILABLE, mapper)));
  }
}

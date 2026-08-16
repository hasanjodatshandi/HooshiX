package com.sajtech.compromisedpassword.interfaces.observability.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sajtech.compromisedpassword.contract.v1.CompromisedPasswordServiceGrpc;
import com.sajtech.compromisedpassword.contract.v1.LookupPrefixRequest;
import com.sajtech.compromisedpassword.domain.lookup.valueobject.CompromisedHashMatch;
import com.sajtech.compromisedpassword.interfaces.lookup.grpc.CompromisedPasswordGrpcService;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SafeTracingServerInterceptorTest {
  private static final String PREFIX = "ABCDE";
  private static final String SUFFIX = "A".repeat(35);

  @Test
  void exportsOnlyAllowListedStatusWithoutRequestOrResponseHashMaterial() throws Exception {
    CapturingSpanExporter exporter = new CapturingSpanExporter();
    try (TraceHarness harness = startHarness(exporter)) {
      CompromisedPasswordServiceGrpc.newBlockingStub(harness.channel())
          .lookupPrefix(LookupPrefixRequest.newBuilder().setPrefix(PREFIX).build());
    }

    assertThat(exporter.spans).hasSize(1);
    SpanData span = exporter.spans.getFirst();
    assertThat(span.getName()).isEqualTo("compromised-password.lookup");
    assertThat(span.getAttributes().asMap()).hasSize(1);
    assertThat(span.getAttributes().asMap().keySet())
        .extracting(key -> key.getKey())
        .containsExactly("rpc.grpc.status_code");
    assertThat(span.getAttributes().asMap().toString())
        .doesNotContain(PREFIX)
        .doesNotContain(SUFFIX);
  }

  @Test
  void crlfBearingInvalidInputIsNotCopiedIntoErrorSpanAttributes() throws Exception {
    String invalid = "AB\r\nCD";
    CapturingSpanExporter exporter = new CapturingSpanExporter();
    try (TraceHarness harness = startHarness(exporter)) {
      var stub = CompromisedPasswordServiceGrpc.newBlockingStub(harness.channel());
      assertThatThrownBy(
              () -> stub.lookupPrefix(LookupPrefixRequest.newBuilder().setPrefix(invalid).build()))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              throwable ->
                  assertThat(Status.fromThrowable(throwable).getCode())
                      .isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    assertThat(exporter.spans).hasSize(1);
    assertThat(exporter.spans.getFirst().getAttributes().asMap().toString())
        .doesNotContain("AB")
        .doesNotContain("CD")
        .doesNotContain("\r")
        .doesNotContain("\n");
  }

  private static TraceHarness startHarness(CapturingSpanExporter exporter) throws Exception {
    SdkTracerProvider tracerProvider =
        SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
    OpenTelemetrySdk openTelemetry =
        OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
    CompromisedPasswordGrpcService service =
        new CompromisedPasswordGrpcService(
            prefix -> List.of(new CompromisedHashMatch(SUFFIX, 3)), 4096);
    String serverName = InProcessServerBuilder.generateName();
    Server server =
        InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(
                ServerInterceptors.intercept(
                    service, new SafeTracingServerInterceptor(openTelemetry)))
            .build()
            .start();
    ManagedChannel channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
    return new TraceHarness(server, channel, tracerProvider);
  }

  private static final class CapturingSpanExporter implements SpanExporter {
    private final List<SpanData> spans = new ArrayList<>();

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
      this.spans.addAll(spans);
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
      return CompletableResultCode.ofSuccess();
    }
  }

  private record TraceHarness(
      Server server, ManagedChannel channel, SdkTracerProvider tracerProvider)
      implements AutoCloseable {
    @Override
    public void close() throws InterruptedException {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      tracerProvider.shutdown().join(5, TimeUnit.SECONDS);
    }
  }
}

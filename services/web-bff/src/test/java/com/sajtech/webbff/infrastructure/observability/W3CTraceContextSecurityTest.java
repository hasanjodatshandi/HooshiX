package com.sajtech.webbff.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class W3CTraceContextSecurityTest {
  private static final String TRACE_ID = "12345678901234567890123456789012";
  private static final String PARENT = "00-" + TRACE_ID + "-1234567890123456-01";
  private static final TextMapGetter<Map<String, String>> GETTER =
      new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
          return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
          return carrier.get(key);
        }
      };

  @Test
  void longWhitespaceWithoutCommaHasBoundedParsingCost() {
    assertTimeoutPreemptively(
        Duration.ofSeconds(3),
        () -> {
          for (String whitespace : new String[] {" ", "\t", " \t"}) {
            SpanContext parsed = extract(PARENT, "safe=value" + whitespace.repeat(200_000));
            assertThat(parsed.isRemote()).isTrue();
            assertThat(parsed.getTraceId()).isEqualTo(TRACE_ID);
            assertThat(parsed.getTraceState().get("safe")).isEqualTo("value");
          }
        });
  }

  @Test
  void validCorrelationRoundTripsAndInvalidParentCannotCreateAValidContext() {
    SpanContext parsed = extract(PARENT, "safe=value, vendor=next");
    Map<String, String> output = new HashMap<>();
    W3CTraceContextPropagator.getInstance()
        .inject(Context.root().with(Span.wrap(parsed)), output, Map::put);
    assertThat(output).containsEntry("traceparent", PARENT);
    assertThat(output).containsEntry("tracestate", "safe=value,vendor=next");
    assertThat(extract("invalid", "safe=value").isValid()).isFalse();
    assertThat(extract(PARENT, "missing-equals").getTraceState().isEmpty()).isTrue();
  }

  private static SpanContext extract(String parent, String state) {
    Context extracted =
        W3CTraceContextPropagator.getInstance()
            .extract(Context.root(), Map.of("traceparent", parent, "tracestate", state), GETTER);
    return Span.fromContext(extracted).getSpanContext();
  }
}

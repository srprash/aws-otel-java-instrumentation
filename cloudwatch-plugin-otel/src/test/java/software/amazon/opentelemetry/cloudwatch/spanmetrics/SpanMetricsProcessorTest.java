/*
 * Copyright Amazon.com, Inc. or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.opentelemetry.cloudwatch.spanmetrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.trace.TestSpanData;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import java.util.Collection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SpanMetricsProcessorTest {

  private static InMemoryMetricReader reader;
  private static SpanMetricsProcessor processor;

  @BeforeAll
  static void setUp() {
    // The holder is a first-wins singleton, so all tests share one bound SDK.
    reader = InMemoryMetricReader.create();
    SdkMeterProvider meterProvider =
        SdkMeterProvider.builder().registerMetricReader(reader).build();
    OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setMeterProvider(meterProvider).build();
    SpanMetrics.bind(sdk);
    processor = new SpanMetricsProcessor();
  }

  private static ReadableSpan readableSpan(SpanKind kind, StatusData status, Attributes attrs) {
    return readableSpan(kind, status, attrs, 0, 5_000_000); // 5 ms
  }

  private static ReadableSpan readableSpan(
      SpanKind kind, StatusData status, Attributes attrs, long startNanos, long endNanos) {
    SpanData data =
        TestSpanData.builder()
            .setName("op")
            .setKind(kind)
            .setStatus(status)
            .setResource(
                Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), "svc")))
            .setAttributes(attrs)
            .setSpanContext(
                SpanContext.create(
                    "00000000000000000000000000000001",
                    "0000000000000001",
                    TraceFlags.getDefault(),
                    TraceState.getDefault()))
            .setStartEpochNanos(startNanos)
            .setEndEpochNanos(endNanos)
            .setHasEnded(true)
            .build();
    ReadableSpan span = Mockito.mock(ReadableSpan.class);
    Mockito.when(span.toSpanData()).thenReturn(data);
    return span;
  }

  @Test
  void emitsSingleDurationHistogramWithSemconvShape() {
    processor.onEnd(readableSpan(SpanKind.SERVER, StatusData.unset(), Attributes.empty()));

    Collection<MetricData> metrics = reader.collectAllMetrics();
    // One histogram only: the connector-era calls counter is gone.
    assertThat(metrics).noneMatch(m -> m.getName().startsWith("traces.span.metrics."));
    MetricData duration = durationMetric(metrics);

    // Duration is in seconds; a 5ms span records 0.005s, with the semconv bucket guidance.
    assertThat(duration.getUnit()).isEqualTo("s");
    assertThat(duration.getDescription())
        .isEqualTo("Duration of ended spans, derived from span start and end timestamps.");
    assertThat(duration.getHistogramData().getPoints())
        .anySatisfy(
            p -> {
              assertThat(p.getCount()).isEqualTo(1);
              assertThat(p.getSum()).isEqualTo(0.005);
              assertThat(p.getBoundaries())
                  .containsExactly(
                      0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0,
                      30.0, 60.0);
              assertThat(p.getAttributes().get(AttributeKey.stringKey("otel.span.kind")))
                  .isEqualTo("SERVER");
              // UNSET status: no otel.status_code, no error.type, and span name is Opt-In.
              assertThat(p.getAttributes().get(AttributeKey.stringKey("otel.status_code")))
                  .isNull();
              assertThat(p.getAttributes().get(AttributeKey.stringKey("error.type"))).isNull();
              assertThat(p.getAttributes().get(AttributeKey.stringKey("otel.span.name"))).isNull();
              // Schema + lib-version markers on the metric (spec §6).
              assertThat(
                      p.getAttributes().get(AttributeKey.stringKey("aws.otel.span.metrics.schema")))
                  .isEqualTo("v1");
              assertThat(
                      p.getAttributes()
                          .get(AttributeKey.stringKey("aws.otel.extension.lib.version")))
                  .isEqualTo(SpanMetricsProcessor.LIB_VERSION);
            });
  }

  @Test
  void spanNameRecordedWhenOptedIn() {
    new SpanMetricsProcessor(true)
        .onEnd(
            readableSpan(
                SpanKind.INTERNAL,
                StatusData.unset(),
                Attributes.builder().put("http.route", "/opt-in").build()));

    assertThat(durationMetric(reader.collectAllMetrics()).getHistogramData().getPoints())
        .filteredOn(
            p -> "/opt-in".equals(p.getAttributes().get(AttributeKey.stringKey("http.route"))))
        .singleElement()
        .satisfies(
            p ->
                assertThat(p.getAttributes().get(AttributeKey.stringKey("otel.span.name")))
                    .isEqualTo("op"));
  }

  @Test
  void endBeforeStartRecordsZeroAndIsCounted() {
    processor.onEnd(
        readableSpan(
            SpanKind.INTERNAL,
            StatusData.unset(),
            Attributes.builder().put("http.route", "/clock-skew").build(),
            10_000_000,
            5_000_000));

    assertThat(durationMetric(reader.collectAllMetrics()).getHistogramData().getPoints())
        .filteredOn(
            p -> "/clock-skew".equals(p.getAttributes().get(AttributeKey.stringKey("http.route"))))
        .singleElement()
        .satisfies(
            p -> {
              assertThat(p.getCount()).isEqualTo(1);
              assertThat(p.getSum()).isEqualTo(0.0);
            });
  }

  @Test
  void clientPortNotADimensionOnServerSpan() {
    // Legacy HTTP server instrumentation puts the client's ephemeral port in net.peer.port;
    // requests from different client ports must still aggregate into one series.
    for (long clientPort : new long[] {50001L, 50002L, 50003L}) {
      processor.onEnd(
          readableSpan(
              SpanKind.SERVER,
              StatusData.unset(),
              Attributes.builder()
                  .put("http.route", "/items/{id}")
                  .put("net.peer.name", "client.example.com")
                  .put("net.peer.port", clientPort)
                  .put("net.host.name", "payments.example.com")
                  .put("net.host.port", 8080L)
                  .build()));
    }

    assertThat(durationMetric(reader.collectAllMetrics()).getHistogramData().getPoints())
        .filteredOn(
            p -> "/items/{id}".equals(p.getAttributes().get(AttributeKey.stringKey("http.route"))))
        .singleElement()
        .satisfies(
            p -> {
              assertThat(p.getCount()).isEqualTo(3);
              assertThat(p.getAttributes().get(AttributeKey.longKey("net.peer.port"))).isNull();
              assertThat(p.getAttributes().get(AttributeKey.stringKey("net.peer.name"))).isNull();
              assertThat(p.getAttributes().get(AttributeKey.longKey("net.host.port")))
                  .isEqualTo(8080L);
            });
  }

  private static MetricData durationMetric(Collection<MetricData> metrics) {
    return metrics.stream()
        .filter(m -> m.getName().equals("otel.span.duration"))
        .findFirst()
        .orElseThrow(() -> new AssertionError("otel.span.duration metric missing"));
  }

  @Test
  void onEndSwallowsExceptions() {
    ReadableSpan bad = Mockito.mock(ReadableSpan.class);
    Mockito.when(bad.toSpanData()).thenThrow(new RuntimeException("boom"));
    // Must not propagate — a bad span cannot break the host pipeline.
    processor.onEnd(bad);
  }

  @Test
  void lifecycleFlags() {
    assertThat(processor.isEndRequired()).isTrue();
    // onStart is required now: the processor stamps dedup/schema attributes on the span.
    assertThat(processor.isStartRequired()).isTrue();
  }

  @Test
  void onStartStampsSchemaAndLibVersionOnSpan() {
    io.opentelemetry.sdk.trace.ReadWriteSpan span =
        Mockito.mock(io.opentelemetry.sdk.trace.ReadWriteSpan.class);
    processor.onStart(io.opentelemetry.context.Context.root(), span);
    Mockito.verify(span).setAttribute(AttributeKey.stringKey("aws.otel.span.metrics.schema"), "v1");
    Mockito.verify(span)
        .setAttribute(
            AttributeKey.stringKey("aws.otel.extension.lib.version"),
            SpanMetricsProcessor.LIB_VERSION);
  }

  @Test
  void onStartSwallowsExceptions() {
    io.opentelemetry.sdk.trace.ReadWriteSpan span =
        Mockito.mock(io.opentelemetry.sdk.trace.ReadWriteSpan.class);
    Mockito.when(span.setAttribute(Mockito.any(AttributeKey.class), Mockito.any()))
        .thenThrow(new RuntimeException("boom"));
    // Must not propagate — a failure stamping attributes cannot break the host pipeline.
    processor.onStart(io.opentelemetry.context.Context.root(), span);
  }
}

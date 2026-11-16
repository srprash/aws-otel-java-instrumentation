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

package software.amazon.opentelemetry.cloudwatch.spanmetrics.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** PROTOTYPE: the Opt-In otel.span.name property, end to end through SDK autoconfigure. */
class SpanNameOptInAutoconfigureTest {

  private static final AttributeKey<String> SPAN_NAME = AttributeKey.stringKey("otel.span.name");

  @BeforeEach
  @AfterEach
  void clear() {
    OpenTelemetryHolder.reset();
  }

  @Test
  void spanNameOmittedByDefault() {
    assertThat(recordOneSpan(null).getAttributes().get(SPAN_NAME)).isNull();
  }

  @Test
  void spanNameRecordedWhenPropertyEnabled() {
    assertThat(recordOneSpan("true").getAttributes().get(SPAN_NAME)).isEqualTo("GET /orders/{id}");
  }

  private static HistogramPointData recordOneSpan(String spanNameEnabled) {
    Map<String, String> props = new HashMap<>();
    props.put("otel.traces.exporter", "none");
    props.put("otel.metrics.exporter", "none");
    props.put("otel.logs.exporter", "none");
    if (spanNameEnabled != null) {
      props.put(SpanMetricsCustomizerProvider.SPAN_NAME_ENABLED_PROPERTY, spanNameEnabled);
    }
    InMemoryMetricReader reader = InMemoryMetricReader.create();
    OpenTelemetrySdk sdk =
        AutoConfiguredOpenTelemetrySdk.builder()
            .addPropertiesSupplier(() -> props)
            .addMeterProviderCustomizer((builder, config) -> builder.registerMetricReader(reader))
            .build()
            .getOpenTelemetrySdk();
    try {
      sdk.getTracer("test").spanBuilder("GET /orders/{id}").startSpan().end();
      MetricData duration =
          reader.collectAllMetrics().stream()
              .filter(m -> m.getName().equals("otel.span.duration"))
              .findFirst()
              .orElseThrow(() -> new AssertionError("otel.span.duration metric missing"));
      return duration.getHistogramData().getPoints().iterator().next();
    } finally {
      sdk.close();
    }
  }
}

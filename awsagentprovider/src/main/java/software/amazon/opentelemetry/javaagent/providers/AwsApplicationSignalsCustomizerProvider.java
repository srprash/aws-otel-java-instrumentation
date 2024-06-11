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

package software.amazon.opentelemetry.javaagent.providers;

import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.contrib.awsxray.AlwaysRecordSampler;
import io.opentelemetry.contrib.awsxray.ResourceHolder;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.internal.OtlpConfigUtil;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.AggregationTemporalitySelector;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.MetricReader;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This customizer performs the following customizations:
 *
 * <ul>
 *   <li>Use AlwaysRecordSampler to record all spans.
 *   <li>Add SpanMetricsProcessor to create RED metrics.
 *   <li>Add AttributePropagatingSpanProcessor to propagate span attributes from parent to child
 *       spans.
 *   <li>Add AwsMetricAttributesSpanExporter to add more attributes to all spans.
 * </ul>
 *
 * <p>You can control when these customizations are applied using the property
 * otel.aws.application.signals.enabled or the environment variable
 * OTEL_AWS_APPLICATION_SIGNALS_ENABLED. This flag is disabled by default.
 */
public class AwsApplicationSignalsCustomizerProvider
    implements AutoConfigurationCustomizerProvider {
  private static final Duration DEFAULT_METRIC_EXPORT_INTERVAL = Duration.ofMinutes(1);
  private static final Logger logger =
      Logger.getLogger(AwsApplicationSignalsCustomizerProvider.class.getName());

  private static final String SMP_ENABLED_CONFIG = "otel.smp.enabled";
  private static final String APP_SIGNALS_ENABLED_CONFIG = "otel.aws.app.signals.enabled";
  private static final String APPLICATION_SIGNALS_ENABLED_CONFIG =
      "otel.aws.application.signals.enabled";
  private static final String SMP_EXPORTER_ENDPOINT_CONFIG = "otel.aws.smp.exporter.endpoint";
  private static final String APP_SIGNALS_EXPORTER_ENDPOINT_CONFIG =
      "otel.aws.app.signals.exporter.endpoint";
  private static final String APPLICATION_SIGNALS_EXPORTER_ENDPOINT_CONFIG =
      "otel.aws.application.signals.exporter.endpoint";

  // Histograms must only be exported with no more than 100 buckets per EMF specifications. Per OTEL
  // documentation,
  // https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/metrics/sdk.md#base2-exponential-bucket-histogram-aggregation
  // max total buckets = max_size*2+1; *2 is because of positive and negative buckets, +1 because of
  // the zero
  // bucket. Negatives are not a concern for Application Signals use-case, which only measures
  // latency, so max_size
  // of 99 gives total buckets of 100.
  // MaxScale is being set as the default value as per the OTEL spec.
  private static final int MAX_HISTOGRAM_BUCKETS = 99;
  private static final int MAX_HISTOGRAM_SCALE = 20;

  public void customize(AutoConfigurationCustomizer autoConfiguration) {
    autoConfiguration.addSamplerCustomizer(this::customizeSampler);
    autoConfiguration.addTracerProviderCustomizer(this::customizeTracerProviderBuilder);
    autoConfiguration.addSpanExporterCustomizer(this::customizeSpanExporter);
  }

  private boolean isApplicationSignalsEnabled(ConfigProperties configProps) {
    return configProps.getBoolean(
        APPLICATION_SIGNALS_ENABLED_CONFIG,
        configProps.getBoolean(
            APP_SIGNALS_ENABLED_CONFIG, configProps.getBoolean(SMP_ENABLED_CONFIG, false)));
  }

  private Sampler customizeSampler(Sampler sampler, ConfigProperties configProps) {
    if (isApplicationSignalsEnabled(configProps)) {
      return AlwaysRecordSampler.create(sampler);
    }
    return sampler;
  }

  private SdkTracerProviderBuilder customizeTracerProviderBuilder(
      SdkTracerProviderBuilder tracerProviderBuilder, ConfigProperties configProps) {
    if (isApplicationSignalsEnabled(configProps)) {
      logger.info("AWS Application Signals enabled");
      Duration exportInterval =
          configProps.getDuration("otel.metric.export.interval", DEFAULT_METRIC_EXPORT_INTERVAL);
      logger.log(
          Level.FINE,
          String.format("AWS Application Signals Metrics export interval: %s", exportInterval));
      // Cap export interval to 60 seconds. This is currently required for metrics-trace correlation
      // to work correctly.
      if (exportInterval.compareTo(DEFAULT_METRIC_EXPORT_INTERVAL) > 0) {
        exportInterval = DEFAULT_METRIC_EXPORT_INTERVAL;
        logger.log(
            Level.INFO,
            String.format(
                "AWS Application Signals metrics export interval capped to %s", exportInterval));
      }
      // Construct and set local and remote attributes span processor
      tracerProviderBuilder.addSpanProcessor(
          AttributePropagatingSpanProcessorBuilder.create().build());
      // Construct meterProvider
      MetricExporter metricsExporter =
          ApplicationSignalsExporterProvider.INSTANCE.createExporter(configProps);

      MetricReader metricReader =
          PeriodicMetricReader.builder(metricsExporter).setInterval(exportInterval).build();

      MeterProvider meterProvider =
          SdkMeterProvider.builder()
              .setResource(ResourceHolder.getResource())
              .registerMetricReader(metricReader)
              .build();
      // Construct and set application signals metrics processor
      SpanProcessor spanMetricsProcessor =
          AwsSpanMetricsProcessorBuilder.create(meterProvider, ResourceHolder.getResource())
              .build();
      tracerProviderBuilder.addSpanProcessor(spanMetricsProcessor);
    }
    return tracerProviderBuilder;
  }

  private SpanExporter customizeSpanExporter(
      SpanExporter spanExporter, ConfigProperties configProps) {
    if (isApplicationSignalsEnabled(configProps)) {
      return AwsMetricAttributesSpanExporterBuilder.create(
              spanExporter, ResourceHolder.getResource())
          .build();
    }

    return spanExporter;
  }

  private enum ApplicationSignalsExporterProvider {
    INSTANCE;

    public MetricExporter createExporter(ConfigProperties configProps) {
      String protocol =
          OtlpConfigUtil.getOtlpProtocol(OtlpConfigUtil.DATA_TYPE_METRICS, configProps);
      logger.log(
          Level.FINE, String.format("AWS Application Signals export protocol: %s", protocol));

      String applicationSignalsEndpoint;
      if (protocol.equals(OtlpConfigUtil.PROTOCOL_HTTP_PROTOBUF)) {
        applicationSignalsEndpoint =
            configProps.getString(
                APPLICATION_SIGNALS_EXPORTER_ENDPOINT_CONFIG,
                configProps.getString(
                    APP_SIGNALS_EXPORTER_ENDPOINT_CONFIG,
                    configProps.getString(
                        SMP_EXPORTER_ENDPOINT_CONFIG, "http://localhost:4316/v1/metrics")));
        logger.log(
            Level.FINE,
            String.format(
                "AWS Application Signals export endpoint: %s", applicationSignalsEndpoint));
        return OtlpHttpMetricExporter.builder()
            .setEndpoint(applicationSignalsEndpoint)
            .setDefaultAggregationSelector(this::getAggregation)
            .setAggregationTemporalitySelector(AggregationTemporalitySelector.deltaPreferred())
            .build();
      } else if (protocol.equals(OtlpConfigUtil.PROTOCOL_GRPC)) {
        applicationSignalsEndpoint =
            configProps.getString(
                APPLICATION_SIGNALS_EXPORTER_ENDPOINT_CONFIG,
                configProps.getString(
                    APP_SIGNALS_EXPORTER_ENDPOINT_CONFIG,
                    configProps.getString(SMP_EXPORTER_ENDPOINT_CONFIG, "http://localhost:4315")));
        logger.log(
            Level.FINE,
            String.format(
                "AWS Application Signals export endpoint: %s", applicationSignalsEndpoint));
        return OtlpGrpcMetricExporter.builder()
            .setEndpoint(applicationSignalsEndpoint)
            .setDefaultAggregationSelector(this::getAggregation)
            .setAggregationTemporalitySelector(AggregationTemporalitySelector.deltaPreferred())
            .build();
      }
      throw new ConfigurationException(
          "Unsupported AWS Application Signals export protocol: " + protocol);
    }

    private Aggregation getAggregation(InstrumentType instrumentType) {
      if (instrumentType == InstrumentType.HISTOGRAM) {
        return Aggregation.base2ExponentialBucketHistogram(
            MAX_HISTOGRAM_BUCKETS, MAX_HISTOGRAM_SCALE);
      }
      return Aggregation.defaultAggregation();
    }
  }
}

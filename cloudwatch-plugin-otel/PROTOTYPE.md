# Prototype: semantic conventions for span-derived metrics

This branch is a prototype for
[open-telemetry/semantic-conventions#4240](https://github.com/open-telemetry/semantic-conventions/issues/4240).
It is not intended to be merged or released.

It changes the plugin's output from the `spanmetrics` connector names to the
proposed convention:

| Before | Prototype |
| --- | --- |
| `traces.span.metrics.calls` (Counter, `{call}`) | removed: throughput is the histogram count |
| `traces.span.metrics.duration` (Histogram, `s`) | `otel.span.duration` (Histogram, `s`) |
| buckets `0.002 … 15` | buckets `0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30, 60` |
| `span.name` | `otel.span.name`, Opt-In (off by default) |
| `span.kind` = `SERVER` | `otel.span.kind` = `SERVER` |
| `status.code` = `UNSET` / `OK` / `ERROR` | `otel.status_code` = `OK` / `ERROR`, absent when `UNSET` |
| `error.type` only when the span has it | `error.type` from the span, or `_OTHER` when status is `ERROR` |

An end timestamp earlier than the start timestamp records `0`.

Unchanged: the semantic-convention attribute allowlist, the `aws.otel.*`
identity attributes, and `service.name` on the Resource only.

## Opt-In span name

Set either of:

- system property `otel.span.metrics.span_name.enabled=true`
- environment variable `OTEL_SPAN_METRICS_SPAN_NAME_ENABLED=true`

Manual SDK setup: `new SpanMetricsProcessor(true)`.

## Build and test

```shell
./gradlew build -p cloudwatch-plugin-otel
./gradlew java8SmokeTest -p cloudwatch-plugin-otel
```

The contract tests under `contract-tests/` still assert the previous shape and
were not updated for this prototype.

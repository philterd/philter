# Monitoring and Logging

Philter exposes operational metrics in [Prometheus](https://prometheus.io/) format. A monitoring stack (for example, Prometheus for collection and Grafana for visualization) scrapes these metrics and is responsible for storing, retaining, and visualizing them. Philter itself does not store metrics history.

## Metrics Endpoint

Philter publishes metrics at:

```
/actuator/prometheus
```

Point your Prometheus scraper at this endpoint (for example, `http://your-philter-endpoint:8080/actuator/prometheus`).

## Captured Metrics

Philter exposes the following counters, in addition to the standard JVM and HTTP server metrics provided by the runtime:

| Metric | Description |
|--------|-------------|
| `philter_redactions_total` | Total redactions performed, labeled by `filter_type`. |
| `philter_tokens_total` | Total tokens processed during redaction. |
| `philter_api_requests_total` | Total API requests, labeled by `method` and `status`. |

Because Prometheus counters are cumulative, query them with `rate()` or `increase()` over a time window rather than reading their absolute value. Counters reset to zero when Philter restarts; Prometheus handles these resets automatically, and the history scraped before a restart is retained by Prometheus.

> Metrics are intentionally low-cardinality and are not labeled by user or API key. To analyze usage by user, consume Philter's application logs.

## Logging

Philter writes application logs to standard output, which can be collected by your container or host logging stack.

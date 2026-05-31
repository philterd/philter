# PII Drift Monitoring with Phield

[Phield](https://github.com/philterd/phield) is a PII drift and trend monitor. It receives PII type counts, learns a baseline for each type over time, and alerts when a count deviates significantly from the established trend (for example, a sudden spike in credit card numbers). Philter can optionally publish the PII type counts from its redactions to Phield so you can monitor drift in the sensitive information flowing through your redaction pipeline.

## What Philter sends (counts only, never PII)

When the integration is enabled, after each redaction Philter sends Phield the **count of each PII type** it identified, along with the context and source metadata. **No PII is ever sent.** The payload contains only counts, such as:

```json
{
  "source_id": "philter",
  "organization": "philter",
  "context": "claims-intake",
  "pii_types": {
    "SSN": 6,
    "EMAIL_ADDRESS": 110,
    "CREDIT_CARD": 24
  }
}
```

The matched text and its replacement are never included, so no sensitive information leaves Philter. Philter posts this to Phield's `/ingest` endpoint.

## How it works

- **Disabled by default.** The integration is inactive unless the `PHIELD_URL` environment variable is set.
- **Fire-and-forget.** Counts are sent asynchronously with a short timeout, and any failure is ignored and logged at debug level. A slow or unavailable Phield never affects redaction latency or availability.
- **Per-context baselines.** Phield tracks a separate baseline for each `(source, organization, context, PII type)` combination. Because Philter sends the redaction [context](redaction/contexts.md), each context is monitored for drift independently. Redactions made without an explicit context are grouped under the `none` context.

## Enabling the integration

Set the following environment variables (see [Settings](settings.md#pii-drift-monitoring-phield) for the full reference):

| Environment Variable | Description | Default Value |
|----------------------|-------------|---------------|
| `PHIELD_URL` | Base URL of the Phield service (for example `http://phield:8080`). When set, Philter publishes PII type counts to `<PHIELD_URL>/ingest` after each redaction. Leave unset to disable. | (empty; disabled) |
| `PHIELD_SOURCE_ID` | The `source_id` reported to Phield, identifying this Philter instance. Use a distinct value per instance if you want each baselined separately. | `philter` |
| `PHIELD_ORGANIZATION` | The `organization` reported to Phield. | `philter` |

Once enabled, view PII flows, baselines, and drift alerts in Phield's own dashboard. See the [Phield documentation](https://philterd.github.io/phield) for installation, alerting channels (such as Slack and PagerDuty), and dashboard details.

## Relationship to metrics

Philter also exposes per-type redaction counts to Prometheus (see [Monitoring and Logging](monitoring_and_logging.md)). Prometheus is suited to general operational metrics and custom alerting rules, while Phield is purpose-built for PII drift detection (adaptive baselines, cooldowns, and a PII-specific dashboard) without you having to author alerting rules. The two are complementary; enable the Phield integration when you want turnkey PII drift monitoring.

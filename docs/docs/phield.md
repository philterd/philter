# PII Drift Monitoring with Phield

[Phield](https://github.com/philterd/phield) is a PII drift and trend monitor. It receives PII type counts, learns a baseline for each type over time, and alerts when a count deviates significantly from the established trend (for example, a sudden spike in credit card numbers). Philter can optionally publish the PII type counts from its redactions to Phield so you can monitor drift in the sensitive information flowing through your redaction pipeline.

## What Philter sends (counts only)

When the integration is enabled, Philter posts one message to Phield's `/ingest` endpoint after each redaction. It carries the number of times each PII type was identified, plus three labels. Nothing else is included:

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

| Field | What it holds | Where it comes from |
|--------|-------------|---------------|
| `pii_types` | One integer count per PII type identified | Counted from the redaction |
| `source_id` | Identifies this Philter instance | Admin setting |
| `organization` | The tenant or organization name | Admin setting |
| `context` | The redaction [context](redaction/contexts.md) | Supplied by the API caller |

**No redacted text is sent.** The matched values, their replacements, the submitted document, and the ledger tokens never leave Philter. The request body is built from a map of PII type names to integers and the three labels above; there is no code path that puts span text into it, and a test asserts on every build that the payload carries no span text or replacements.

The field to think about is `context`, which Philter forwards verbatim as the caller supplied it. It is intended as a label such as `claims-intake`, and used that way it carries no identifying information. Choose context names that are not themselves sensitive: a context named after a patient, an account number, or an email address would reach Phield along with the counts.

## How it works

- **Disabled by default.** The integration is inactive unless an administrator enables it (see below).
- **Fire-and-forget.** Counts are sent asynchronously with a short timeout, and any failure is ignored and logged at debug level. A slow or unavailable Phield never affects redaction latency or availability.
- **Per-context baselines.** Phield tracks a separate baseline for each `(source, organization, context, PII type)` combination. Because Philter sends the redaction [context](redaction/contexts.md), each context is monitored for drift independently. Redactions made without an explicit context are grouped under the empty (blank) context.

## Enabling the integration

This is configured by an administrator on the dashboard **Admin** page (it is off by default):

| Option | Description | Default |
|--------|-------------|---------|
| **Publish PII count statistics to Phield** | Enables publishing. When off, nothing is sent. | Off |
| **Phield URL** | Base URL of the Phield service (for example `http://phield:8080`). Counts are posted to `<URL>/ingest` after each redaction. Publishing is inactive while this is blank. | (empty) |
| **Phield Source ID** | The `source_id` reported to Phield, identifying this Philter instance. Use a distinct value per instance if you want each baselined separately. | `philter` |
| **Phield Organization** | The `organization` reported to Phield. | `philter` |
| **Phield API Key** | Sent as an `Authorization: Bearer` header on each request. Set this to the value of the Phield instance's `PHIELD_API_KEY`. Leave blank when the Phield instance is unauthenticated. | (empty) |

Changes take effect within a short interval (the settings are cached briefly to keep the redaction path fast).

Once enabled, view PII flows, baselines, and drift alerts in Phield's own dashboard. See the [Phield documentation](https://philterd.github.io/phield) for installation, alerting channels (such as Slack and PagerDuty), and dashboard details.

### Authenticating to Phield

Phield's `/ingest` endpoint requires a bearer token when the Phield instance is run with `PHIELD_API_KEY` set. Put that same key in **Phield API Key** and Philter sends it on each request. A mismatched or missing key gets `401 Unauthorized` from Phield, which Philter logs as a publishing failure; redaction is unaffected.

The key is [encrypted at rest](database.md#what-is-encrypted-at-rest) under `PHILTER_ENCRYPTION_KEY`, so a database dump alone does not yield it.

`https` is not required, because the payload is counts rather than PII and Phield is commonly reached over a private network. It is the key that is worth protecting: it is sent in a request header, so on a plain `http` URL anyone who intercepts it can write counts into Phield. Philter warns, on save and in the log, when a key is configured with an `http` URL pointing anywhere other than the local host. Use `https` when the key crosses a network you do not control.

### Using a Phield with a self-signed certificate

Phield's container generates its own certificate on start, and no public authority signs it. Philter validates the certificates presented to it, so publishing to such an instance fails the TLS handshake until the Philter JVM is told to trust that certificate. The failure is logged as a publishing failure and redaction is unaffected, so the symptom is counts that never arrive.

Two things have to be true.

**The certificate has to name the host Philter connects to.** Phield takes the certificate's common name from `PHIELD_CERT_CN` and defaults it to `localhost`, and adds no subject alternative names. An instance Philter reaches at `https://phield:8443` therefore has to be started with `PHIELD_CERT_CN=phield`. A name mismatch fails the handshake with `No subject alternative names present` even once the certificate is trusted.

**The certificate has to be in the truststore the Philter JVM uses.** Export it from the running Phield:

```bash
openssl s_client -connect phield:8443 -showcerts </dev/null 2>/dev/null \
    | openssl x509 -outform PEM > phield.crt
```

Copy the JVM's default truststore and add the certificate to the copy. Copy rather than create a new file: `javax.net.ssl.trustStore` replaces the default rather than adding to it, so a truststore holding only Phield's certificate would leave Philter unable to verify any other TLS it makes outbound, including webhook deliveries.

```bash
cp "${JAVA_HOME}/lib/security/cacerts" phield-truststore.p12

keytool -importcert -noprompt -alias phield -file phield.crt \
        -keystore phield-truststore.p12 -storepass changeit
```

`changeit` is the JDK default and is fine to keep. A truststore holds public certificates, so the password guards against tampering rather than disclosure.

Mount the truststore into the Philter container and point the JVM at it. The JVM reads `JAVA_TOOL_OPTIONS` on start, so no change to the command is needed:

```yaml
services:
  philter:
    volumes:
      - ./phield-truststore.p12:/opt/philter/ssl/phield-truststore.p12:ro
    environment:
      - JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStore=/opt/philter/ssl/phield-truststore.p12 -Djavax.net.ssl.trustStorePassword=changeit
```

The JVM prints `Picked up JAVA_TOOL_OPTIONS` on start when the setting is in effect. Repeat the export and import if Phield is recreated without a mounted certificate, since the entrypoint generates a new one.

A certificate from your own internal authority is the better option where you have one: import that authority once and it covers Phield and anything else it signs.

## Relationship to metrics

Philter also exposes per-type redaction counts to Prometheus (see [Monitoring and Logging](monitoring_and_logging.md)). Prometheus is suited to general operational metrics and custom alerting rules, while Phield is purpose-built for PII drift detection (adaptive baselines, cooldowns, and a PII-specific dashboard) without you having to author alerting rules. The two are complementary; enable the Phield integration when you want turnkey PII drift monitoring.

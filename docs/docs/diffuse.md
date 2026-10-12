# Differential-Privacy Reporting with Philter Diffuse

[Philter Diffuse](https://github.com/philterd/philterdiffuse) applies differential privacy (using [OpenDP](https://github.com/opendp/opendp)) to aggregate PII counts, producing compliance-ready reports with a tracked privacy budget. Philter can optionally record the PII type counts from its redactions so that Diffuse can privatize and report on them.

When this is enabled, after each redaction Philter records **counts only**, never any PII.

## What Philter records

Philter writes aggregated, time-bucketed **document-presence** counts to `pii_count_aggregates` in its database, one record per `(context, day)`. As MongoDB stores it:

```json
{
  "context": "claims-intake",
  "bucket_start": ISODate("2026-05-31T00:00:00Z"),
  "counts": { "SSN": 1240, "EMAIL_ADDRESS": 8900, "PERSON": 9500 },
  "total_documents": 10000,
  "updated_at": ISODate("2026-05-31T18:42:10Z")
}
```

For each redaction that finds PII, every distinct PII type present increments that type's counter by one, and `total_documents` increments by one. A redaction that finds no PII is not recorded, so `total_documents` counts documents that contained PII, not all documents redacted. `counts.SSN` is the number of **documents** in that day's bucket (for that context) that contained at least one SSN.

This "document-presence" counting is deliberate: it ensures each redaction contributes at most one to any count, which preserves the `sensitivity = 1` assumption that Diffuse's differential-privacy guarantee depends on. (Counting the total number of SSN spans instead would let a single document contribute many, which would weaken the privacy guarantee Diffuse reports.)

Because the counts are bucketed per `(context, day)`, the collection stays small regardless of redaction volume, so no retention TTL is required.

## Reading the counts through the API

The [PII Counts API](api_and_sdks/api/pii_counts_api.md) (`GET /api/pii-counts`) returns the same counts with an API key, so a reader needs no access to Philter's database and does not depend on how Philter stores them. Each entry gives a context, a UTC day, the count for each PII type, and `totalDocuments`. Narrow it by day range and context, and page through it with `offset` and `limit`. It requires an administrator and the `pii-counts:read` scope, and each read is recorded in the audit log.

```bash
curl -k -H "Authorization: Bearer <token>" \
  "https://localhost:8080/api/pii-counts?from=2026-09-01&to=2026-09-30"
```

The response's `recordingEnabled` field says whether Philter is recording counts now, so an empty result can be told apart from recording being off.

## Enabling it

Recording is controlled by a single global admin setting and is **off by default** (data minimization: statistics are not collected unless you opt in). An administrator enables it with the [Settings API](api_and_sdks/api/settings_api.md) (`{"diffuseCountsEnabled": true}`).

Recording is best-effort: any failure is ignored so it can never affect redaction, and when the setting is off there is no per-redaction overhead.

## Producing a report

Philter Diffuse reads the recorded counts through the [PII Counts API](api_and_sdks/api/pii_counts_api.md), so it needs only a Philter API key: no access to Philter's database, and it works with either storage type. The key must hold the `pii-counts:read` scope and belong to an administrator. Put it in the `PHILTER_API_KEY` environment variable and pass Philter's URL with `--philter-url`:

```bash
export PHILTER_API_KEY=<api key>
python main.py --philter-url https://localhost:8080 --aggregates --output privatized_counts.csv
```

Add `--context <name>` to restrict the report to a single context, and `--from` and `--to` (UTC days, `YYYY-MM-DD`) to restrict it to a range of days. If recording is turned off, Diffuse says so and writes no report. With the self-signed certificate the Docker image generates, pass that certificate to Diffuse with `--philter-ca-cert` (see [TLS](settings.md#tls)). Diffuse adds calibrated noise, tracks the epsilon (privacy loss) budget, and writes privatized counts. See the [Philter Diffuse documentation](https://philterd.github.io/philterdiffuse) for the privacy budget, scale/epsilon options, and report formats.

Diffuse can still read the `pii_count_aggregates` collection directly from Philter's MongoDB (`--mongo-uri "mongodb://localhost:27017/philter" --aggregates`, with `STORAGE_TYPE=mongodb`), but that path is deprecated in Philter Diffuse and will be removed.

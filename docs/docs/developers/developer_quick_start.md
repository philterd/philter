# Developer Quick Start

This guide walks through creating a redaction policy, redacting text, and redacting a PDF, using `curl` and Python.

## Before You Start

You need a running Philter instance and an API key. Create a key on the [API Keys](../account/api_keys.md) page of the dashboard, or seed one at startup with `PHILTER_BOOTSTRAP_API_KEY` (see [Settings](../settings.md)).

Every request carries the key as a bearer token:

```http
Authorization: Bearer <YOUR_API_KEY>
```

The examples use `https://localhost:8080` and pass `-k` to `curl` because the Docker image generates a self-signed certificate on first start. Replace the host with your own endpoint, and drop `-k` once you install a trusted certificate.

## Step 1: Create a Redaction Policy

A [policy](../redaction/policies.md) defines what Philter looks for and how each match is transformed. Philter ships with a `default` policy; this creates a custom one.

Save the following as `my-policy.json`:

```json
{
  "name": "my-custom-policy",
  "identifiers": {
    "ssn": {
      "ssnFilterStrategies": [
        { "strategy": "REDACT" }
      ]
    },
    "emailAddress": {
      "emailAddressFilterStrategies": [
        { "strategy": "MASK" }
      ]
    }
  }
}
```

`REDACT` replaces the match with a label, and `MASK` replaces each character with a mask character. See [Filter Strategies](../policies/filter_strategies.md) for the full set and [Policy Schema](../policies/policy_schema.md) for every field.

The policy name comes from the required `name` query parameter. A policy with the same name is overwritten.

### Using curl

```bash
curl -k -X POST "https://localhost:8080/api/policies?name=my-custom-policy" \
     -H "Authorization: Bearer YOUR_API_KEY" \
     -H "Content-Type: application/json" \
     -d @my-policy.json
```

A successful save returns `201 Created`. An invalid policy returns `400 Bad Request` with a message describing the problem, and nothing is stored.

### Using Python

```python
import requests

api_key = "YOUR_API_KEY"
base_url = "https://localhost:8080"

with open("my-policy.json") as f:
    policy_json = f.read()

response = requests.post(
    f"{base_url}/api/policies",
    params={"name": "my-custom-policy"},
    headers={
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    },
    data=policy_json,
    verify=False,
)

print(response.status_code)
```

## Step 2: Redact Text

`POST /api/filter` with `Content-Type: text/plain` redacts text synchronously and returns the redacted text in the response body.

Two query parameters control the request:

* `p` (optional, default `default`) is the policy to apply.
* `c` (optional) is the [context](../redaction/contexts.md). Documents sharing a context receive consistent replacements for the same value, so the same person can be tracked across documents without exposing their identity.

### Using curl

```bash
curl -k -X POST "https://localhost:8080/api/filter?c=my-context&p=my-custom-policy" \
     -H "Authorization: Bearer YOUR_API_KEY" \
     -H "Content-Type: text/plain" \
     -d "Send the results to jdoe@example.com and reference SSN 123-45-6789."
```

```
Send the results to **************** and reference SSN {{{REDACTED-ssn}}}.
```

The policy created in Step 1 covers only SSNs and email addresses, so nothing else in the text is changed. Person names, dates, and the rest require their own entries in the policy; the `default` policy is a broader starting point.

The response carries `X-Philter-Policy-Name` and `X-Philter-Policy-Version` recording which policy version governed the request, and `X-Document-Id` identifying the request. When [output signing](../output_signing.md) is enabled, an `X-Philter-Signature` JWT attests the response body.

### Using Python

```python
import requests

api_key = "YOUR_API_KEY"
base_url = "https://localhost:8080"

response = requests.post(
    f"{base_url}/api/filter",
    params={"c": "my-context", "p": "my-custom-policy"},
    headers={
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "text/plain",
    },
    data="Send the results to jdoe@example.com and reference SSN 123-45-6789.",
    verify=False,
)

print(response.text)
print(response.headers["X-Philter-Policy-Version"])
```

## Step 3: Redact a PDF

`POST /api/filter` with `Content-Type: application/pdf` is asynchronous by default. Philter returns `202 Accepted` with a `documentId`, redacts on a background worker, and holds the result for you to download. Append `?async=false` to receive the redacted PDF inline instead.

The body may be up to `MAX_FILE_SIZE_BYTES` (10 MB by default); a larger body is rejected with `413 Payload Too Large`. The body is also checked against the declared `Content-Type`, so a file that contradicts its header is rejected with `415 Unsupported Media Type` rather than silently passed through the wrong pipeline.

### Submit

```bash
curl -k -X POST "https://localhost:8080/api/filter?c=my-context&p=my-custom-policy" \
     -H "Authorization: Bearer YOUR_API_KEY" \
     -H "Content-Type: application/pdf" \
     --data-binary @sample_document.pdf
```

```json
{"documentId":"c0c2c5a8-3a78-4e56-bf2a-44ad8b3a8e9f"}
```

### Poll for Completion

```bash
curl -k -H "Authorization: Bearer YOUR_API_KEY" \
     https://localhost:8080/api/documents/c0c2c5a8-3a78-4e56-bf2a-44ad8b3a8e9f/status
```

```json
{"documentId":"c0c2c5a8-3a78-4e56-bf2a-44ad8b3a8e9f","status":"PROCESSING"}
```

A document moves through `PENDING`, `PROCESSING`, and then `COMPLETE` or `FAILED`. Rather than polling, you can configure a webhook to receive a signed notification when the redaction finishes; see [Webhooks](../api_and_sdks/api/webhooks.md).

### Download

```bash
curl -k -H "Authorization: Bearer YOUR_API_KEY" \
     -o redacted_document.pdf \
     https://localhost:8080/api/documents/c0c2c5a8-3a78-4e56-bf2a-44ad8b3a8e9f
```

Downloading before the redaction completes returns `409 Conflict`; a failed redaction returns `410 Gone`. Completed records are removed after `PENDING_DOCUMENTS_TTL_SECONDS` (7 days by default).

### The Whole Flow in Python

```python
import time

import requests

api_key = "YOUR_API_KEY"
base_url = "https://localhost:8080"
headers = {"Authorization": f"Bearer {api_key}"}

with open("sample_document.pdf", "rb") as f:
    submit = requests.post(
        f"{base_url}/api/filter",
        params={"c": "my-context", "p": "my-custom-policy"},
        headers={**headers, "Content-Type": "application/pdf"},
        data=f,
        verify=False,
    )

document_id = submit.json()["documentId"]

while True:
    status = requests.get(
        f"{base_url}/api/documents/{document_id}/status",
        headers=headers,
        verify=False,
    ).json()["status"]

    if status in ("COMPLETE", "FAILED"):
        break

    time.sleep(2)

if status == "COMPLETE":
    download = requests.get(
        f"{base_url}/api/documents/{document_id}",
        headers=headers,
        verify=False,
    )
    with open("redacted_document.pdf", "wb") as f:
        f.write(download.content)
```

## Validate the Output

Detection is probabilistic, so review the output against your own data before putting a policy into production. [`POST /api/explain`](../api_and_sdks/api/filtering_api.md) returns the spans Philter identified and the policy that produced them, which is the fastest way to see why a value was or was not redacted. The [Redaction Ledger](../redaction/ledgers.md) records what was redacted in each document for later review.

## Next Steps

* [API Reference](../api_and_sdks/api.md) for every endpoint.
* [Filtering API](../api_and_sdks/api/filtering_api.md) and [Documents API](../api_and_sdks/api/documents_api.md) for the full request and response details used above.
* [Policy Schema](../policies/policy_schema.md) for advanced policy configuration.
* [Client SDKs](../api_and_sdks/sdks.md) for the Java SDK and for generating a client in other languages.
* The interactive Swagger UI on your own instance at `https://localhost:8080/swagger-ui/index.html`.

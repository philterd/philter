# Monitoring and Logging

Philter uses OpenSearch to capture metrics for both API requests and redaction usage. These metrics are used to populate the dashboard and provide insights into how Philter is being used.

## OpenSearch Integration

Philter can be configured to send usage and auditing data to an OpenSearch cluster. This is controlled by environment variables as described in the [Settings](settings.md#usage-and-auditing-settings-opensearch) documentation.

## Captured Metrics

Philter captures two primary types of metrics in OpenSearch:

### API Request Metrics

Every request made to Philter's API is logged to the API requests index (default: `pds-api-requests`). This includes:

* **Timestamp**: The date and time of the request.
* **User ID**: The ID of the user (API key) making the request.
* **Endpoint**: The specific API endpoint being accessed (e.g., `/api/filter`, `/api/explain`).
* **Premium**: A flag indicating if the request is considered a premium feature.

### Redaction Metrics

When documents are processed for redaction, metrics about the redaction process are logged to the redactions index (default: `pds-redactions`). These metrics include:

* **Timestamp**: The date and time of the redaction.
* **User ID**: The ID of the user (API key) that initiated the redaction.
* **Document ID**: A unique identifier for the document being redacted.
* **Context**: The filtering context used for the redaction.
* **Tokens**: The number of tokens (words) processed in the document.
* **Redactions**: The number of sensitive information spans identified and redacted.
* **Type**: The type of document processed (e.g., `text/plain`, `application/pdf`).

## Visualization in Dashboard

The metrics captured in OpenSearch are visualized in the Philter dashboard under the **Metrics** section. This provides real-time and historical views of:

* API request volume over time.
* Total tokens processed.
* Total redactions performed.
* Usage patterns by user and context.

By default, the dashboard shows metrics for the last 30 days.


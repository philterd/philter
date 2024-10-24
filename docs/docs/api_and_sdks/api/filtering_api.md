# Filtering API

Philter’s filtering API provides access to Philter’s ability to filter sensitive information from text and to retrieve the health status of Philter.

> The `curl` example commands shown on this page are written assuming Philter has been enabled for SSL and it is using a self-signed certificate. If launched from a cloud marketplace, SSL will be enabled automatically with a self-signed SSL certificate. See the [SSL/TLS ](../../settings.md) settings for more information.

Each filter request can optionally have a `context`. When not provided, the context defaults to `none`. Contexts provide a means for logically grouping your documents during filtering. For example, documents pertaining to one health care provider may be submitted under the context `hospital1`, and documents pertaining to another health care provider may be submitted under the context `hospital2`.

The context for each filter request impacts how sensitive information is replaced when found in the text. [Consistent anonymization](../../other_features/consistent_anonymization.md) can be enabled at either the context or document level. When enabled at the context level, all instances of a given piece of sensitive information will be replaced consistently by the same value. This allows for maintaining meaning across all documents in the context.

Each filter request submitted to Philter is automatically assigned a document identifier. The document identifier is an alphanumeric value unique to that request. No two documents should be assigned the same document identifier. The document identifier is returned in the `x-document-id` header with each `filter` or `explain` API response.

## Filter

The `filter` endpoint receives plain text or a PDF document and returns the redacted text or redacted PDF document.

The types of sensitive information found and how each type is redacted is determined by the chosen policy.

| Method | Endpoint      | Description            |
|--------|---------------|------------------------| 
| `POST` | `/api/filter` | Filter the given text. |

### Query Parameters

* `d` - A document ID that uniquely identifies the text being submitted. Leave empty and Philter will generate a document ID derived from a hash of the submitted text.
* `p` - The name of the policy to use for filtering. Defaults to `default` if not provided.
* `c` - The filtering context. Defaults to `none` if not provided.

### Headers

* `Content-Type` - The value should be set to `text/plain` or `application/pdf`.

Example request to filter plain text:

```
curl -k -X POST "https://localhost:8080/api/filter" -d @file.txt -H Content-Type "text/plain"
```

Example request to filter a PDF document:

```
curl -k -X POST "https://localhost:8080/api/filter?" -d @file.pdf -H Content-Type "application/pdf" -O redacted.zip
```

## Explain

The `explain` endpoint behaves much like the `filter` endpoint in that receives plain text and returns the redacted plain text. However, the `explain` endpoint provides a detailed explanation describing how the text was redacted. Also, the `explain` endpoint does not support PDF documents.

The types of sensitive information found and how each type is redacted is determined by the chosen policy.

| Method | Endpoint       | Description                                               |
|--------|----------------|-----------------------------------------------------------| 
| `POST` | `/api/explain` | Filter the given text and provide a detailed explanation. |

### Query Parameters

* `d` - A document ID that uniquely identifies the text being submitted. Leave empty and Philter will generate a document ID derived from a hash of the submitted text.
* `p` - The name of the policy to use for filtering. Defaults to `default` if not provided.
* `c` - The filtering context. Defaults to `none` if not provided.

### Headers

* `Content-Type` - The value should be set to `text/plain`.

Example explain request:

```
curl -k -X POST "https://localhost:8080/api/explain" -d @file.txt -H Content-Type "text/plain"
```

Example explain response:

```
{
  "filteredText": "{{{REDACTED-entity}}} was a patient and his ssn was {{{REDACTED-ssn}}}.",
  "context": "none",
  "documentId": "7a906866-4fc9-44d6-9bc3-22728b93a602",
  "explanation": {
    "appliedSpans": [
      {
        "id": "c78fb69c-84d6-4189-b376-63791793cbd2",
        "characterStart": 0,
        "characterEnd": 17,
        "filterType": "NER_ENTITY",
        "context": "C1",
        "documentId": "7a906866-4fc9-44d6-9bc3-22728b93a602",
        "confidence": 0.9189682900905609,
        "text": "George Washington",
        "replacement": "{{{REDACTED-entity}}}",
        "ignored": false
      },
      {
        "id": "f4556f62-2f80-4edc-96f0-aa1d44802157",
        "characterStart": 48,
        "characterEnd": 59,
        "filterType": "SSN",
        "context": "C1",
        "documentId": "7a906866-4fc9-44d6-9bc3-22728b93a602",
        "confidence": 1,
        "text": "123-45-6789",
        "replacement": "{{{REDACTED-ssn}}}",
        "ignored": false
      }
    ],
    "ignoredSpans": []
  }
}
```

## Status

The `status` endpoint is useful in determining the current state of Philter. The `status` endpoint can be used by monitoring software to assess Philter's availability or by your cloud provider for purposes of determining Philter's health when deployed behind a load balancer.

| Method | Endpoint      | Description                 |
|--------|---------------|-----------------------------| 
| `GET`  | `/api/status` | Gets the status of Philter. |

Example request:

```
curl -k -X POST "https://localhost:8080/api/status"
```

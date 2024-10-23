# Document Analysis

Philter analyzes received documents prior to redacting the document. This analysis is done to help Philter get a better understanding of the document. The results of the analysis are used to [exclude certain document types from redaction](excluding_by_document_type.md) and to improve Philter's redaction performance.

While not recommended, the automatic document analysis can be disabled in a policy. By default, document analysis is enabled.

> Disabling document analysis will cause any policy features dependent on the results of the document analysis to not function.

An example policy with disabled document analysis is shown below.

```
{
  "name": "email-and-phone-numbers",
  "config": {
    "analysis": {
      "enabled": false
    }
  },
  "identifiers": {
    "emailAddress": {
      "emailAddressFilterStrategies": [
        {
          "strategy": "REDACT",
          "redactionFormat": "{{{REDACTED-%t}}}"
        }
      ]
    }
  }
}
```

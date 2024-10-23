# Excluding by Document Type

Philter can automatically detect certain types of documents and exclude those documents from redaction of certain sensitive information. For example, you want to redact SSN/TINs in all but one type of document.

To exclude a document type from a specific filter, set the `excludeDocumentTypes` value to a list of document types to exclude for a filter strategy. Filter strategies for all filter types support the `excludeDocumentTypes` property.

An example to exclude email addresses from being redacted in a subpoena document is given below:

```
{
   "name": "email-address",
   "identifiers": {
      "emailAddress": {
         "emailAddressFilterStrategies": [
            {
               "strategy": "REDACT",
               "redactionFormat": "{{{REDACTED-%t}}}",
               "excludeDocumentTypes": ["SUBPOENA"]
            }
         ]
      }
   }
}
```

In this example, email addresses are redacted in all document types except documents Philter identifies as being subpoena documents.

### Document Types Supported by Automatic Detection

Philter currently supports automatically detecting the following document types.

| Document Type | Document Description                                                           |
| ------------- | ------------------------------------------------------------------------------ |
| Subpoena      | Form 2540 Federal Bankruptcy - SUBPOENA FOR RULE 2004 EXAMINATION              |
| Subpoena      | Form 2550 - Federal Bankruptcy - SUBPOENA TO APPEAR AND TESTIFY                |
| Subpoena      | Form 2560 - Federal Bankruptcy - SUBPOENA TO TESTIFY AT A DEPOSITION           |
| Subpoena      | Form 2570 - Federal Bankruptcy - SUBPOENA TO PRODUCE DOCUMENTS                 |
| Subpoena      | AO 88 - SUBPOENA TO APPEAR AND TESTIFY AT A HEARING OR TRIAL IN A CIVIL ACTION |
| Subpoena      | AO 88A - SUBPOENA TO TESTIFY AT A DEPOSITION IN A CIVIL ACTION                 |
| Subpoena      | AO 88B - SUBPOENA TO PRODUCE DOCUMENTS, INFORMATION, OR OBJECTS                |
| Subpoena      | AO 89 - SUBPOENA TO TESTIFY AT A HEARING OR TRIAL IN A CRIMINAL CASE           |
| Subpoena      | AO 90 - SUBPOENA TO TESTIFY AT A DEPOSITION IN A CRIMINAL CASE                 |
| Subpoena      | AO 110 - SUBPOENA TO TESTIFY BEFORE A GRAND JURY                               |

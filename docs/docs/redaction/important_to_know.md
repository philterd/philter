# Important to Know

Redaction is a complex process with many variables. To ensure you get the best results from Philter, keep the following considerations in mind.

## Document Metadata is Not Redacted

Philterd focuses on the content of the documents and datasets provided. It does not inspect or redact document metadata, such as:

*   File properties (Author, Title, Created Date).
*   Revision history or "Track Changes" in word processing documents.
*   EXIF data in images embedded within documents.
*   Hidden spreadsheet rows, columns, or comments.

Ensure you scrub metadata using specialized tools before or after the redaction process if your security policy requires it.

## The Quality of Input Text Matters

The accuracy of redaction is highly dependent on the quality of the input text.

*   **OCR Accuracy:** If you are redacting scanned documents, the quality of the Optical Character Recognition (OCR) is critical. If the OCR engine misreads "Jane Doe" as "J4ne D0e," the redaction engine may not recognize it as a name.
*   **Context:** Redaction engines use surrounding text to determine if a word is sensitive. Fragmented text or lists of data without context can be harder to redact accurately than full sentences.

## Combination of Techniques

Philterd uses a combination of techniques to redact text. See [Mistakes](../mistakes.md) for more information.

## Redaction is One-Way

Philter does not provide a way to reverse redaction. Changesets allow you to see the changes made to documents, but they do not provide a way to undo the changes.

## Regulatory Compliance

While Philter provides the tools to help you achieve compliance with regulations like HIPAA, GDPR, and CCPA, using the service does not automatically make you compliant. Compliance is a result of your overall data handling policies, how you configure your redaction policies, and how you verify the output.

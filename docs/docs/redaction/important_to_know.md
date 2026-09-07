# Important to Know

Redaction is a complex process with many variables. To ensure you get the best results from Philter, keep the following considerations in mind.

## Document Metadata is Not Redacted

Philter supports text and PDF redaction. Do not rely on sensitive-information detection to scrub file properties, embedded-image metadata, or other non-text surfaces. Review those surfaces with appropriate tools. Philter does not process Office documents; revision history, tracked changes, and spreadsheet content require external conversion and review.

Ensure you scrub metadata using specialized tools before or after the redaction process if your security policy requires it.

## The Quality of Input Text Matters

The accuracy of redaction is highly dependent on the quality of the input text.

*   **External OCR:** Philter does not implement OCR or redact image-only PDFs. If you extract text with an external OCR tool, review its accuracy before submitting that text to Philter. Redacting extracted text does not redact the original scanned image. For PDFs with annotations, also review the [PDF limitations](redacting_documents.md#supported-file-formats).
*   **Context:** Redaction engines use surrounding text to determine if a word is sensitive. Fragmented text or lists of data without context can be harder to redact accurately than full sentences.

## Combination of Techniques

Philterd uses a combination of techniques to redact text. See [Mistakes](../mistakes.md) for more information.

## Reversibility Depends on the Strategy

`CRYPTO_REPLACE` and `FPE_ENCRYPT_REPLACE` produce encrypted values that authorized callers can decrypt through the [Re-identification API](re-identification.md). Strategies such as `REDACT` and `MASK` do not encode the original value in their output, but a context with the [redaction ledger](ledgers.md) enabled retains encrypted original values as evidence. Protect the keys, ledger access, and context mappings when assessing whether a dataset can be re-identified. Re-identification of supported encrypted values does not reconstruct a redacted PDF.

## Regulatory Compliance

While Philter provides the tools to help you achieve compliance with regulations like HIPAA, GDPR, and CCPA, using the service does not automatically make you compliant. Compliance is a result of your overall data handling policies, how you configure your redaction policies, and how you verify the output.

# Disambiguation

When Philter processes text, multiple filters might identify overlapping or conflicting spans of text as sensitive information. Disambiguation is the process of resolving these overlaps to ensure only the most accurate and relevant redactions are applied.

Philterd uses several strategies to disambiguate overlapping spans.

## Disambiguation at the Document or Context Level

During redaction, disambiguation can be performed at the document or context level. When done on the document level, Philter will use other information from only the document being redacted to disambiguate overlapping types of PII and PHI. When done at the context level, Philter will use information from all documents in the same redaction context to disambiguate overlapping types of PII and PHI.

### Entity Type Disambiguation

Entity type disambiguation is a specific form of disambiguation that uses contextual information to determine the correct entity type when multiple types are possible. This feature can be enabled or disabled at the [context level](contexts.md).

When enabled for a context, Philter will utilize the context's accumulated knowledge to improve the accuracy of entity type identification across all documents processed within that context.

The option between disambiguation at the document level and the context level is called **disambiguation scope** and can be set in your [redaction policy](policy_syntax.md).

When disambiguation is performed on the context level, you can think of Philter as "getting smarter" over time. As more documents are processed under a context, the redaction engine will have more information to utilize in its analysis. This can ultimately lead to more accurate redactions.

Disambiguation at the document level is generally more efficient and can be used in cases where you have a small number of documents to redact.

## Longest Span Wins

The primary rule for resolving overlapping spans is automatically selecting the longest span. If one identified span is entirely contained within another, Philter will choose to redact the longer span.

For example, in the phrase "Washington State University", a filter might identify "Washington" as a name, while another identifies "Washington State University" as an organization. 

Philterd will apply the redaction for "Washington State University" because it is the longer, more specific span.

## Contextual Disambiguation

For spans that are identical or partially overlap, Philterd uses contextual disambiguation. This allows Philter to look at the words surrounding a sensitive term to determine its most likely type.

**Examples:**

*   "I met with **George Washington** today." (Likely a Person)
*   "I am visiting **Washington** next week." (Likely a Location)

Philter analyzes the context to assign higher confidence to the most appropriate filter type for that specific instance.

## Confidence Thresholds

Every identified span is assigned a confidence score. You can control which spans are considered for redaction by setting a Confidence Threshold value in your [redaction policy](policy_syntax.md).

*   Spans with a confidence score below your chosen threshold are automatically discarded. (For instance, set the threshold to 0.5 to discard spans with a confidence score less than 0.5)
*   If multiple filters identify overlapping spans with different types, Philter uses the confidence scores (adjusted by context) to help decide which span to redact.

## Partial Overlaps

In cases where spans partially overlap but neither entirely contains the other, Philter typically favors the span with the higher confidence score. If confidence scores are equal, the first identified span is generally preferred, but the system aims to minimize data loss by ensuring sensitive information is covered.

## How it Works Together

1.  Identification: All filters in the chosen redaction policy are used to scan the text and identify potential sensitive spans.
2.  Scoring: Each span is assigned a confidence score.
3.  Contextual Adjustment: Philter adjusts these scores based on the text surrounding each span.
4.  Filtering: Spans below the policy's confidence threshold are removed.
5.  Resolution: The span with the longest length and confidence-based resolution rules are applied to the remaining spans to produce the final set of redacted spans.

# Disambiguation

When Philter processes text, multiple filters might identify overlapping or conflicting spans of text as sensitive information. Disambiguation is the process of resolving these overlaps to ensure only the most accurate and relevant redactions are applied.

Philterd uses several strategies to disambiguate overlapping spans.

## Disambiguation at the Document or Context Level

During redaction, disambiguation can be performed at the document or context level. When done on the document level, Philter will use other information from only the document being redacted to disambiguate overlapping types of PII and PHI. When done at the context level, Philter will use information from all documents in the same redaction context to disambiguate overlapping types of PII and PHI.

### Entity Type Disambiguation

Entity type disambiguation is a specific form of disambiguation that uses contextual information to determine the correct entity type when multiple types are possible. This feature can be enabled or disabled at the [context level](contexts.md).

When enabled for a context, Philter will utilize the context's accumulated knowledge to improve the accuracy of entity type identification across all documents processed within that context.

The option between disambiguation at the document level and the context level is called **disambiguation scope** and is configured on the [context](contexts.md), not in the policy. See [Span Disambiguation](../other_features/span_disambiguation.md).

When disambiguation is performed on the context level, you can think of Philter as "getting smarter" over time. Philter learns from unambiguous spans (text that only one filter claimed) by recording the surrounding words as an example of that type, then compares future competing spans against this accumulated knowledge. As more documents are processed under a context, the engine has more examples to draw on, which can lead to more accurate redactions. A brand-new context has no examples yet, so its first decisions fall back to a deterministic default and improve as examples accumulate.

Disambiguation at the document level is generally more efficient and can be used in cases where you have a small number of documents to redact.

## Longest Span Wins

The primary rule for resolving overlapping spans is automatically selecting the longest span. If one identified span is entirely contained within another, Philter will choose to redact the longer span.

For example, in the phrase "Washington State University", a filter might identify "Washington" as a name, while another identifies "Washington State University" as an organization. 

Philterd will apply the redaction for "Washington State University" because it is the longer, more specific span.

## Contextual Disambiguation (Entity Type Disambiguation)

When two filters claim the **same** text (identical start and end positions) but assign different types, Philter uses contextual disambiguation to choose between them. It compares the words surrounding the span against the context it has learned for each candidate type and selects the most likely one. This is the vector-based feature described in detail in [Span Disambiguation](../other_features/span_disambiguation.md).

For example, if the word "Washington" in "I am visiting Washington next week." is claimed both as a person name and as a location, the surrounding words ("visiting", "next week") favor the location interpretation.

This applies only to spans covering identical text. Spans that merely overlap (one longer than the other) are resolved by the longest-span and confidence rules described above, not by contextual disambiguation.

## Confidence Thresholds

Every identified span is assigned a confidence score. You can control which spans are considered for redaction by setting a Confidence Threshold value in your [redaction policy](../policies/policy_schema.md).

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

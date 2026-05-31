# Span Disambiguation

Span disambiguation is an optional feature in Philter that is disabled by default. Refer to Philter' [Settings](../settings.md) to enable and configure span disambiguation.

In Philter, a _span_ is a piece of the input text that Philter has identified as sensitive information. A span has a start and end positions, a confidence, a type, and other attributes. Ideally, each piece of identified sensitive information will only have a single span associated with it. In this case, the type of sensitive information is unambiguous. The goal of span disambiguation is to provide more accurate filtering by removing the potential ambiguities in the types of sensitive information for duplicate spans.

However, sometimes the exact same piece of text is identified by two different filters, each assigning it a different type of sensitive information. For span disambiguation to apply, the competing spans must cover the **identical** characters (the same start and end positions) and differ only by type. This happens when filters whose patterns can match the same text are enabled together. For example, a nine-digit number such as `123456789` could be matched both by the SSN filter and by a custom [identifier](../policies/filters/custom_filters/identifier.md) filter configured to match nine-digit IDs. Given the input `The employee id 123456789 was assigned.`, both filters claim the characters `123456789`, leaving Philter with two competing spans for the same text.

> Filters whose patterns are structurally different never produce competing spans, so they are not candidates for disambiguation. For instance, an SSN (nine digits, grouped 3-2-4) and a US phone number (ten digits, grouped 3-3-4) match different text of different lengths, so the same characters are never identified as both.

### How Philter's Span Disambiguation Works

When we read the sentence `The employee id 123456789 was assigned.` we can tell the value should be an identifier rather than an SSN, because the surrounding words ("employee", "id") point to that type. We use those surrounding words to deduce the correct type for `123456789`.

That is how Philter's span disambiguation works. When presented with competing spans that cover the same characters but differ by type, Philter looks at the text surrounding the span in combination with the spans it has previously seen in the same context to determine which type is most likely correct. Philter then removes the competing spans and keeps the single most likely one.

### Improves Over Time

Philter learns the context associated with each type from the unambiguous spans it sees: when only one filter claims a piece of text, Philter records the surrounding words as an example of that type within the context. Future competing spans are then compared against this accumulated knowledge, so disambiguation becomes more accurate as more text is filtered within the same context.

When a context is new and has not yet seen any examples, there is no learned signal to compare against. In that case Philter makes a deterministic default choice among the competing types; accuracy improves once the context has processed unambiguous examples of the types involved. Use a [context](../redaction/contexts.md) with disambiguation enabled at the **context** scope (rather than the document scope) so this learning persists across documents.

### More Details

#### Span Disambiguation and Span Locations

Span disambiguation is only invoked for competing spans that cover the identical location (the same start and end positions) but were assigned different types. Confidence is not required to match: competing filters routinely assign different confidences to the same text, and those are exactly the cases disambiguation resolves. Spans that do not share the same location are handled by normal overlap resolution rather than disambiguation.

#### Cache Service

When multiple application using Philter are deployed alongside each other behind a load balancer, Philter' [cache service](../settings.md) should be configured and enabled. Philter will store the information needed to disambiguate spans in the cache such that the information is available to each instance of Philter. If only a single instance of Philter is running then the cache service is not required, however, the information needed to disambiguate spans will be stored in memory and will be lost when Philter is stopped or restarted. Because of this, we recommend the cache service always be used unless there is a specific reason not to.

#### Fine-Tuning the Span Disambiguation

There are properties available to fine-tune how the span disambiguation operates. These properties are not documented because improper use of the properties could have a negative impact on performance. We will be glad to walk through these properties upon request.

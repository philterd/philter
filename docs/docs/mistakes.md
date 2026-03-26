# Mistakes: Understanding Redaction Accuracy and Limitations

**The information on this page is very important. If you have questions or would like clarification on anything discussed here, please [contact us](mailto:support@philterd.ai) and we will be glad to assist.**

Redaction is a critical process for protecting sensitive information, but it is important to understand that it is not an exact science. While Philter makes use of sophisticated technologies to identify and mask sensitive data, the process is inherently probabilistic and can occasionally result in mistakes.

## Redaction is Not a Scientific Process

Automated redaction involves the interpretation of human language, which is complex, nuanced, and often ambiguous. Because language does not follow a rigid set of mathematical rules, identifying sensitive information (like, but not limited to, names, addresses, or ages) requires balancing different methods of detection.

In document redaction, there are two primary types of errors:

*   **False Positives**: When the system incorrectly identifies non-sensitive information as sensitive (e.g., redacting the word "Washington" when it refers to a state rather than a person).
*   **False Negatives**: When the system fails to identify and redact actual sensitive information (e.g., missing a name that is misspelled or used in an unusual context).

## Redaction Methods

Philter uses a multi-layered approach to identification. The following two methods are primarily used to identify sensitive information:

### Pattern-Based Redaction

Pattern-based redaction relies on predefined rules, regular expressions, and checksums to identify information that follows a predictable format.

*   **How it works**: It looks for specific sequences of characters. For example, a Social Security Number (SSN) follows a `XXX-XX-XXXX` pattern, and a credit card number must pass a Luhn algorithm check.
*   **Strengths**: Highly accurate for highly structured data like SSNs, credit card numbers, and certain identification IDs.
*   **Limitations**: It cannot identify sensitive information that doesn't follow a fixed pattern, such as names, street addresses, or ages.

### Statistical-Based Redaction

Statistical-based redaction using machine learning and natural language processing identifies sensitive information based on the context in which words appear.

*   **How it works**: The system is trained on vast amounts of data to recognize the "shape" of sensitive entities. It understands that a word following "Mr." or "Dr." is likely a name, or that a sequence of words following "located at" is likely an address.
*   **Strengths**: Capable of identifying unstructured data like names, locations, and organizations that do not have a fixed format.
*   **Limitations**: Because it relies on statistical probability, it can be influenced by the quality of the training data and the complexity of the sentence structure, leading to potential false positives or negatives.

## Improving Accuracy with Custom Lists

When you identify mistakes in the automated redaction process, Philter provides several tools to help you remediate them and improve future performance.

### Always Redact and Never Redact Lists

One of the most effective ways to handle consistent mistakes is by using "Always Redact" and "Never Redact" lists. These lists allow you to explicitly define terms that the system should either always target or always ignore.

*   **Terms to Always Redact**: If you find that certain sensitive terms (like project codenames or internal IDs) are consistently missed by automated filters (False Negatives), you can add them to an "Always Redact" list.
*   **Terms to Never Redact**: If the system is over-redacting non-sensitive terms (like your company name or common industry terms) (False Positives), you can add them to a "Never Redact" list to ensure they are always protected.

### Global vs. Per-Policy Configuration

These lists can be configured at two different levels depending on your needs:

1.  **Global Terms**: These are applied across **all** redaction policies in your account. They are ideal for terms that should be handled consistently regardless of the specific document type or project (e.g., your company's name). See [Global Terms](redaction/global_terms.md) for more information.
2.  **Per-Policy Terms**: You can also define "Always Redact" and "Ignore" terms within an individual [redaction policy](redaction/policies.md). This is useful for project-specific terms that only apply to certain types of documents.

## The Importance of Manual Review

Because no automated system can guarantee 100% accuracy, **it is essential that all redacted documents are reviewed manually by a human.**

Automated redaction should be viewed as a powerful tool to accelerate the protection of sensitive data, but it does not replace the need for human oversight. A manual review ensures that:

1.  **Critical sensitive data was not missed** (preventing data breaches).
2.  **The document remains readable and useful** (ensuring non-sensitive information was not over-redacted).
3.  **Contextual nuances are correctly handled** that an algorithm might misunderstand.

Philter provides tools like **Redaction Summaries** and **Changesets** to assist in this review process, allowing you to quickly see what was changed and verify the results.

### Best Practices for Review

*   **Verify High-Risk Entities**: Pay extra attention to names, dates, and locations.
*   **Use Changesets**: Review the exact text that was removed to ensure the system's logic aligned with your expectations.
*   **Iterate on Policies**: If you notice consistent mistakes, refine your [redaction policy](redaction/policies.md), use [Custom Lists](redaction/custom_lists.md), or update your **Always/Never Redact** lists to improve future performance.
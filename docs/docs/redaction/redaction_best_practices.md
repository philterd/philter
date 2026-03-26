# Redaction Best Practices

Redacting sensitive information is rarely a "one-and-done" task. To achieve the highest level of data protection while maintaining the utility of your documents, we recommend following these best practices.

## An Iterative Process

Redaction should be viewed as an iterative process. It is common to apply a policy, review the results, and then refine the policy to improve the outcome.

1.  **Initial Run**: Apply your chosen redaction policy (or the `default` policy) to a sample of your documents.
2.  **Review**: Carefully examine the redacted output. Look for any "false negatives" (sensitive information that was missed) or "false positives" (information that was redacted but shouldn't have been).
3.  **Refine**: Adjust your [Redaction Policy](policies.md) based on your findings. This might involve:
    *   Adding [Custom Lists](custom_lists.md) for project-specific terms.
    *   Applying [Redaction Lenses](lenses.md) to optimize for your specific data domain (e.g., medical or legal).
    *   Adjusting [Policy Syntax](policy_syntax.md) to fine-tune identification rules.
4.  **Repeat**: Run the redaction process again with the refined policy and repeat the review until the results meet your requirements.

## Documents Require Individual Attention

Every document is unique. Differences in formatting, context, and language usage mean that a policy that works perfectly for one document might need adjustments for another.

*   **Varying Layouts**: Documents from different sources often have unique structures that can affect how information is identified.
*   **Contextual Nuance**: Some information may be sensitive in one context but not in another. Use [Contexts](contexts.md) to manage these variations.
*   **Ambiguity**: Names, locations, and other identifiers can sometimes be ambiguous. [Disambiguation](disambiguation.md) can help improve the redaction accuracy.

## Human-in-the-Loop Review

While Philterd Data Services uses advanced AI and machine learning, automated redaction is not a substitute for human oversight. Especially for highly sensitive or regulated data, a "human-in-the-loop" review of the redacted documents is strongly encouraged.

## Start with a Risk Assessment

Before performing redaction, run a [Risk Assessment](../risk_assessments/risk_assessments.md) on your documents. This provides a quantitative overview of the sensitive information present, helping you prioritize which documents need the most attention and which policies might be most effective.

## Use Custom Lists for Known Terms

If you have a known set of terms that must always be redacted (e.g., internal project names, specific employee IDs) or always protected, use [Custom Lists](custom_lists.md). This is one of the most effective ways to quickly improve redaction accuracy for your specific organization.

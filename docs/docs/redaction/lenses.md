# Redaction Lenses

Redaction lenses are specialized configurations within Philterd Data Services that optimize the redaction process for specific domains or types of text. By applying a lens, you can enhance the accuracy and relevance of sensitive data identification based on the context of your documents.

It is **not** necessary to use a lens that specifically matches the type of text you are redacting. The `general` lens has been created to provide a balance between performance and accuracy, and it is the default lens used when no other lens is selected.

## What are Lenses?

A lens is essentially a pre-tuned model or set of parameters that guides the Philterd engine on how to interpret and process text. Different domains such as healthcare, legal, or news have unique linguistic patterns, terminologies, and entity types. A lens allows the system to be "aware" of these nuances, leading to more precise Named Entity Recognition (NER) and fewer false positives or negatives.

## Why Use a Lens?

Using a lens provides several key benefits:

*   **Improved Accuracy**: Lenses are optimized for specific vocabularies and sentence structures common in their respective fields.
*   **Reduced Over-Redaction**: By understanding the context, a lens can better distinguish between sensitive PII/PHI and non-sensitive industry-specific terms.
*   **Domain-Specific Optimization**: Some entities might be more critical in certain domains. Lenses ensure that the most relevant information for your industry is handled with the appropriate level of scrutiny.

## Available Lens Choices

Philterd Data Services currently offers the following lenses:

| Lens Name | Description | Recommended Use Case |
| :--- | :--- | :--- |
| **`general`** | General purpose lens for all types of text. | The default choice for diverse or non-specialized datasets. |
| **`healthcare`**| Lens tuned for healthcare and medical text. | Clinical notes, patient records, and medical research documents. |
| **`legal`** | Lens tuned for legal and judicial text. | Contracts, court filings, and legal correspondence. |
| **`news`** | Lens tuned for news and journalistic text. | Press releases, news articles, and public reports. |

## How to Use a Lens

Lenses are applied at the policy level. You can specify which lens to use by adding the `lens` field to your redaction policy's JSON configuration.

### Example Policy with a Lens

In this example, the `healthcare` lens is applied to a policy designed for medical records:

```json
{
  "version": "1.0",
  "name": "healthcare-redaction-policy",
  "description": "A policy optimized for healthcare data using the healthcare lens",
  "lens": "healthcare",
  "filters": {
    "PERSON": [
      {
        "strategy": "REDACT"
      }
    ],
    "DATE": [
      {
        "strategy": "REDACT"
      }
    ]
  }
}
```

If no `lens` is specified in the policy, Philterd defaults to the `general` lens.

## See Also

*   [Redaction Policy Syntax](policy_syntax.md)
*   [Redaction Policies](policies.md)
*   [Disambiguation](disambiguation.md)

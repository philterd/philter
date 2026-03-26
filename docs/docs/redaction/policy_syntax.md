# Redaction Policy Syntax Reference

**Important: Knowledge of the policy syntax is not necessary to use Philterd Data Services. Creating or editing policies by hand allows for powerful control over the redaction process, but it is not required.**

Philterd Data Services uses a powerful and flexible JSON-based syntax for defining redaction policies. This structure allows you to exercise precise control over how sensitive information is identified, classified, and subsequently protected within your documents and datasets.

This reference guide provides a deep dive into the individual components of a policy, the available filters, and the diverse processing strategies at your disposal.

If you need help creating a policy that meets your needs, please [contact us](../support.md).

## Core Policy Structure

A Philterd redaction policy is defined as a JSON object containing several mandatory and optional top-level fields. This structured approach ensures that your data protection rules are both machine-readable and easy to manage.

### Top-Level Fields

*   **`version`** (Text, Required): The version of the policy syntax being used. The current stable version is `1.0`.
*   **`name`** (Text, Required): A unique, descriptive name for the policy (e.g., `"Standard-HIPAA-Ruleset"`). The name is to allow you to quickly understand the policy's purpose.
*   **`description`** (Text, Optional): A brief explanation of the policy's purpose or the regulatory standard it is designed to meet. The description is to help you remember extra information about the policy.
*   **`notes`** (Text, Optional): Additional information about the policy that you would like to keep with the policy. Notes are useful to remember what the policy is for, why changes were made to the policy, or any other extra information that might be helpful.
*   **`filters`** (Object, Required): A mapping where each key is a PII/PHI type and the value is a list of redaction strategies to be applied to that type.
*   **`termsToIgnore`** (Array of Text, Optional): A list of specific terms that the system should never redact, even if they match a filter.
*   **`termsToAlwaysRedact`** (Array of Text, Optional): A list of specific terms that must be redacted every time they are encountered.
*   **`highlightChangesinWordDocuments`** (Boolean, Optional): Specifically for `.docx` files. If set to `true`, the redacted areas in the output document will be highlighted (usually in yellow) for easy visual identification.
*   **`turnOnRevisionsinWordDocuments`** (Boolean, Optional): Specifically for `.docx` files. If set to `true`, the platform will enable Microsoft Word's "Track Changes" (Revisions) feature, showing exactly what was removed and what it was replaced with.
*   **`disambiguationScope`** (Text, Optional): The scope of the disambiguation during redaction. Valid values are `Document` and `Context`. Defaults to `Document` if not specified.
*   **`lens`** (Text, Optional): The lens to apply during redaction. Valid values are `general`, `financial`, `healthcare`, `legal`, and `news`. Defaults to `general` if not specified. See [Redaction Lenses](lenses.md) for more details.

### Example Policy

The following example shows a policy that handles multiple data types with different strategies:

```json
{
  "version": "1.0",
  "name": "comprehensive-privacy-policy",
  "description": "A robust policy for general PII and PHI protection",
  "notes": "I created this policy to redact emails and truncate SSNs.",
  "filters": {
    "PERSON": [
      {
        "strategy": "REDACT",
        "simplifiedCondition": {
          "confidence": 90
        }
      }
    ],
    "EMAIL_ADDRESS": [
      {
        "strategy": "ANONYMIZE",
        "anonymizationMethod": "REALISTIC",
        "redactionScope": "Document"
      }
    ],
    "SSN": [
      {
        "strategy": "LAST_4"
      }
    ]
  },
  "termsToIgnore": ["Philterd", "Headquarters", "Global Sales"],
  "termsToAlwaysRedact": ["Confidential Project Phoenix", "Top Secret"],
  "highlightChangesinWordDocuments": true,
  "turnOnRevisionsinWordDocuments": false,
  "disambiguationScope": "Document"
}
```

## Supported PII/PHI Filters

Filters are the engines that identify specific categories of sensitive information. Philterd supports a wide array of filters, leveraging both pattern matching (RegEx) and advanced AI-based Named Entity Recognition (NER).

| Filter Key | Description | Detection Method |
| :--- | :--- | :--- |
| **`PERSON`** | Full names of individuals. | AI / NER |
| **`FIRST_NAME`** | Common first names. | Dictionary / Pattern |
| **`SURNAME`** | Common surnames/last names. | Dictionary / Pattern |
| **`EMAIL_ADDRESS`**| Electronic mail addresses. | Pattern |
| **`SSN`** | Social Security Numbers. | Pattern |
| **`AGE`** | References to a person's age. | AI / Pattern |
| **`PHONE_NUMBER`** | Telephone numbers (various formats). | Pattern |
| **`STREET_ADDRESS`** | Physical street addresses. | AI / NER |
| **`LOCATION_CITY`**| City names. | AI / NER |
| **`LOCATION_STATE`**| State or province names. | AI / NER |
| **`ZIP_CODE`** | Postal/Zip codes. | Pattern |
| **`DATE`** | Calendar dates. | AI / Pattern |
| **`CREDIT_CARD`** | Credit and debit card numbers. | Pattern |
| **`BANK_ROUTING_NUMBER`**| Financial institution routing codes. | Pattern |
| **`IP_ADDRESS`** | IPv4 and IPv6 addresses. | Pattern |
| **`URL`** | Website addresses and links. | Pattern |
| **`PASSPORT_NUMBER`**| National passport numbers. | Pattern |
| **`DRIVERS_LICENSE_NUMBER`**| Motor vehicle license numbers. | Pattern |
| **`VIN`** | Vehicle Identification Numbers. | Pattern |
| **`IDENTIFIER`** | Custom identifiers (MRNs, Employee IDs). | Pattern / AI |

## Redaction Strategies

A strategy determines the specific transformation applied to a piece of information once it has been identified by a filter.

*   **`REDACT`**: The most common strategy. It replaces the sensitive text with a descriptive label enclosed in brackets. For example, "John Doe" becomes `[PERSON]`.
*   **`ANONYMIZE`**: Replaces the identified text with a synthetically generated but realistic value of the same type. For example, one email address might be replaced with a different, fake email address.
    *   **`anonymizationMethod`** (Text, Optional): Determines the type of anonymization. Valid values are `REALISTIC` and `UUID`. Defaults to `UUID`.
        *   `REALISTIC`: Replaces the PII with a realistic value. For example, a Social Security Number will be replaced with a random number formatted as an SSN. Note that for some types of PII, the pool size of random replacement values may be limited. For instance, the pool of person's names is limited, while numbers for SSNs are virtually unlimited. When using `REALISTIC`, you want the pool size to be largre than the count of PII of each type in your document(s). For more information on pool sizes, please contact us.
        *   `UUID`: Replaces the PII with a random UUID.
    *   **`redactionScope`** (Text, Optional): The scope of the anonymization. Valid values are `Document` and `Context`. Defaults to `Document`. This value controls whether referential integrity is performed across the entire context or just within the current document.
*   **`LAST_4`**: Useful for identifiers like SSNs or bank accounts. It replaces all characters except for the final four. For example, "123-456-7890" becomes `*******7890`.
*   **`MASK`**: Replaces every character in the sensitive string with a specific masking character (typically `*` or `X`). Note that the length of the masking will be the same as the original token. For example, "George" will be masked as "******".
*   **`TRUNCATE_TO_YEAR`**: Specifically for dates. It removes the month and day, preserving only the year. "January 15, 2023" becomes `2023`.
*   **`SHIFT`**: For dates. It shifts the date forward or backward by a random number of days (useful for maintaining chronological relationships in datasets while protecting specific dates).
*   **`FPE` (Format-Preserving Encryption)**: Encrypts the sensitive data using a key while ensuring the output has the same format and length as the input. This is ideal for maintaining database schema compatibility.

## Confidence Thresholds (AI Filters Only)

For filters that utilize AI and machine learning (like `PERSON` or `STREET_ADDRESS`), you can specify a `confidence` threshold within the `simplifiedCondition` block. This value (0-100) represents the level of certainty the AI must have before it applies the redaction strategy.

*   **High Confidence (e.g., 95+)**: Minimizes "false positives" (redacting things that aren't actually PII) but may miss some actual sensitive information.
*   **Low Confidence (e.g., 70-80)**: Ensures more comprehensive redaction but increases the risk of "over-redaction."
*   **Default**: If not specified, the system typically defaults to a confidence score of **85**.

## Advanced Management of Terms

### Terms to Never Redact

The `termsToIgnore` list is critical for preventing the accidental redaction of non-sensitive information that may resemble PII. This is effectively a list of terms to **never redact**. Common use cases include:

*   Your organization's name or brand.
*   Publicly known office locations or city names that are central to the document's context.
*   Industry-specific terminology that might be misidentified as names or locations.

### Terms to Always Redact

The `termsToAlwaysRedact` list ensures that specific, high-risk terms are caught every time, regardless of the automated filters. This is effectively a list of terms to **always redact**. This is perfect for:

*   Sensitive project codenames (e.g., "Project X").
*   Specific internal identifiers that do not follow a standard pattern.
*   Any proprietary terms that must remain confidential.

## Implementation Guide

To implement a policy using this syntax:

1.  **Access the Dashboard**: Navigate to the [Redaction Policies](policies.md) section.
2.  **Enter the Advanced Editor**: Click the **Advanced Editor** button for your chosen policy.
3.  **Construct Your JSON**: Using the rules above, build your JSON configuration.
4.  **Save and Validate**: Click **Save**. The platform will validate your JSON structure.
5.  **Verify via Testing**: Always test your new policy on a representative sample document in the [Redacting Documents](redacting_documents.md) section to ensure it behaves as expected before wide-scale deployment.

## See Also

*   [Redaction Lenses](lenses.md)
*   [Redacting Documents](redacting_documents.md)
*   [Redaction Policies](policies.md)
*   [Redaction Contexts](contexts.md)
*   [Custom Lists](custom_lists.md)
*   [Disambiguation](disambiguation.md)

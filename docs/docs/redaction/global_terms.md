# Global Terms

Global terms are terms that are applied across all redaction policies for your account. They are useful for ensuring that specific terms are always redacted or never redacted, regardless of the individual policy settings.

Global terms can be managed in the Philterd Dashboard under the **Redaction Policies** view, in the **Global Terms** tab.

### Terms to Always Redact

Terms added to this list will always be redacted in your documents, even if they are not identified by any other filter in your active redaction policy. Terms can be single words or phrases. Terms can be added or removed at any time, but any previously redacted documents will not be affected.

- Enter each term on a new line.
- Terms are case-insensitive.
- Click **Save Terms** to apply your changes.

#### Fuzzy Matching

You can enable fuzzy matching for a term by appending `:fuzzy` to the end of the term. This is useful for redacting variations of a term or terms that might have spelling mistakes.

For example:

- `Philterd:fuzzy` will redact "Philterd", "Philter", "Philtred", etc.

### Terms to Never Redact

Terms added to this list will never be redacted, effectively acting as an allow-list (or ignore list) that overrides all other redaction filters. This is useful for protecting common names, places, or organization names that should remain visible in your documents. Terms can be single words or phrases. Terms can be added or removed at any time, but any previously redacted documents will not be affected.

- Enter each term on a new line.
- Terms are case-insensitive.
- Click **Save Terms** to apply your changes.

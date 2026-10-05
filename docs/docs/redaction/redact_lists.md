# Always/Never Redact Lists

The always-redact and never-redact lists are lists of terms that are applied across all redaction policies for your account. They are useful for ensuring that specific terms are always redacted or never redacted, regardless of the individual policy settings.

> **Scoped to your own account.** These lists apply across *all of your* redaction policies and contexts, but only within your own account. They are **not** shared with, or applied to, other users. Each user has their own independent always-redact and never-redact lists, and one user's lists never affect another user's redactions. Users are deactivated rather than deleted, and a deactivated user's lists are retained.

The always-redact and never-redact lists are managed with the [Always/Never Redact Lists API](../api_and_sdks/api/redact_lists_api.md).

### Terms to Always Redact

Terms added to this list will always be redacted in your documents, even if they are not identified by any other filter in your active redaction policy. Terms can be single words or phrases. Terms can be added or removed at any time, but any previously redacted documents will not be affected.

- Each term is one entry in the list (`alwaysRedact` or `neverRedact`).
- Terms are case-insensitive.
- Saving replaces the whole list, so send every term you want to keep.

#### Fuzzy Matching

You can enable fuzzy matching for a term by appending `:fuzzy` to the end of the term. This is useful for redacting variations of a term or terms that might have spelling mistakes.

For example:

- `Philterd:fuzzy` will redact "Philterd", "Philter", "Philtred", etc.

### Terms to Never Redact

Terms added to this list will never be redacted, effectively acting as an allow-list (or ignore list) that overrides all other redaction filters. This is useful for protecting common names, places, or organization names that should remain visible in your documents. Terms can be single words or phrases. Terms can be added or removed at any time, but any previously redacted documents will not be affected.

- Each term is one entry in the list (`alwaysRedact` or `neverRedact`).
- Terms are case-insensitive.
- Saving replaces the whole list, so send every term you want to keep.

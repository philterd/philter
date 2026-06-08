# Always/Never Redact Lists

The always-redact and never-redact lists are lists of terms that are applied across all redaction policies for your account. They are useful for ensuring that specific terms are always redacted or never redacted, regardless of the individual policy settings.

> **Scoped to your own account.** These lists apply across *all of your* redaction policies and contexts — but only within your own account. They are **not** shared with, or applied to, other users. Each user has their own independent always-redact and never-redact lists, and one user's lists never affect another user's redactions. When a user is deleted, their lists are deleted along with the rest of their data.

The always-redact and never-redact lists can be managed in the Philterd Dashboard under the **Always/Never Redact Lists** page, or through the [Always/Never Redact Lists API](../api_and_sdks/api/redact_lists_api.md).

### Terms to Always Redact

Terms added to this list will always be redacted in your documents, even if they are not identified by any other filter in your active redaction policy. Terms can be single words or phrases. Terms can be added or removed at any time, but any previously redacted documents will not be affected.

- Enter each term on a new line.
- Terms are case-insensitive.
- Click **Save Lists** to apply your changes.

#### Fuzzy Matching

You can enable fuzzy matching for a term by appending `:fuzzy` to the end of the term. This is useful for redacting variations of a term or terms that might have spelling mistakes.

For example:

- `Philterd:fuzzy` will redact "Philterd", "Philter", "Philtred", etc.

### Terms to Never Redact

Terms added to this list will never be redacted, effectively acting as an allow-list (or ignore list) that overrides all other redaction filters. This is useful for protecting common names, places, or organization names that should remain visible in your documents. Terms can be single words or phrases. Terms can be added or removed at any time, but any previously redacted documents will not be affected.

- Enter each term on a new line.
- Terms are case-insensitive.
- Click **Save Lists** to apply your changes.

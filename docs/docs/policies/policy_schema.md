# Policy Schema 1.3.0 Reference

This page documents the **Phileas redaction policy schema, version 1.3.0**: the complete JSON structure that defines a redaction policy. The schema is published at `https://www.philterd.ai/schemas/redaction-policy/1.3.0/schema.json`. Where the schema and the redaction engine differ, this page describes the engine's behavior.

> You do not need to know this schema to use Philter. Most users build policies with the hosted policy editor at [policies.philterd.ai](https://policies.philterd.ai). This reference is for authoring or reviewing policies by hand and for understanding every available option.

The version supported by a running Philter instance is reported by the [status endpoint](../api_and_sdks/api/filtering_api.md) in the `redactionPolicySchemaVersion` field.

## Top-level structure

A policy is a JSON object. Philter requires a non-empty `identifiers` object; all other top-level properties are optional. Philter does not validate a policy against the JSON schema: it checks that the policy parses and maps to the policy model, and ignores unknown properties.

```json
{
  "metadata": { },
  "config": { },
  "crypto": { },
  "fpe": { },
  "identifiers": { },
  "ignored": [ ],
  "ignoredPatterns": [ ],
  "graphical": { },
  "generators": { }
}
```

| Property | Type | Description |
|----------|------|-------------|
| `metadata` | object | Descriptive information carried with the policy, such as `description`. Additional properties are allowed. Has no effect on redaction. |
| `config` | object | Global processing settings (text splitting, PDF rendering, post-filters, analysis). |
| `crypto` | object | AES settings used by the `CRYPTO_REPLACE` strategy. |
| `fpe` | object | Format-preserving-encryption settings used by the `FPE_ENCRYPT_REPLACE` strategy. |
| `identifiers` | object | The PII/PHI types to detect and how to handle each. This is the core of a policy. |
| `ignored` | array | Named lists of terms to ignore globally. |
| `ignoredPatterns` | array | Named regex patterns to ignore globally. |
| `graphical` | object | Fixed bounding-box redaction for images and PDFs. |
| `generators` | object | Named replacement generators referenced by the `MAP_REPLACE` strategy's `generator` property (see [Generators](#generators)). |

## `config`

Global configuration settings.

| Property | Type | Description |
|----------|------|-------------|
| `splitting` | object | Whether and how to split input text before filtering. |
| `pdf` | object | PDF rendering settings for PDF redaction. |
| `postFilters` | object | Post-processing applied to redacted output. |
| `analysis` | object | Analysis feature settings. |

**`config.splitting`**

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | boolean | `false` | Enable text splitting. |
| `threshold` | integer | `10000` | Text of this many characters or more is split. |
| `method` | string | `newline` | Method used to split text: `newline`, `width`, or `characters`. |
| `overlap` | integer | `0` | Characters each piece shares with the end of the previous piece. See [Splitting Input Text](splitting_input_text.md). |

**`config.pdf`**

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `redactionColor` | string | `black` | Color of the box drawn over redacted text. |
| `showReplacement` | boolean | `false` | Render replacement text inside the redaction box. |
| `replacementFont` | string | `helvetica` | Font for replacement text. |
| `replacementMaxFontSize` | number | `12` | Maximum font size for replacement text. |
| `replacementFontColor` | string | | Color of replacement text. |
| `scale` | number | `0.25` | Scale factor for PDF rendering. |
| `dpi` | integer | `150` | DPI for PDF rendering. |
| `compressionQuality` | number (0.0–1.0) | `1.0` | JPEG compression quality for PDF output. |
| `preserveUnredactedPages` | boolean | `false` | Keep pages with no redactions in their original form. |

**`config.postFilters`**

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `removeTrailingPeriods` | boolean | `true` | Remove trailing periods from redacted output. |
| `removeTrailingSpaces` | boolean | `true` | Remove trailing spaces from redacted output. |
| `removeTrailingNewLines` | boolean | `true` | Remove trailing newlines from redacted output. |

**`config.analysis`**

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `identification` | boolean | `true` | Accepted for compatibility; has no effect. |
| `spanDisambiguation` | boolean | `true` | Resolve the type of an ambiguously typed span (for example SSN or phone number) from its context. When `false`, the policy skips this step. |

## `crypto`

Encryption settings used by the `CRYPTO_REPLACE` strategy. Philter encrypts using AES in GCM mode (authenticated encryption) with a random nonce generated per value. Only `key` is required. The `key` may be prefixed with `env:` to read it from an environment variable (for example, `env:CRYPTO_KEY`) so the secret is not stored in the policy.

| Property | Type | Description |
|----------|------|-------------|
| `key` | string | Hex-encoded AES key (64 hex characters for a 256-bit key, 32 for 128-bit). Prefix with `env:` to read it from an environment variable; a key written here is stored in the clear, see [what is encrypted at rest](../database.md#what-is-encrypted-at-rest). |

## `fpe`

Format-preserving encryption settings used by the `FPE_ENCRYPT_REPLACE` strategy. Optional: if omitted, Philter applies a stable per-user key it manages automatically, so the strategy works with no configuration. Supply this object to use your own key instead. The encryption is deterministic and reversible for a given key and tweak. Either value may be prefixed with `env:` to read it from an environment variable so the secret is not stored in the policy.

| Property | Type | Description |
|----------|------|-------------|
| `key` | string | FPE encryption key (hex, 128/192/256-bit). Prefix with `env:` to read it from an environment variable; a key written here is stored in the clear, see [what is encrypted at rest](../database.md#what-is-encrypted-at-rest). |
| `tweak` | string | FPE tweak value (hex, 56- or 64-bit). May be prefixed with `env:`. |

## `identifiers`

The `identifiers` object selects which types of sensitive information to detect and how to handle each. Each property is an individual filter. Every filter shares a set of common properties (below) and carries a list of *filter strategies* that determine how matches are transformed.

### Available filters

| Property | Detects |
|----------|---------|
| `age` | Age values (for example, "35 years old", "age 42"). |
| `bankRoutingNumber` | Bank routing numbers. |
| `bitcoinAddress` | Bitcoin wallet addresses. |
| `city` | City names (dictionary). |
| `county` | County names (dictionary). |
| `creditCard` | Credit card numbers. |
| `currency` | Currency values (for example, "$100.00", "50 EUR"). |
| `date` | Dates in various formats. |
| `dictionaries` | Array of custom dictionary filters (user-defined term sets). |
| `driversLicense` | Driver's license numbers. |
| `ein` | U.S. Employer Identification Numbers. |
| `emailAddress` | Email addresses. |
| `firstName` | First names (dictionary). |
| `hospital` | Hospital names (dictionary). |
| `ibanCode` | International Bank Account Numbers (IBAN). |
| `identifiers` | Array of custom regex-based identifier filters. |
| `ipAddress` | IPv4 and IPv6 addresses. |
| `macAddress` | MAC addresses. |
| `passportNumber` | Passport numbers. |
| `person` | **Deprecated.** Use `pheyes` instead. |
| `pheyes` | Array of PhEye AI-based entity detection filters. |
| `phoneNumber` | Phone numbers. |
| `phoneNumberExtension` | Phone number extensions (for example, "ext. 1234"). |
| `sections` | Array of section filters (start/end regex pairs). |
| `ssn` | U.S. Social Security Numbers. |
| `state` | U.S. state names (dictionary). |
| `stateAbbreviation` | U.S. state abbreviations (for example, "CA", "NY"). |
| `streetAddress` | Street addresses. |
| `surname` | Surnames (dictionary). |
| `trackingNumber` | Shipping tracking numbers (UPS, FedEx, USPS). |
| `url` | URLs. |
| `vin` | Vehicle Identification Numbers. |
| `zipCode` | U.S. ZIP codes (5-digit and ZIP+4). |

### Common filter properties

Every filter accepts the following properties in addition to its strategies list.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | boolean | `true` | Whether this filter is active. |
| `ignored` | array of strings | | Specific values this filter should ignore. |
| `ignoredFiles` | array of strings | | Paths to files of values to ignore (one per line). |
| `ignoredPatterns` | array | | Regex patterns whose matches this filter ignores. |
| `windowSize` | integer | | Number of surrounding tokens to consider as context. |
| `priority` | integer | | Priority relative to other filters. When spans overlap, the longer span wins, then the higher confidence, then the higher priority. |
| `id` | string | | Identifier for the filter, used in logs and diagnostics. No effect on redaction. |

Each filter holds its strategies under a filter-specific key. The key is the filter name plus `FilterStrategies`, for example `ssn` uses `ssnFilterStrategies`, `emailAddress` uses `emailAddressFilterStrategies`, `age` uses `ageFilterStrategies`, and so on.

```json
{
  "identifiers": {
    "ssn": {
      "ssnFilterStrategies": [
        { "strategy": "LAST_4" }
      ]
    },
    "emailAddress": {
      "emailAddressFilterStrategies": [
        { "strategy": "REDACT" }
      ]
    }
  }
}
```

### Filters with extra properties

Most filters only add their strategies list to the common properties. The following filters have additional, filter-specific properties.

**`dictionaries` (custom dictionary filter)** detects terms from a user-defined list. Uses `customFilterStrategies`.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `classification` | string | | Classification label assigned to matches. |
| `terms` | array of strings | | Terms to detect. |
| `files` | array of strings | | Paths to files of terms to detect. |
| `fuzzy` | boolean | `false` | Enable approximate matching. |
| `sensitivity` | string | `medium` | Fuzzy-match strictness: `auto`, `off`, `low`, `medium`, `high`. Applies only when `fuzzy` is `true`. The bundled schema lists a default of `off`; the engine's default is `medium`. |
| `capitalized` | boolean | `false` | Only match capitalized variants. |

**`identifiers` (custom identifier filter)** detects matches of a custom regex. Uses `identifierFilterStrategies`.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `pattern` | string | `\b[A-Z0-9_-]{6,}\b` | Java-compatible regex to match. |
| `groupNumber` | integer | `0` | Capture group to extract (0 = whole match). |
| `caseSensitive` | boolean | `true` | Whether matching is case-sensitive. |
| `classification` | string | `custom-identifier` | Classification label assigned to matches. |
| `validator` | string or object | | A built-in validator a match must pass to be kept: `luhn`, `bic-structural`, `de-personalausweis`, `de-steuerid`, `mod11`, `mod97`, `mod23-letter`, or `es-cif`. Either the name as a string or `{"name": "...", "params": { }}`. An unknown name is a policy error. |

**`sections` (section filter)** redacts everything between two regex markers. Uses `sectionFilterStrategies`.

| Property | Type | Description |
|----------|------|-------------|
| `startPattern` | string | Java regex marking the start of a section to redact. |
| `endPattern` | string | Java regex marking the end of a section to redact. |

**`pheyes` (PhEye AI filters)** detect entities using a remote PhEye AI model. PhEye filters use `phEyeFilterStrategies` and add:

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `phEyeConfiguration` | object | | Connection settings for the PhEye service (see below). |
| `removePunctuation` | boolean | `false` | Strip punctuation before sending text to the model. |
| `thresholds` | object | | Per-label confidence floors (0.0–1.0); detections below are discarded. |

`phEyeConfiguration`:

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `endpoint` | string (URI) | `PHEYE_ENDPOINT` environment variable, else `http://philter-ph-eye-1:5000/` | URL of the PhEye service. |
| `bearerToken` | string | | Bearer token for the PhEye service. |
| `timeout` | integer | `600` | Request timeout in seconds. |
| `maxIdleConnections` | integer | `30` | Maximum idle HTTP connections. |
| `labels` | array of strings | | Entity labels to detect (for example, "PER", "LOC", "ORG"). |
| `modelPath` | string | | Path to a local GLiNER model directory. When set, entities are detected on-device instead of through `endpoint`. |

## Filter strategies

A filter strategy determines the transformation applied to each match. A filter's strategies array is evaluated in order; the first strategy whose `condition` is satisfied, or that has no `condition`, is applied. Only that one strategy is applied. If no strategy's `condition` is satisfied, the value is left unchanged; end the array with a strategy that has no `condition` to transform every match. A strategy a filter does not support falls back to `REDACT`.

### Common strategy properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `id` | string (uuid) | auto | Unique id for this strategy instance. |
| `strategy` | string (enum) | `REDACT` | The transformation to apply (see below). |
| `redactionFormat` | string | `{{{REDACTED-%t}}}` | Format for `REDACT`. Placeholders: `%t` filter type, `%v` original value, `%l` label. |
| `replacementScope` | string | `DOCUMENT` | Consistency scope: `DOCUMENT` (each document pseudonymized independently) or `CONTEXT` (the same value gets the same replacement across documents in a context). See [Consistent Pseudonymization](../redaction/replacement_scope.md). |
| `staticReplacement` | string | | Replacement text for `STATIC_REPLACE`. |
| `maskCharacter` | string (1 char) | `*` | Character used by `MASK`. |
| `maskLength` | string | `SAME` | Mask length; `SAME` preserves the original length, or a fixed number. |
| `truncateLeaveCharacters` | integer | `4` | Characters left visible by `TRUNCATE` (minimum `1`; `1` to `4` for the zip code filter). |
| `truncateCharacter` | string (1 char) | `*` | Character used to replace truncated portions. |
| `truncateDirection` | string | `LEADING` | Which end to truncate: `LEADING` or `TRAILING`. |
| `condition` | string | | Expression that must hold for this strategy to apply (see below). |
| `salt` | boolean | `false` | Add a random salt for `HASH_SHA256_REPLACE`. |
| `anonymizationMethod` | string | `REALISTIC` | Method for `RANDOM_REPLACE`: `REALISTIC`, `FROM_LIST`, or `UUID`. |
| `anonymizationCandidates` | array of strings | | Candidate values for the `FROM_LIST` method. |
| `mappings` | object | | `MAP_REPLACE` lookup table of value to replacement. Takes precedence over `mappingFiles`. |
| `mappingFiles` | array of strings | | `MAP_REPLACE` tab-delimited files of value and replacement, one pair per line. |
| `caseSensitive` | boolean | `false` | Whether `MAP_REPLACE` lookup keys are matched case-sensitively. |
| `generator` | string | | Name of a top-level generator `MAP_REPLACE` calls for a value not in the lookup table. |
| `fallbackStrategy` | string | `REDACT` | Strategy `MAP_REPLACE` applies when no mapping or accepted generated value exists. |
| `color` | string | | Color of the redaction box drawn for this strategy's spans in a PDF. Overrides `config.pdf.redactionColor`. No effect on text. |

### Strategy values

| `strategy` | Effect |
|------------|--------|
| `REDACT` | Replace the match with a label (see `redactionFormat`). |
| `RANDOM_REPLACE` | Replace with a generated value (see `anonymizationMethod`). |
| `STATIC_REPLACE` | Replace with a fixed string (`staticReplacement`). |
| `CRYPTO_REPLACE` | Replace with the AES-encrypted value (requires top-level `crypto`). |
| `FPE_ENCRYPT_REPLACE` | Replace with a format-preserving-encrypted value. In Philter the key is managed per account automatically; see [`fpe`](#fpe). |
| `HASH_SHA256_REPLACE` | Replace with a SHA-256 hash (optionally salted). |
| `LAST_4` | Keep only the last four characters. |
| `MASK` | Replace each character with `maskCharacter`. |
| `TRUNCATE` | Keep `truncateLeaveCharacters` characters from one end. |
| `ABBREVIATE` | Replace with the uppercase initials of the matched value's words. |
| `MAP_REPLACE` | Replace using a lookup table, an optional generator, and a fallback strategy. See [MAP_REPLACE](filter_strategies.md#the-map_replace-filter-strategy). |

Not every filter supports every strategy. The `date` and `zipCode` filters do not support `FPE_ENCRYPT_REPLACE`, `LAST_4`, `ABBREVIATE`, or `MAP_REPLACE`; PhEye filters do not support `LAST_4` or `MAP_REPLACE`. The `zipCode` filter adds `ZERO_LEADING`.

### Date-only strategies

The `date` filter supports `REDACT`, `RANDOM_REPLACE`, `STATIC_REPLACE`, `CRYPTO_REPLACE`, `HASH_SHA256_REPLACE`, `MASK`, and `TRUNCATE`, plus three date-specific strategies, configured in `dateFilterStrategies`:

| `strategy` | Effect |
|------------|--------|
| `TRUNCATE_TO_YEAR` | Keep only the year. |
| `SHIFT` | Shift the date by a fixed or random amount. |
| `RELATIVE` | Replace with a value relative to the date. |

Additional date strategy properties:

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `shiftRandom` | boolean | `false` | Shift by a random amount. |
| `shiftDays` | integer | `0` | Days to shift. |
| `shiftMonths` | integer | `0` | Months to shift. |
| `shiftYears` | integer | `0` | Years to shift. |
| `futureDates` | boolean | `false` | For `RELATIVE`, describe a future date relatively (for example "in 3 months") instead of redacting it. |

### Conditions

The `condition` property is an expression that gates whether a strategy applies. It supports the fields `token`, `context`, `confidence`, and `population` (zip code filter), the operators `startswith`, `==`, `!=`, `>`, `<`, `>=`, `<=`, `is`, and `is not`, and multiple clauses joined with `and` or `AND`. String values must be in double quotes. For `token`, the engine evaluates `==` (case-insensitive), `startswith`, and, on the date filter, `is` and `is not` with `"birthdate"`, `"deathdate"`, or `"birthdate or deathdate"`. `context` supports `==` and `!=`. The grammar also accepts a `type` field (`PER` or `LOC`), but no filter evaluates it, so a clause on `type` is never satisfied.

Examples:

* `confidence > 0.9`
* `token startswith "5"`
* `population < 4500`
* `token is "birthdate"` (date filter)
* `token is "birthdate or deathdate"` (date filter)

## `ignored` and `ignoredPatterns`

Top-level lists that apply across all filters.

**`ignored`** is an array of named ignore lists:

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `name` | string | | Name of the ignore list. |
| `terms` | array of strings | | Terms to ignore. |
| `files` | array of strings | | Paths to files of terms to ignore. |
| `caseSensitive` | boolean | `false` | Whether matching is case-sensitive. |

**`ignoredPatterns`** is an array of named regex patterns:

| Property | Type | Description |
|----------|------|-------------|
| `name` | string | Name of the ignored pattern. |
| `pattern` | string | Java regex; matches are excluded from filtering. |

## Generators

`generators` is an object mapping a generator name to its definition. A `MAP_REPLACE` strategy references a generator by name.

| Property | Type | Description |
|----------|------|-------------|
| `type` | string | Generator backend. The only supported value is `ollama`, which calls an Ollama-compatible `/api/generate` endpoint. A generator with another type is ignored. |
| `endpoint` | string | Base URL of the generator endpoint, for example `http://localhost:11434`. |
| `model` | string | Model name the endpoint uses. |
| `prompt` | string | Prompt template. `{{token}}` is replaced with the detected value and `{{label}}` with its label. |
| `timeoutMs` | integer | Request timeout in milliseconds. |

## `graphical`

Fixed bounding-box redaction for images and PDFs.

`graphical.boundingBoxes` is an array of boxes:

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `color` | string | | Fill color for the redaction box. |
| `x`, `y` | number | | Coordinates of the top-left corner. |
| `w`, `h` | number | | Width and height of the box. |
| `page` | integer | `1` | Page number (1-based) for PDFs. |
| `enabled` | boolean | `true` | Whether this box is active. |
| `ignored` | array of strings | | Specific values to ignore. |
| `ignoredFiles` | array of strings | | Paths to files of values to ignore. |
| `ignoredPatterns` | array | | Regex patterns whose matches are ignored. |
| `windowSize` | integer | | Context window size. |
| `priority` | integer | | Priority relative to other filters. |

## Complete example

A small but complete policy that redacts SSNs to their last four digits, replaces email addresses with realistic fakes consistently within a context, truncates dates to the year, and ignores the organization name:

```json
{
  "config": {
    "splitting": { "enabled": false },
    "pdf": { "redactionColor": "black" }
  },
  "identifiers": {
    "ssn": {
      "ssnFilterStrategies": [
        { "strategy": "LAST_4" }
      ]
    },
    "emailAddress": {
      "emailAddressFilterStrategies": [
        {
          "strategy": "RANDOM_REPLACE",
          "anonymizationMethod": "REALISTIC",
          "replacementScope": "CONTEXT"
        }
      ]
    },
    "date": {
      "dateFilterStrategies": [
        { "strategy": "TRUNCATE_TO_YEAR" }
      ]
    },
    "pheyes": [
      {
        "phEyeFilterStrategies": [
          { "strategy": "REDACT", "condition": "confidence > 0.9" }
        ],
        "phEyeConfiguration": { "endpoint": "http://ph-eye:5000" },
        "thresholds": { "PER": 0.8 }
      }
    ]
  },
  "ignored": [
    { "name": "org-terms", "terms": ["Philterd", "Acme Corp"] }
  ]
}
```

## See also

* [Filter Strategies](filter_strategies.md)
* [Filters](filters.md)
* [Redaction Policies](../redaction/policies.md)

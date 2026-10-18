# Person's Names (NER)

## Filter

This filter identifies person's names in text using a PhEye model. It is configured as an entry in the `pheyes` array. The earlier single-object `person` key is deprecated but still accepted, with the same properties.

### Required Parameters

This filter has no required parameters.

### Optional Parameters

| Parameter                   | Description                                                      | Default Value |
| --------------------------- | ---------------------------------------------------------------- | ------------- |
| `phEyeConfiguration`        | Connection settings for the PhEye service, or a `modelPath` naming a local model to run in-process (see the [Models API](../../../api_and_sdks/api/models_api.md)). A policy may name only an endpoint the deployment allows, and may not set a `bearerToken`; see [Redaction services](../../../settings.md#redaction-services) and the [Policy Schema](../../policy_schema.md#filters-with-extra-properties). | None |
| `removePunctuation`         | When set to true, punctuation will be removed prior to analysis. | `false`       |
| `phEyeFilterStrategies`     | A list of filter strategies.                                     | None          |
| `enabled`                   | When set to false, the filter will be disabled and not applied   | `true`        |
| `ignored`                   | A list of terms to be ignored by the filter.                     | None          |

### Filter Strategies

The filter may have zero or more filter strategies. When no filter strategy is given the default strategy of `REDACT` is used. When multiple filter strategies are given, they are evaluated in the order listed and only the first one whose condition is satisfied, or that has no condition, is applied. See [Filter Strategies](../../filter_strategies.md) for details.

| Strategy              | Description                                               |
| --------------------- | --------------------------------------------------------- |
| `REDACT`              | Replace the sensitive text with a placeholder.            |
| `RANDOM_REPLACE`      | Replace the sensitive text with a similar, random value.  |
| `STATIC_REPLACE`      | Replace the sensitive text with a given value.            |
| `CRYPTO_REPLACE`      | Replace the sensitive text with its encrypted value.      |
| `HASH_SHA256_REPLACE` | Replace the sensitive text with its SHA256 hash value.    |
| `ABBREVIATE`          | Replace the sensitive text with the initials of the text. |

### Conditions

Each filter strategy may have one condition. See [Conditions](../../filter_strategies.md#filter-strategy-conditions) for details.

| Conditional  | Description                                                              | Operators                          |
| ------------ | ------------------------------------------------------------------------ | ---------------------------------- |
| `TOKEN`      | Compares the value of the sensitive text.                                | `==`, `!=`, `startswith`           |
| `CONTEXT`    | Compares the filtering context.                                          | `==` , `!=`                        |
| `CONFIDENCE` | Compares the confidence in the sensitive text against a threshold value. | `<` , `<=`, `>` , `>=`, `==`, `!=` |

## Long Text

A PhEye model examines at most a fixed number of words in one call, its `max_len` (512 for the `ph-eye-pii-en-*` models), and ignores the rest. The filter divides longer text into overlapping windows that each fit, detects in each, and reports every name once, so a name anywhere in the text is examined. Words are counted as the model counts them, so each punctuation mark is a word. Text that fits is sent in one call.

The window size comes from the model: a model run in-process (`modelPath`) has its `max_len` read from its `gliner_config.json`, and the PhEye service's limit is read from its `/model` endpoint. A PhEye service without that endpoint, before ph-eye 1.4.0, is sent windows of 256 words. Windows overlap so that a name at a window's edge is found whole. No policy setting is needed, and [text splitting](../../splitting_input_text.md) is not required for this filter.

## Example Policy

```
{
   "identifiers": {
      "pheyes": [
         {
            "phEyeFilterStrategies": [
               {
                  "strategy": "REDACT",
                  "redactionFormat": "{{{REDACTED-%t}}}"
               }
            ]
         }
      ]
   }
}
```

# Surnames

## Filter

This filter identifies common surnames as identified by the US census in text.

### Required Parameters

This filter has no required parameters.

### Optional Parameters

| Parameter                 | Description                                                                                                                           | Default Value |
| ------------------------- | ------------------------------------------------------------------------------------------------------------------------------------- | ------------- |
| `sensitivity`             | Controls the "fuzziness" of allowed values to account for misspellings and derivations. Valid values are `low`, `medium`, and `high`. | `medium`      |
| `surnameFilterStrategies` | A list of filter strategies.                                                                                                          | None          |
| `enabled`                 | When set to false, the filter will be disabled and not applied                                                                        | `true`        |
| `ignored`                 | A list of terms to be ignored by the filter.                                                                                          | None          |

### Filter Strategies

The filter may have zero or more filter strategies. When no filter strategy is given the default strategy of `REDACT` is used. When multiple filter strategies are given the filter strategies will be applied in as they are listed. See [Filter Strategies](../../filter_strategies.md) for details.

| Strategy              | Description                                              |
| --------------------- | -------------------------------------------------------- |
| `REDACT`              | Replace the sensitive text with a placeholder.           |
| `RANDOM_REPLACE`      | Replace the sensitive text with a similar, random value. |
| `STATIC_REPLACE`      | Replace the sensitive text with a given value.           |
| `CRYPTO_REPLACE`      | Replace the sensitive text with its encrypted value.     |
| `HASH_SHA256_REPLACE` | Replace the sensitive text with its SHA256 hash value.   |

### Conditions

Each filter strategy may have one condition. See [Conditions](../../filter_strategies.md#filter-strategy-conditions) for details.

| Conditional  | Description                                                              | Operators                          |
| ------------ | ------------------------------------------------------------------------ | ---------------------------------- |
| `TOKEN`      | Compares the value of the sensitive text.                                | `==` , `!=`                        |
| `CONTEXT`    | Compares the filtering context.                                          | `==` , `!=`                        |
| `CONFIDENCE` | Compares the confidence in the sensitive text against a threshold value. | `<` , `<=`, `>` , `>=`, `==`, `!=` |

## Example Policy

```
{
   "name": "surnames-example",
   "identifiers": {
      "surname": {
         "surnameFilterStrategies": [
            {
               "strategy": "REDACT",
               "redactionFormat": "{{{REDACTED-%t}}}"
            }
         ]
      }
   }
}
```

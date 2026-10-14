# Dictionary

## Filter

This filter identifies custom text based on a given dictionary.

### Required Parameters

At least one of `terms` or `files` must be provided.

| Parameter | Description                                    | Default Value |
| --------- | ---------------------------------------------- | ------------- |
| `terms`   | A list of terms in the dictionary.             | None          |
| `files`   | A list of files containing terms one per line. A path must be inside a directory in `POLICY_FILES_DIRECTORIES`; see [Policy files](../../../settings.md#policy-files). | None          |

### Optional Parameters

| Parameter        | Description                                                                                                                                                                                                                      | Default Value         |
| ---------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------- |
| `customFilterStrategies` | A list of filter strategies. | None |
| `enabled`        | When set to false, the filter will be disabled and not applied                                                                                                                                                                   | `true`                |
| `ignored`        | A list of terms to be ignored by the filter.                                                                                                                                                                                     | None                  |
| `fuzzy`          | When set to true, the dictionary will employ fuzzy comparisons. Use the `sensitivity` parameter to control the level of fuzziness. Setting this value to false will disable fuzziness and provide a higher level of performance. | `false`               |
| `classification` | Used to apply an arbitrary label to the identifier, such as "patient-id", or "account-number."                                                                                                                                   | None                  |
| `sensitivity` | How close a word must be to a term to be reported when `fuzzy` is `true`, measured in edits (a character inserted, removed, or changed). `high` reports only exact matches, `medium` allows one edit, and `low` allows up to two. `off` reports only exact matches. `auto` allows no edits for a word under five characters, one edit for a word under nine, and two for a longer word, counts two swapped neighboring letters as one edit (so `Smiht` is one edit from `Smith`), and reports a fuzzy match only when it begins with a capital letter, which helps keep common short words such as `and` and `the` from matching names and places. Words shorter than three characters, not counting punctuation around them, are not compared. Any other value refuses the policy when it is saved, even when `fuzzy` is `false`. | `off` |
| `capitalized`    | When set to true, a match is reported only when its first letter is uppercase, so a term that is also an ordinary word is not reported when written in lowercase. Applies only when `fuzzy` is set to `true`; with exact matching it has no effect. | `false` |

### Filter Strategies

The filter may have zero or more filter strategies. When no filter strategy is given the default strategy of `REDACT` is used. When multiple filter strategies are given, they are evaluated in the order listed and only the first one whose condition is satisfied, or that has no condition, is applied. See [Filter Strategies ](../../filter_strategies.md)for details.

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
| `TOKEN`      | Compares the value of the sensitive text.                                | `==`, `!=`, `startswith`           |
| `CONTEXT`    | Compares the filtering context.                                          | `==` , `!=`                        |
| `CONFIDENCE` | Compares the confidence in the sensitive text against a threshold value. | `<` , `<=`, `>` , `>=`, `==`, `!=` |

## Example Policy

```
{
   "identifiers": {
      "dictionaries": [
         {
            "terms": ["john", "jane", "doe"],
            "files": ["/opt/philter/policy-files/dictionary.txt"],
            "fuzzy": true,
            "sensitivity": "medium",
            "customFilterStrategies": [
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

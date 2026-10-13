# Hospitals

## Filter

This filter identifies US hospitals in text.

### Required Parameters

This filter has no required parameters.

### Optional Parameters

| Parameter                  | Description                                                                                                                           | Default Value |
| -------------------------- | ------------------------------------------------------------------------------------------------------------------------------------- | ------------- |
| `hospitalFilterStrategies` | A list of filter strategies.                                                                                                          | None          |
| `fuzzy`                    | When set to true, matching is fuzzy, using `sensitivity` to control the fuzziness. When false, terms are matched exactly, ignoring case. | `false`       |
| `sensitivity` | How close a word must be to a term to be reported when `fuzzy` is `true`, measured in edits (a character inserted, removed, or changed). `high` reports only exact matches, `medium` allows one edit, and `low` allows up to two. `off` reports only exact matches. `auto` allows no edits for a word under five characters, one edit for a word under nine, and two for a longer word, counts two swapped neighboring letters as one edit (so `Smiht` is one edit from `Smith`), and reports a fuzzy match only when it begins with a capital letter, which helps keep common short words such as `and` and `the` from matching names and places. Words shorter than three characters, not counting punctuation around them, are not compared. Any other value refuses the policy when it is saved, even when `fuzzy` is `false`. | `auto` |
| `terms` | A list of terms to detect in place of the list bundled with Philter. When given, only these terms are detected and the bundled list is not used; an empty list uses the bundled list. They are matched the way `fuzzy` says: exactly, ignoring case, or fuzzily. | None (the bundled list) |
| `capitalized` | When set to true, a match is reported only when its first letter is uppercase, so a term that is also an ordinary word is not reported when written in lowercase. Applies only when `fuzzy` is set to `true`; with exact matching it has no effect, and a match is reported whatever its case. | `false` |

### Filter Strategies

The filter may have zero or more filter strategies. When no filter strategy is given the default strategy of `REDACT` is used. When multiple filter strategies are given, they are evaluated in the order listed and only the first one whose condition is satisfied, or that has no condition, is applied. See [Filter Strategies](../../filter_strategies.md) for details.

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
      "hospital": {
         "fuzzy": true,
         "sensitivity": "medium",
         "hospitalFilterStrategies": [
            {
               "strategy": "REDACT",
               "redactionFormat": "{{{REDACTED-%t}}}"
            }
         ]
      }
   }
}
```

### Using your own terms

To detect only your own list of terms, give them in `terms`. With `fuzzy` set to `true`, close misspellings of a term can also be detected, as `sensitivity` allows; with `capitalized` also set to `true`, a term written in lowercase is not reported:

```
{
   "identifiers": {
      "hospital": {
         "terms": ["Mercy General", "St. Jude"],
         "fuzzy": true,
         "capitalized": true,
         "hospitalFilterStrategies": [
            {
               "strategy": "REDACT",
               "redactionFormat": "{{{REDACTED-%t}}}"
            }
         ]
      }
   }
}
```

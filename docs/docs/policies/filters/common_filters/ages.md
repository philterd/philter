# Ages

## Filter

This filter identifies ages such as `3.5 years old` in text.

### Required Parameters

This filter has no required parameters.

### Optional Parameters

| Parameter             | Description                                                    | Default Value |
| --------------------- | -------------------------------------------------------------- | ------------- |
| `ageFilterStrategies` | A list of filter strategies.                                   | None          |
| `enabled`             | When set to false, the filter will be disabled and not applied | `true`        |
| `ignored`             | A list of terms to be ignored by the filter.                   | None          |

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

Each filter strategy may have one condition. The strategy is only applied when the condition is satisfied. See [Conditions](../../filter_strategies.md#filter-strategy-conditions) for details.

| Conditional  | Description                                                              | Operators                          |
| ------------ | ------------------------------------------------------------------------ | ---------------------------------- |
| `TOKEN`      | Compares the value of the sensitive text.                                | `==`, `!=`, `startswith`           |
| `CONTEXT`    | Compares the filtering context.                                          | `==` , `!=`                        |
| `CONFIDENCE` | Compares the confidence in the sensitive text against a threshold value. | `<` , `<=`, `>` , `>=`, `==`, `!=` |
| `TOKEN` with a number | Compares the age in years with a number, as in `token > 89`. See [Comparing the Age](#comparing-the-age). | `<` , `<=`, `>` , `>=`, `==`, `!=` |

### Comparing the Age

A condition compares the age with a number when the number is written without quotes, as in `token > 89`. Only the
age filter accepts a number for `token`; on any other filter such a condition refuses the policy when it is saved. A
quoted value, as in `token == "47 years old"`, is still compared with the identified text.

The age compared is the age in years that the identified text gives:

* The number written with digits is used, so `47 years old`, `47-year-old`, `47yo`, `61 y/o`, `Age: 47`, `aged 47`,
  and `age of 47` each compare as 47. A decimal is kept: `3.5 years old` satisfies `token > 3` but not `token == 3`.
* A spelled-out age compares as its number, so `thirty-five years old` compares as 35 and
  `one hundred and two years old` as 102.
* An age after an `age` keyword ends at the number, so in `aged 18 months` the identified text is `aged 18`. When the
  word after it is `months`, `month`, `mos`, `mo`, `mths`, or `mth`, the number is read as months, each a twelfth of a
  year; `weeks`, `week`, `wks`, or `wk` as weeks of 7 days; and `days` or `day` as days, with a year of 365 days. So
  `aged 18 months` compares as 1.5. The word is read from the span's window, so with a `windowSize` of `0` these ages
  compare as years. The filter does not identify an age written with months, weeks, or days and no keyword, such as
  `18 months old`.
* An identified age whose number cannot be read satisfies no comparison, so its strategy is skipped.

HIPAA Safe Harbor (45 CFR 164.514(b)(2)(i)(C)) requires ages over 89 to be aggregated into a single category of 90 or
older. This policy replaces those ages with `90 or older`. Because its only strategy has a condition, younger ages are
left in the text:

```
{
   "identifiers": {
      "age": {
         "ageFilterStrategies": [
            {
               "condition": "token > 89",
               "strategy": "STATIC_REPLACE",
               "staticReplacement": "90 or older"
            }
         ]
      }
   }
}
```

With this policy, `The patient is 93 years old.` becomes `The patient is 90 or older.`, and
`The patient is 72 years old.` is unchanged. The replacement takes the place of the whole identified text, including an
`age` keyword, so `Age: 93` becomes `90 or older`. To redact the younger ages instead, add a strategy with no condition
after this one.

## Example Policy

```
{
   "identifiers": {
      "age": {
         "ageFilterStrategies": [
            {
               "strategy": "REDACT",
               "redactionFormat": "{{{REDACTED-%t}}}"
            }
         ]
      }
   }
}
```

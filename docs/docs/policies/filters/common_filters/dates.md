# Dates

## Filter

This filter identifies dates such as `May 22, 2014` in text. The supported date formats are:

| Format        | Example                   |
| ------------- | ------------------------- |
| yyyy-MM-d     | 2020-05-10                |
| MM-dd-yyyy    | 05-10-2020                |
| M-d-y         | 5-10-2020                 |
| MMM dd        | May 5 or May 05           |
| MMMM dd, yyyy | May 5, 2020 or May 5 2020 |

### Required Parameters

This filter has no required parameters.

### Optional Parameters

| Parameter              | Description                                                    | Default Value |
| ---------------------- | -------------------------------------------------------------- | ------------- |
| `dateFilterStrategies` | A list of filter strategies.                                   | None          |
| `enabled`              | When set to false, the filter will be disabled and not applied | `true`        |
| `ignored`              | A list of terms to be ignored by the filter.                   | None          |
| `onlyValidDates`       | When set to true, only valid dates will be filtered.           | `false`       |

### Filter Strategies

The filter may have zero or more filter strategies. When no filter strategy is given the default strategy of `REDACT` is used. When multiple filter strategies are given, they are evaluated in the order listed and only the first one whose condition is satisfied, or that has no condition, is applied. See [Filter Strategies](../../filter_strategies.md) for details.

| Strategy              | Description                                                   |
| --------------------- | ------------------------------------------------------------- |
| `REDACT`              | Replace the sensitive text with a placeholder.                |
| `RANDOM_REPLACE`      | Replace the sensitive text with a similar, random value.      |
| `STATIC_REPLACE`      | Replace the sensitive text with a given value.                |
| `CRYPTO_REPLACE`      | Replace the sensitive text with its encrypted value.          |
| `HASH_SHA256_REPLACE` | Replace the sensitive text with its SHA256 hash value.        |
| `MASK`                | Replace the sensitive text with a repeated mask character.    |
| `TRUNCATE`            | Keep some characters of the sensitive text and mask the rest. |
| `TRUNCATE_TO_YEAR`    | Replace the date with its year.                               |
| `SHIFT`               | Shift the date by a number of months, days, and/or years, or by a random amount with `shiftRandom`. |
| `RELATIVE`            | Replace the date by a words relative to the date.             |

`TRUNCATE_TO_YEAR` and `SHIFT` fall back to `REDACT` when the date cannot be parsed.

### Filter Strategy Options

The following filter strategy options are available for the `RELATIVE` filter strategy.

|               | Description                                                                                        | Default Value |
| ------------- | -------------------------------------------------------------------------------------------------- | ------------- |
| `futureDates` | When `true`, future dates are replaced by relative words. When `false`, future dates are redacted. | `false`       |

The following filter strategy options are available for the `SHIFT` filter strategy.

| Option         | Description                                                                                                       | Default Value |
| -------------- | ----------------------------------------------------------------------------------------------------------------- | ------------- |
| `shiftDays`    | The number of days to shift the date. Can be a negative or positive integer. Defaults to `0` if not specified.    | `0`           |
| `shiftMonths`  | The number of months to shift the date. Can be a negative or positive integer. Defaults to `0` if not specified.  | `0`           |
| `shiftYears`   | The number of years to shift the date. Can be a negative or positive integer. Defaults to `0` if not specified.   | `0`           |
| `shiftRandom`  | When `true`, shift the date by a random number of days, months, and years instead of `shiftDays`, `shiftMonths`, and `shiftYears`. | `false`       |

### Conditions

Each filter strategy may have one condition. See [Conditions](../../filter_strategies.md#filter-strategy-conditions) for details.

| Conditional  | Description                                                              | Operators                          |
| ------------ | ------------------------------------------------------------------------ | ---------------------------------- |
| `TOKEN`      | Compares the value of the sensitive text.                                | `==`, `startswith`                 |
| `TOKEN`      | Compares the sensitive text to some category, e.g. `"birthdate"`.        | `is`, `is not`                     |
| `CONTEXT`    | Compares the filtering context.                                          | `==` , `!=`                        |
| `CONFIDENCE` | Compares the confidence in the sensitive text against a threshold value. | `<` , `<=`, `>` , `>=`, `==`, `!=` |

#### Differentiating Between Dates and Birth Dates

In some cases it may be necessary to redact birth dates and dates differently. Using conditions it is possible to determine if an identified date is a birth date. The conditional `token is "birthdate"` will determine if the identified date (token) is a birth date by analyzing the content surrounding the date. The supported categories are `"birthdate"`, `"deathdate"`, and `"birthdate or deathdate"`.

## Example Policy to Redact Dates

The following policy redacts dates.

```
{
   "identifiers": {
      "date": {
         "onlyValidDates": false,
         "dateFilterStrategies": [
            {
               "strategy": "REDACT",
               "redactionFormat": "{{{REDACTED-%t}}}"
            }
         ]
      }
   }
}
```

## Example Policy to Shift Dates

The following policy to shift dates forward by 2 days and 4 months.

```
{
   "identifiers": {
      "date": {
         "onlyValidDates": false,
         "dateFilterStrategies": [
            {
               "strategy": "SHIFT",
               "shiftDays": 2,
               "shiftMonths": 4,
               "shiftYears": 0
            }
         ]
      }
   }
}
```

# Filter Strategies

A filter strategy defines how sensitive information identified by Philter should be manipulated, whether it is redacted, replaced, encrypted, or manipulated in some other fashion.

In a policy, you list the types of sensitive information that should be filtered. How Philter replaces each type of sensitive information is specific to each type. For instance, zip codes can be truncated based on the leading digits or zip code population while phone numbers are redacted. These replacements are performed by "filter strategies."

> Each filter can have one or more filter strategies and conditions can be used to determine when to apply each filter strategy.

A sample policy containing a filter strategy is shown below. In this example, email addresses will be redacted.

```
{
   "identifiers": {
      "emailAddress": {
         "emailAddressFilterStrategies": [
            {
               "strategy": "REDACT",
               "redactionFormat": "{{{REDACTED-%t}}}"
            }
         ]
      }
   }
}
```

> Most of the filter strategies apply to all types of data, however, some filter strategies only apply to a few types. For example, the `ZERO_LEADING` filter strategy only applies to a zip code filter. A strategy a filter does not support falls back to `REDACT`.


## Filter Strategies

The filter strategies are described below. Each filter type can specify zero or more filter strategies. When no filter strategies are given, Philter will default to `REDACT` for that filter type. When multiple filter strategies are given for a single filter type, they are evaluated in the order listed, top to bottom, and only the first one whose condition is satisfied, or that has no condition, is applied. If no strategy's condition is satisfied, the value is left unchanged. To transform every detected value, end the list with a strategy that has no condition.

* [REDACT](#the-redact-filter-strategy)
* [CRYPTO_REPLACE](#the-crypto_replace-filter-strategy)
* [HASH_SHA256_REPLACE](#the-hash_sha256_replace-filter-strategy)
* [FPE_ENCRYPT_REPLACE](#the-fpe_encrypt_replace-filter-strategy)
* [RANDOM_REPLACE](#the-random_replace-filter-strategy)
* [STATIC_REPLACE](#the-static_replace-filter-strategy)
* [MASK](#the-mask-filter-strategy)
* [LAST_4](#the-last_4-filter-strategy)
* [ABBREVIATE](#the-abbreviate-filter-strategy)
* [MAP_REPLACE](#the-map_replace-filter-strategy)
* [TRUNCATE](#the-truncate-filter-strategy)
* [ZERO_LEADING](#the-zero_leading-filter-strategy)
* [Date strategies](#date-filter-strategies) (`TRUNCATE_TO_YEAR`, `SHIFT`, `RELATIVE`)

### The `REDACT` Filter Strategy

The REDACT filter strategy replaces sensitive information with a given redaction format. You can put variables in the redaction format that Philter will replace when performing the redaction.

The available redaction variables are:

| Redaction Variable | Description                                                                                                                                               |
| ------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `%t`               | Will be replaced with the type of sensitive information. This is to allow you to know the type of sensitive information that was identified and redacted. |
| `%l`               | Will be replaced by the given classification for the type of sensitive information.                                                                       |
| `%v`               | Will be replaced by the original value of the sensitive text. With `%v` you can annotate sensitive information instead of masking or removing it.         |

To redact sensitive information by replacing it with the type of sensitive information, the redaction format would be `REDACTED-%t`.

An example filter using the `REDACT` filter strategy:

```
{
   "identifiers": {
      "emailAddress": {
         "emailAddressFilterStrategies": [
            {
               "strategy": "REDACT",
               "redactionFormat": "{{{REDACTED-%t}}}"
            }
         ]
      }
   }
}
```

### The `CRYPTO_REPLACE` Filter Strategy

The `CRYPTO_REPLACE` filter strategy replaces each identified piece of sensitive information with its encrypted value. Philter encrypts using AES in GCM mode (authenticated encryption) with a freshly generated random nonce for each value. As a result the same input encrypts to a different value each time (so equal values cannot be matched across the output), and each encrypted value carries an authentication tag that detects tampering. The encryption is reversible: an encrypted value can be decrypted later using the same key.

To use this filter strategy, the policy must provide an encryption `key` in a top-level `crypto` object:

```
{
   "crypto": {
     "key": "...."
   },
   ...
```

The `key` is a hex-encoded AES key. Use 64 hex characters for a 256-bit key (recommended), or 32 hex characters for a 128-bit key. Generate one with:

```
openssl rand -hex 32
```

#### Keeping the key out of the policy

Because `CRYPTO_REPLACE` is reversible, the key is a sensitive secret. Rather than writing it directly into the policy file, prefix the value with `env:` to have Philter read it from an environment variable at redaction time:

```
"crypto": {
  "key": "env:PHILTER_CRYPTO_KEY"
}
```

With the example above, Philter reads the key from the `PHILTER_CRYPTO_KEY` environment variable.

An example policy using the `CRYPTO_REPLACE` filter strategy:

```
{
   "crypto": {
     "key": "env:PHILTER_CRYPTO_KEY"
   },
   "identifiers": {
      "emailAddress": {
         "emailAddressFilterStrategies": [
            {
               "strategy": "CRYPTO_REPLACE"
            }
         ]
      }
   }
}
```

### The `HASH_SHA256_REPLACE` Filter Strategy

The `HASH_SHA256_REPLACE` filter strategy replaces sensitive information with the SHA256 hash value of the sensitive information. To append a random salt value to each value prior to hashing, set the `salt` property to `true`. The salt value used will be returned in the `explain` response from Philter' API.

An example policy using the `HASH_SHA256_REPLACE` filter strategy:

```
{
   "identifiers": {
      "emailAddress": {
         "emailAddressFilterStrategies": [
            {
               "strategy": "HASH_SHA256_REPLACE"
            }
         ]
      }
   }
}
```

### The FPE\_ENCRYPT\_REPLACE Filter Strategy

The `FPE_ENCRYPT_REPLACE` filter strategy uses format-preserving encryption (FPE) to encrypt the sensitive information. Philter uses the FF3-1 algorithm for format-preserving encryption.

By default you do not need to supply a key. Philter manages a stable format-preserving-encryption key for each user automatically and applies it at redaction time, so selecting the `FPE_ENCRYPT_REPLACE` strategy is enough:

```
{
   "identifiers": {
      "creditCard": {
         "creditCardFilterStrategies": [
            {
               "strategy": "FPE_ENCRYPT_REPLACE"
            }
         ]
      }
   }
}
```

Because the key is stable, the encryption is deterministic and reversible: the same input always encrypts to the same value (referential integrity across documents), and the value can be decrypted with the user's key.

To use your own key instead of the managed one, supply a top-level `fpe` object with a `key` and a `tweak`:

```
{
   "fpe": {
     "key": "...",
     "tweak": "..."
   },
   "identifiers": {
      "creditCard": {
         "creditCardFilterStrategies": [
            {
               "strategy": "FPE_ENCRYPT_REPLACE"
            }
         ]
      }
   }
}
```

The `key` is a hex-encoded AES key (32, 48, or 64 hex characters for a 128-, 192-, or 256-bit key) and the `tweak` is a hex value (14 or 16 hex characters). Generate them with:

```
openssl rand -hex 32   # key (256-bit)
openssl rand -hex 7    # tweak (56-bit)
```

Either value may be prefixed with `env:` to read it from an environment variable (for example, `env:PHILTER_FPE_KEY`) so the secret is not stored in the policy.

For more information on these values and format-preserving encryption, refer to the resources below:

* [https://nvlpubs.nist.gov/nistpubs/SpecialPublications/NIST.SP.800-38Gr1-draft.pdf](https://nvlpubs.nist.gov/nistpubs/SpecialPublications/NIST.SP.800-38Gr1-draft.pdf)
* [https://nvlpubs.nist.gov/nistpubs/specialpublications/nist.sp.800-38g.pdf](https://nvlpubs.nist.gov/nistpubs/specialpublications/nist.sp.800-38g.pdf)

### The `RANDOM_REPLACE` Filter Strategy

Replaces the identified text with a fake value but of the same type. For example, an SSN will be replaced by a random text having the format `###-##-####`, such as 123-45-6789. An email address will be replaced with a randomly generated email address. Available to all filter types.

By default each document is pseudonymized independently. To make the same value map to the same fake value across every document in a [context](../redaction/contexts.md), set `"replacementScope": "CONTEXT"` on the strategy. See [Consistent Pseudonymization](../redaction/replacement_scope.md).

An example policy using the `RANDOM_REPLACE` filter strategy:

```
{
   "identifiers": {
      "emailAddress": {
         "emailAddressFilterStrategies": [
            {
               "strategy": "RANDOM_REPLACE"
            }
         ]
      }
   }
}
```

### The `STATIC_REPLACE` Filter Strategy

Replaces the identified text with a given static value. Available to all filter types.

An example policy using the `STATIC_REPLACE` filter strategy:

```
{
   "identifiers": {
      "emailAddress": {
         "emailAddressFilterStrategies": [
            {
               "strategy": "STATIC_REPLACE",
               "staticReplacement": "some new value"
            }
         ]
      }
   }
}
```

### The `MASK` Filter Strategy

Replaces the identified text with a repeated mask character. `maskCharacter` sets the character (default `*`). `maskLength` sets the number of characters, either a number or `SAME` (default) to match the length of the identified text.

```
{
   "identifiers": {
      "emailAddress": {
         "emailAddressFilterStrategies": [
            {
               "strategy": "MASK",
               "maskCharacter": "#",
               "maskLength": "SAME"
            }
         ]
      }
   }
}
```

### The `LAST_4` Filter Strategy

Replaces the identified text with its last four characters. For example, `4111111111111111` becomes `1111`. Not available to the date, zip code, and PhEye filters.

### The `ABBREVIATE` Filter Strategy

Replaces the identified text with the uppercase initials of its words. For example, `John Smith` becomes `JS`. Not available to the date and zip code filters.

### The `MAP_REPLACE` Filter Strategy

Replaces the identified text using a lookup table. Not available to the date, zip code, and PhEye filters.

| Property           | Description                                                                                                                                       | Default  |
| ------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------- | -------- |
| `mappings`         | An object mapping an identified value to its replacement. Inline entries take precedence over entries loaded from `mappingFiles`.                 | None     |
| `mappingFiles`     | A list of local file paths. Each file is tab-delimited with one key and replacement per line.                                                    | None     |
| `caseSensitive`    | Whether lookup keys are matched case-sensitively.                                                                                                 | `false`  |
| `generator`        | The name of a generator in the policy's top-level `generators` object, called for a value not in the lookup table.                                | None     |
| `fallbackStrategy` | The strategy applied when the value is not in the lookup table and no generator produces an accepted value. `MAP_REPLACE` is treated as `REDACT`. | `REDACT` |

A generated value is rejected, and the fallback strategy applied, when it is blank, equals the original value, or contains sensitive information detected by the policy's filters. With `"replacementScope": "CONTEXT"`, a value receives the same replacement across the documents in a context.

Generators are declared by name in the top-level `generators` object. The only supported `type` is `ollama`, which calls an Ollama-compatible `/api/generate` endpoint. `prompt` may contain `{{token}}` and `{{label}}` placeholders. `timeoutMs` sets the request timeout in milliseconds.

```
{
   "generators": {
      "local-llm": {
         "type": "ollama",
         "endpoint": "http://localhost:11434",
         "model": "llama3",
         "prompt": "Return a fictitious replacement for the {{label}} value {{token}}. Return only the value.",
         "timeoutMs": 5000
      }
   },
   "identifiers": {
      "emailAddress": {
         "emailAddressFilterStrategies": [
            {
               "strategy": "MAP_REPLACE",
               "mappings": {
                  "jane@example.com": "user1@example.org"
               },
               "generator": "local-llm",
               "fallbackStrategy": "REDACT"
            }
         ]
      }
   }
}
```

### The `TRUNCATE` Filter Strategy

Keeps a number of characters of the identified text and replaces the rest with a truncate character. `truncateLeaveCharacters` sets the number of characters to keep (default `4`, minimum `1`). `truncateCharacter` sets the replacement character (default `*`). `truncateDirection` is `LEADING` (default) to keep the leading characters or `TRAILING` to keep the trailing characters. For the zip code filter, `truncateLeaveCharacters` must be between `1` and `4`; with the default of `4`, the zip code 90210 is truncated to `9021*`, and with `2` it is truncated to `90***`.

An example policy using the `TRUNCATE` filter strategy:

```
{
   "identifiers": {
      "zipCode": {
         "zipCodeFilterStrategies": [
            {
               "strategy": "TRUNCATE",
               "truncateLeaveCharacters": 3
            }
         ]
      }
   }
}
```

### The `ZERO_LEADING` Filter Strategy

Available only to zip codes, this strategy changes the first 3 digits of a zip code to be 0. For example, the zip code 90210 will be changed to 00010.

The `ZERO_LEADING` filter strategy is only available to zip code filters. An example zip code filter using the `ZERO_LEADING` filter strategy:

```
{
   "identifiers": {
      "zipCode": {
         "zipCodeFilterStrategies": [
            {
               "strategy": "ZERO_LEADING"
            }
         ]
      }
   }
}
```

### Date Filter Strategies

The date filter also supports `TRUNCATE_TO_YEAR` (replace the date with its year), `SHIFT` (shift the date by `shiftDays`, `shiftMonths`, and `shiftYears`, or by a random amount with `"shiftRandom": true`), and `RELATIVE` (replace the date with a relative description such as "3 months ago"). See [Dates](filters/common_filters/dates.md).

## Filter Strategy Conditions

A replacement strategy can be applied based on the sensitive information meeting one or more conditions. For example, you can create a condition such that only dates of `11/05/2010` are replaced by using the condition `token == "11/05/2010"`. The conditions that can be applied vary based on the type of sensitive information. For instance, zip codes can have conditions based on their population. Refer to each specific [filter type](filters.md) for the conditions available. For `token`, `==` compares case-insensitively and `startswith` compares case-sensitively. To exclude specific values from a filter, use [ignored terms](ignoring_specific_information.md).

The following is an example policy for credit cards that contains a condition to only redact credit card numbers that start with the digits `3000`:

```
{
  "identifiers": {
    "creditCard": {
      "creditCardFilterStrategies": [
        {
          "condition": "token startswith \"3000\"",
          "strategy": "REDACT",
          "redactionFormat": "{{{REDACTED-%t}}}"
        }
      ]
    }
  }
}
```

With this policy, credit card numbers that do not start with `3000` are left unchanged. To transform them too, end the list with a strategy that has no condition. Here, numbers starting with `3000` are masked and all others are redacted:

```
"creditCardFilterStrategies": [
  {
    "condition": "token startswith \"3000\"",
    "strategy": "MASK"
  },
  {
    "strategy": "REDACT",
    "redactionFormat": "{{{REDACTED-%t}}}"
  }
]
```

#### Combining Conditions

Conditions can be joined through the use of the `and` keyword. When conditions are joined, each condition must be satisfied for the strategy to be applied. If any of the conditions is not satisfied, the strategy is skipped and the next strategy in the list is evaluated. Below is an example joined condition:

```
token == "123-45-6789" and context == "my-context"
```

This condition requires that the identified text (the token) be equal to `123-45-6789` and the context be equal to `my-context`. Both of these conditions must be satisfied for the strategy to be applied.

Conversely, conditions can be `OR`'d through the use of multiple filter strategies. For example, if we want to `OR` a condition on the token and a condition on the context, we would use two filter strategies:

```
"ssnFilterStrategies": [
  {
    "condition": "token == \"123-45-6789\"",
    "strategy": "REDACT",
    "redactionFormat": "{{{REDACTED-%t}}}"
  },
  {
    "condition": "context == \"my-context\"",
    "strategy": "REDACT",
    "redactionFormat": "{{{REDACTED-%t}}}"
  }        
]
```

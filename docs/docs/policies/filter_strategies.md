# Filter Strategies

A filter strategy defines how sensitive information identified by Philter should be manipulated, whether it is redacted, replaced, encrypted, or manipulated in some other fashion.

In a policy, you list the types of sensitive information that should be filtered. How Philter replaces each type of sensitive information is specific to each type. For instance, zip codes can be truncated based on the leading digits or zip code population while phone numbers are redacted. These replacements are performed by "filter strategies."

> Each filter can have one or more filter strategies and conditions can be used to determine when to apply each filter strategy.

A sample policy containing a filter strategy is shown below. In this example, email addresses will be redacted.

```
{
   "name": "email-address",
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

> Most of the filter strategies apply to all types of data, however, some filter strategies only apply to a few types. For example, the `TRUNCATE` filter strategy only applies to a zip code filter.


## Filter Strategies

The filter strategies are described below. Each filter type can specify zero or more filter strategies. When no filter strategies are given, Philter will default to `REDACT` for that filter type. When multiple filter strategies are given for a single filter type, the filter strategies will be applied in order as they are listed in the policy, top to bottom.

* [REDACT](#the-redact-filter-strategy)
* [CRYPTO_REPLACE](#the-crypto_replace-filter-strategy)
* [HASH_SHA256_REPLACE](#the-hash_sha256_replace-filter-strategy)
* [FPE_ENCRYPT_REPLACE](#the-fpe_encrypt_replace-filter-strategy)
* [RANDOM_REPLACE](#the-random_replace-filter-strategy)
* [STATIC_REPLACE](#the-static_replace-filter-strategy)
* [TRUNCATE](#the-truncate-filter-strategy)
* [ZERO_LEADING](#the-zero_leading-filter-strategy)

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
   "name": "email-address",
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
   "name": "sample-profile",
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
   "name": "email-address",
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
   "name": "email-address",
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
   "name": "credit-cards",
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
   "name": "credit-cards",
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

By default each document is anonymized independently. To make the same value map to the same fake value across every document in a [context](../redaction/contexts.md), set `"replacementScope": "CONTEXT"` on the strategy — see [Consistent Pseudonymization](../redaction/replacement_scope.md).

An example policy using the `RANDOM_REPLACE` filter strategy:

```
{
   "name": "email-address",
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
   "name": "email-address",
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

### The `TRUNCATE` Filter Strategy

Available only to zip codes, this strategy allows for truncating zip codes to only a select number of digits. Specify `truncateDigits` to set the desired number of leading digits to leave. For example, if `truncateDigits` is 2, the zip code 90210 will be truncated to `90***`.&#x20;

The TRUNCATE filter strategy is available only to the zip code filter. An example policy using the `TRUNCATE` filter strategy:

```
{
   "name": "zip-codes",
   "identifiers": {
      "zipCode": {
         "zipCodeFilterStrategy": [
            {
               "strategy": "TRUNCATE",
               "truncateDigits": 3
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
   "name": "zip-codes",
   "identifiers": {
      "zipCode": {
         "zipCodeFilterStrategy": [
            {
               "strategy": "ZERO_LEADING"
            }
         ]
      }
   }
}
```

## Filter Strategy Conditions

A replacement strategy can be applied based on the sensitive information meeting one or more conditions. For example, you can create a condition such that only dates of `11/05/2010` are replaced by using the condition `token == "11/05/2010"`. The conditions that can be applied vary based on the type of sensitive information. For instance, zip codes can have conditions based on their population. Refer to each specific [filter type](filters.md) for the conditions available.

The following is an example policy for credit cards that contains a condition to only redact credit card numbers that start with the digits `3000`:

```
{
  "name": "default",
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

#### Combining Conditions

Conditions can be joined through the use of the `and` keyword. When conditions are joined, each condition must be satisfied for the identified text to be filtered. If any of the conditions are not satisfied the identified text will not be filtered. Below is an example joined condition:

```
token != "123-45-6789" and context == "my-context"
```

This condition requires that the identified text (the token) not be equal to `123-45-6789` and the context be equal to `my-context`. Both of these conditions must be satisfied for the identified text to be filtered.

Conversely, conditions can be `OR`'d through the use of multiple filter strategies. For example, if we want to `OR` a condition on the token and a condition on the context, we would use two filter strategies:

```
"ssnFilterStrategies": [
  {
    "condition": "token != \"123-45-6789\"",
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

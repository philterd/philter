# Sample Policies

This page lists some sample policies. You can use these policies either as-is or as starting points for customizing them to meet your specific de-identification needs.

## Managed Policies

Philter includes built-in managed policies that can be used as starting points:

| Name | Description |
|------|-------------|
| `managed_common_pii` | Common PII including names, emails, phone numbers, and SSNs |
| `managed_healthcare_phi` | Healthcare PHI including names, dates, ages, cities, states, zip codes, emails, phone numbers, and SSNs |
| `managed_financial_pii` | Financial PII including credit cards, bank routing numbers, and Bitcoin addresses |

A managed policy can be read but not changed. To use one as the basis for your own, copy it and edit the copy:

```
curl -X POST -H "Authorization: Bearer <token>" -k "https://localhost:8080/api/policies/managed_common_pii/copy?name=my-pii"
```

List them, each with its description, with `GET /api/policies?managed=true`, and read one with `GET /api/policies/managed_common_pii`. See the [Policies API](../api_and_sdks/api/policies_api.md#copy-a-policy).

## Examples

> These policies are examples and not an exhaustive list of all the sensitive information Philter can identify. Items from each of these policies can be combined to make policies to meet your use-cases.


### Email Addresses and Phone Numbers

This policy finds email addresses and phone numbers and redacts them with `{{{REDACTED-email-address}}}` and `{{{REDACTED-phone-number}}}`, respectively.

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
    },
    "phoneNumber": {
      "phoneNumberFilterStrategies": [
        {
          "strategy": "REDACT",
          "redactionFormat": "{{{REDACTED-%t}}}"
        }
      ]
    }
  }
}
```

### Persons Names and SSNs

This policy finds persons names and SSNs and redacts them with `{{{REDACTED-entity}}}` and `{{{REDACTED-ssn}}}`, respectively.

```
{
  "identifiers": {
    "person": {
      "phEyeFilterStrategies": [
        {
          "strategy": "REDACT",
          "redactionFormat": "{{{REDACTED-%t}}}"
        }
      ]
    },
    "ssn": {
      "ssnFilterStrategies": [
        {
          "strategy": "REDACT",
          "redactionFormat": "{{{REDACTED-%t}}}"
        }
      ]
    }
  }
}
```

### Dates, URLs, and VINs

This policy finds dates, URLs, and VINs. Dates and URLs are redacted with `{{{REDACTED-date}}}` and `{{{REDACTED-url}}}`, respectively. Each VIN number are replaced by a randomly generated VIN number.

```
{
  "identifiers": {
    "date": {
      "dateFilterStrategies": [
        {
          "strategy": "REDACT",
          "redactionFormat": "{{{REDACTED-%t}}}"
        }
      ]
    },
    "url": {
      "urlFilterStrategies": [
        {
          "strategy": "REDACT",
          "redactionFormat": "{{{REDACTED-%t}}}"
        }
      ]
    },
    "vin": {
      "vinFilterStrategies": [
        {
          "strategy": "RANDOM_REPLACE"
        }
      ]
    }
  }
}
```

### IP Addresses

This policy finds IP addresses and replaces each identified IP address with the static text `IP_ADDRESS` as long as the IP address is not `127.0.0.1`. (The filter's `ignored` list excludes `127.0.0.1`.)

```
{
  "identifiers": {
    "ipAddress": {
      "ignored": ["127.0.0.1"],
      "ipAddressFilterStrategies": [
        {
          "strategy": "STATIC_REPLACE",
          "staticReplacement": "IP_ADDRESS"
        }
      ]
    }
  }
}
```

### Zip Codes

This policy finds ZIP codes starting with `90` and truncates the zip code to its first two digits, for example `90210` to `90***`.

```
{
  "identifiers": {
    "zipCode": {
      "zipCodeFilterStrategies": [
        {
          "condition": "token startswith \"90\"",
          "strategy": "TRUNCATE",
          "truncateLeaveCharacters": 2
        }
      ]
    }
  }
}
```

### Enable Text Splitting

This policy enables text splitting for input over 10,000 characters.

```
{
  "config": {
    "splitting": {
      "enabled": true,
      "threshold": 10000,
      "method": "newline"
    }
  },
  "identifiers": {
    "ssn": {
      "ssnFilterStrategies": [
        {
          "strategy": "REDACT",
          "redactionFormat": "{{{REDACTED-%t}}}"
        }
      ]
    }
  }
}
```

### Globally Ignored Terms

This policy has a list of globally ignored terms.

```
{
  "ignored": [
    {
      "name": "ignored credit cards",
      "terms": ["4111111111111111", "0000000000000000"]
    }
  ],
  "identifiers": {
    "creditCard": {
      "creditCardFilterStrategies": [
        {
          "strategy": "REDACT",
          "redactionFormat": "{{{REDACTED-%t}}}"
        }
      ]
    }
  }
}
```

### Redacting a Specific Value

This policy redacts an email address only when it is `test@example.com`.

```
{
  "identifiers": {
    "emailAddress": {
      "emailAddressFilterStrategies": [
        {
          "strategy": "REDACT",
          "redactionFormat": "{{{REDACTED-%t}}}",
          "condition": "token == \"test@example.com\""
        }
      ]
    }
  }
}
```
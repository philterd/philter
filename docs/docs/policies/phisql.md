# Authoring Policies with PhiSQL

[PhiSQL](https://github.com/philterd/phisql) is a declarative language for writing redaction policies. It compiles to the same native policy JSON documented in the [Policy Schema Reference](policy_schema.md), so a compiled PhiSQL policy is an ordinary Philter policy: nothing about redaction behaves differently.

PhiSQL exists because policies are reviewed by people who do not write JSON. A policy expressed as `REDACT SSN WITH MASK;` can be read, diffed, and approved by a compliance reviewer; the equivalent nested JSON is harder to review and easier to get wrong.

Philter compiles PhiSQL through `POST /api/policies/compile`. It does not store PhiSQL source: keep the source in your own version control, and save the compiled JSON as the policy.

## A Minimal Policy

```sql
POLICY ssn_only;

REDACT SSN WITH MASK;
```

compiles to:

```json
{
  "identifiers": {
    "ssn": {
      "ssnFilterStrategies": [
        { "strategy": "MASK" }
      ]
    }
  }
}
```

## A Larger Policy

`DEIDENTIFY` applies one strategy per entity in a single statement, and `WHERE` attaches a condition to a strategy.

```sql
POLICY clinical_notes
  DESCRIPTION 'De-identify clinical notes before analytics.';

DEIDENTIFY
  PHYSICIAN_NAME AS RANDOM_REPLACE,
  SSN            AS REDACT,
  PHONE_NUMBER   AS REDACT,
  EMAIL_ADDRESS  AS REDACT,
  DATE           AS TRUNCATE;

REDACT CREDIT_CARD WITH LAST_4 WHERE CONFIDENCE > 0.85;
```

compiles to:

```json
{
  "identifiers": {
    "physicianName": {
      "physicianNameFilterStrategies": [ { "strategy": "RANDOM_REPLACE" } ]
    },
    "ssn": {
      "ssnFilterStrategies": [ { "strategy": "REDACT" } ]
    },
    "phoneNumber": {
      "phoneNumberFilterStrategies": [ { "strategy": "REDACT" } ]
    },
    "emailAddress": {
      "emailAddressFilterStrategies": [ { "strategy": "REDACT" } ]
    },
    "date": {
      "dateFilterStrategies": [ { "strategy": "TRUNCATE" } ]
    },
    "creditCard": {
      "creditCardFilterStrategies": [
        { "strategy": "LAST_4", "conditions": "confidence > 0.85" }
      ]
    }
  }
}
```

## Compiling and Saving

Compiling and saving are two calls. Compile returns the policy name from the `POLICY` declaration, the description if one was given, and the compiled policy under `policy`:

```bash
curl -k -X POST "https://localhost:8080/api/policies/compile" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: text/plain" \
  --data-binary @clinical-notes.phisql
```

```json
{
  "name": "clinical_notes",
  "description": "De-identify clinical notes before analytics.",
  "policy": { "identifiers": { "...": {} } }
}
```

Then save the `policy` object under that name:

```bash
curl -k -X POST "https://localhost:8080/api/policies?name=clinical_notes" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d @compiled-policy.json
```

Both steps in one pipeline, with `jq`:

```bash
NAME=$(curl -sk -X POST "https://localhost:8080/api/policies/compile" \
  -H "Authorization: Bearer <token>" -H "Content-Type: text/plain" \
  --data-binary @clinical-notes.phisql | tee /tmp/compiled.json | jq -r .name)

jq .policy /tmp/compiled.json | curl -sk -X POST "https://localhost:8080/api/policies?name=${NAME}" \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" -d @-
```

The `POLICY` declaration is optional. Source without one still compiles, but `name` comes back as `null`, so choose a name yourself when saving.

## Errors

A source that fails to parse or compile returns `400 Bad Request` with the compiler's message:

```json
{"message": "Unknown entity type: NOT_A_THING"}
```

Philter also validates the compiled output against the policy schema before returning it, so a policy that compiles but would be rejected by `POST /api/policies` fails at compile time instead, with the validation message.

## Language Reference

The grammar, catalog of entity types and strategies, and worked examples live in the [PhiSQL project](https://github.com/philterd/phisql), which versions independently of Philter. Philter compiles with the PhiSQL Java reference implementation (`ai.philterd:phisql`) bundled through [Phileas](https://github.com/philterd/phileas), so the accepted syntax is whatever that version accepts.

Entity type and strategy names in PhiSQL map onto the filters and filter strategies documented here: see [Filters](filters.md) and [Filter Strategies](filter_strategies.md).

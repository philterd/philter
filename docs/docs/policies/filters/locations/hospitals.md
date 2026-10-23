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

## Detecting Hospitals with a Model

The filter above detects the hospitals in its bundled list, or in your `terms`. To detect hospital names that are not in a list, run [`knowledgator/gliner-pii-base-v1.0`](https://huggingface.co/knowledgator/gliner-pii-base-v1.0) inside Philter as a [`pheyes`](../persons_names/persons-names-ner.md) entry, with no PhEye service. It is a third-party GLiNER model, licensed Apache-2.0 by Knowledgator, and is not trained by Philterd. It is not included in the Philter image: its files are about 670 MB. Its detection is probabilistic, so validate its output against your own data.

### Download the Model

Download the three files Philter reads, pinned to a revision, and check them. Run this in the directory that holds your `docker-compose.yml`; on macOS, use `shasum -a 256 -c` in place of `sha256sum -c`.

```
mkdir -p gliner-pii-base/onnx && cd gliner-pii-base
REV=61726e0ad791dcab3e29339bbec3ad42ded65641
for f in gliner_config.json tokenizer.json onnx/model.onnx; do
  curl -fL -o "$f" "https://huggingface.co/knowledgator/gliner-pii-base-v1.0/resolve/$REV/$f"
done
sha256sum -c <<'SUMS'
e33d3da38e0d369fa7574668d3798ca6c7d2b23cba7d628507112eeb426aaccb  gliner_config.json
ee028763434d18611c1c36356ea1d050e90a9fa94ede57fac48b39f85f818ad1  tokenizer.json
c6ccec44625d46bfe3191152e41d6564b69bc9d4313b7f3e419e8372679e9fed  onnx/model.onnx
SUMS
cd ..
```

### Mount the Model

Mount the directory, read-only, as a subdirectory of `PHEYE_MODELS_DIRECTORY` (default `/opt/philter/models`). Its name is the model's name. Mount the subdirectory rather than the whole models directory, so the `ph-eye-pii-en-small` model the image includes stays available:

```yaml
services:
  philter:
    volumes:
      - ./gliner-pii-base:/opt/philter/models/gliner-pii-base:ro
```

[`GET /api/models`](../../../api_and_sdks/api/models_api.md) then lists `gliner-pii-base`, with a `maxLength` of 2048.

### Example Policy

```json
{
  "identifiers": {
    "pheyes": [
      {
        "phEyeConfiguration": {
          "modelPath": "gliner-pii-base",
          "labels": ["hospital"]
        },
        "phEyeFilterStrategies": [
          { "strategy": "REDACT", "redactionFormat": "{{{REDACTED-%t}}}" }
        ]
      }
    ]
  }
}
```

With this policy, the text

```
The patient was transferred from Mercy Hospital to Riverside General on Monday.
```

is redacted to

```
The patient was transferred from {{{REDACTED-hospital}}} to {{{REDACTED-hospital}}} on Monday.
```

and `Call the clinic with questions.` is unchanged.

**`labels` is required for this model.** A `pheyes` entry without `labels`, or with an empty list, searches for `["name"]`, so a hospitals entry that leaves it out redacts names instead of hospitals. The model also detects other labels; for example, `["hospital", "room number"]` redacts `assigned to room 204` as `assigned to {{{REDACTED-room number}}}`.

**Spans are typed `OTHER`.** [`POST /api/explain`](../../../api_and_sdks/api/filtering_api.md#explain) reports this model's spans with a `filterType` of `OTHER`, while the dictionary `hospital` filter above reports `HOSPITAL`. Redaction is unaffected, and `%t` in a `redactionFormat` gives `hospital` for both: this model's label, and the dictionary filter's type.

### Names and Hospitals in One Policy

A policy can have several `pheyes` entries, each with its own model. This one detects names with the model the image includes and hospitals with this one:

```json
{
  "identifiers": {
    "pheyes": [
      {
        "phEyeConfiguration": { "modelPath": "ph-eye-pii-en-small" },
        "phEyeFilterStrategies": [
          { "strategy": "REDACT", "redactionFormat": "{{{REDACTED-%t}}}" }
        ]
      },
      {
        "phEyeConfiguration": { "modelPath": "gliner-pii-base", "labels": ["hospital"] },
        "phEyeFilterStrategies": [
          { "strategy": "REDACT", "redactionFormat": "{{{REDACTED-%t}}}" }
        ]
      }
    ]
  }
}
```

```
Alice Example was transferred from Mercy Hospital to Riverside General on Monday.
```

is redacted to

```
{{{REDACTED-name}}} was transferred from {{{REDACTED-hospital}}} to {{{REDACTED-hospital}}} on Monday.
```

### Running the Model as a Service

The `philterd/ph-eye:1.3.0-hospitals` image runs the same model as a PhEye service on port 5000. It replaces the older `philterd/ph-eye-hospitals` image. It is about 10 GB. A policy names it as its `endpoint` instead of `modelPath`:

```json
"phEyeConfiguration": {
  "endpoint": "http://ph-eye-hospitals:5000/",
  "labels": ["hospital"]
}
```

A policy may name only `PHEYE_ENDPOINT` or an endpoint in `PHEYE_ALLOWED_ENDPOINTS`, and is refused with `400 Bad Request` when saved otherwise. Allow the service, and put it on the same network as Philter (`philter` in the repository's compose files):

```yaml
services:
  philter:
    environment:
      PHEYE_ALLOWED_ENDPOINTS: http://ph-eye-hospitals:5000/
  ph-eye-hospitals:
    image: philterd/ph-eye:1.3.0-hospitals
    networks:
      - philter
```

See [Redaction services](../../../settings.md#redaction-services).

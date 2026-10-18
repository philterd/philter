# Models API

The models API lists the local PhEye models a policy can name. A model is a subdirectory of `PHEYE_MODELS_DIRECTORY` (default `/opt/philter/models`) that holds `gliner_config.json`, `tokenizer.json`, and `model.onnx` (or `onnx/model.onnx`); its name is the subdirectory's name, which may contain letters, digits, `.`, `_`, and `-`, and starts with a letter or digit. A ph-eye filter uses a model by giving its name as `phEyeConfiguration.modelPath`, and the model then runs inside Philter, with no PhEye service. See [Settings](../../settings.md#redaction-engine) and the [Policy Schema](../../policies/policy_schema.md).

The models are the deployment's, the same for every user. The directory is read on each request, so a model an administrator adds is listed and can be named without a restart. A model Philter has loaded stays loaded until Philter restarts, so changing a loaded model's files has no effect until then. Responses do not include a model's location on disk. Both operations require `policies:read`.

## List the Models

| Method | Endpoint      | Description               |
| ------ |---------------|---------------------------|
| `GET`  | `/api/models` | List the local models.    |

Example request:

```
curl -k -H "Authorization: Bearer <token>" https://localhost:8080/api/models
```

Example response:

```json
{
  "models": [
    {
      "encoder": "microsoft/deberta-v3-xsmall",
      "maxLength": 512,
      "maxWidth": 12,
      "name": "pii-en-xsmall",
      "sizeBytes": 98724945
    }
  ]
}
```

* `name` - The name a policy gives as `modelPath`.
* `encoder` - The encoder the model was trained from, from its `gliner_config.json`. Absent when the configuration does not say.
* `maxLength` - The most words of a text the model reads; later words are not examined. Absent when the configuration does not say.
* `maxWidth` - The most words one detected entity can span. Absent when the configuration does not say.
* `sizeBytes` - The size of the model's files.

Models are ordered by name. A subdirectory missing one of the files, or whose `gliner_config.json` cannot be read, is not listed.

* `200 OK` - Every model, which may be none.

## Get a Model

| Method | Endpoint                  | Description          |
| ------ |---------------------------|----------------------|
| `GET`  | `/api/models/{modelName}` | Get one local model. |

```
curl -k -H "Authorization: Bearer <token>" https://localhost:8080/api/models/pii-en-xsmall
```

The response is one model, with the fields above.

* `200 OK` - The model.
* `404 Not Found` - There is no model with that name.

## Use a Model in a Policy

```json
{
  "identifiers": {
    "pheyes": [
      {
        "phEyeConfiguration": {
          "modelPath": "pii-en-xsmall",
          "labels": ["name"]
        },
        "phEyeFilterStrategies": [
          { "strategy": "REDACT", "redactionFormat": "{{{REDACTED-%t}}}" }
        ]
      }
    ]
  }
}
```

A `modelPath` with no `/` or `\` is a model name. A policy naming a model the deployment does not have is refused with `400 Bad Request` when it is saved, and when it is used if the model has since been removed:

```json
{"message":"There is no model named 'pii-en-large'. GET /api/models lists the models this deployment has.","reason":"invalid_request"}
```

The stored policy keeps the name, so it uses whatever model has that name when it redacts. A `modelPath` that is a path is still accepted when it is the directory of a model in `PHEYE_MODELS_DIRECTORY` or is listed in `PHEYE_ALLOWED_MODEL_PATHS`.

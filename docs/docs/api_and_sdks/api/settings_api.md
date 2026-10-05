# Settings API

These endpoints read and change the deployment's admin settings: differential-privacy count recording, output signing, the webhook destination allowlist, and Phield publishing. They require an administrator in addition to the [scope](../../account/api_keys.md#scopes): `settings:read` to read, `settings:write` to change.

## The settings object

```json
{
  "diffuseCountsEnabled": false,
  "signingEnabled": true,
  "webhookAllowlist": "hooks.example.com, 10.4.0.0/16",
  "phieldEnabled": true,
  "phieldUrl": "https://phield.example.com",
  "phieldSourceId": "philter",
  "phieldOrganization": "philter",
  "phieldApiKeySet": true,
  "warnings": []
}
```

* `diffuseCountsEnabled` - Record PII counts for [differential-privacy reporting](../../diffuse.md).
* `signingEnabled` - Sign every text filter and explain response. See [Output Signing](../../output_signing.md).
* `webhookAllowlist` - Where a user's [webhook](webhooks.md#where-a-webhook-may-point) may point: comma-separated hostnames, IP addresses, and CIDR ranges. Empty allows any public address.
* `phieldEnabled`, `phieldUrl`, `phieldSourceId`, `phieldOrganization` - [Phield](../../phield.md) publishing.
* `phieldApiKeySet` - Whether a Phield API key is set. The key itself is never returned.
* `warnings` - Warnings about the saved settings, returned on a change. Empty on a read.

## Get the settings

```
GET /api/settings
```

Requires `settings:read` and an administrator.

## Change settings

```
PATCH /api/settings
```

Requires `settings:write` and an administrator. Send only the settings to change; the rest are left as they are. Returns the settings as saved.

```json
{
  "signingEnabled": true,
  "phieldEnabled": true,
  "phieldUrl": "https://phield.example.com",
  "phieldApiKey": "the Phield instance's PHIELD_API_KEY"
}
```

* `phieldApiKey` - Sets the Phield API key. Leave it out to keep the current key; send `""` to remove it.
* `phieldSourceId` and `phieldOrganization` - Sending a blank value sets `philter`.

Values are validated before anything is saved. If any value is invalid, nothing is changed:

| Status | When |
|--------|------|
| `400 Bad Request` | A `webhookAllowlist` entry is not a hostname, an IP address, or a CIDR range; `phieldUrl` is not an absolute `http` or `https` URL; or the request sends `phieldEnabled` or `phieldUrl` and Phield would end up enabled without a URL. The body names the problem. |
| `401 Unauthorized` | The `Authorization` header is absent or the API key is not recognized. |
| `403 Forbidden` | The key does not hold the scope, or the caller is not an administrator. The message says which. |

An `http` Phield URL with an API key is saved, and the response's `warnings` says the key will be sent in the clear.

```
curl -k -X PATCH "https://localhost:8080/api/settings" \
  -H "Authorization: Bearer <administrator key>" \
  -H "Content-Type: application/json" \
  --data '{"signingEnabled":true}'
```

Each instance caches the settings for up to `ADMIN_SETTINGS_CACHE_TTL_SECONDS` (see [Settings](../../settings.md)). A change applies at once on the instance that made it, and on other instances when their cache expires.

## Auditing

A change is recorded as a `settings_updated` [audit event](../../auditing.md) naming the settings that changed and the calling API key. Values are not recorded.

## See also

* [Settings](../../settings.md)
* [Output Signing](../../output_signing.md)
* [PII Drift Monitoring with Phield](../../phield.md)
* [Differential Privacy with Philter Diffuse](../../diffuse.md)

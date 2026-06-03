# Upgrading Philter

We recommend reviewing the [Philter Release Notes](https://www.philterd.ai/philter-release-notes/) prior to upgrading.

## Upgrading to 4.x

Philter 4.x includes several breaking changes. Review the following before upgrading from 3.x.

### Platform and configuration

* **Java 25 is now required.** Philter 4 is built for and runs on Java 25 (Philter 3.x ran on Java 17). Install a Java 25 runtime before upgrading. See [System Requirements](system_requirements.md).
* **An encryption key is now required.** Set `PHILTER_ENCRYPTION_KEY` to a base64-encoded 32-byte (AES-256) key (generate one with `openssl rand -base64 32`). Philter will not start without a valid key. Existing encrypted data continues to decrypt, because each record stores the key used to encrypt it, but set this consistently going forward. See [Settings](settings.md#encryption).
* **OpenSearch has been removed.** Usage metrics are no longer stored in OpenSearch and the in-application Metrics dashboard has been removed. Metrics are now exposed in Prometheus format at `/actuator/prometheus`; collect and visualize them with your own monitoring stack. Remove the `OPENSEARCH_*` and `API_REQUESTS_INDEXING_ENABLED` variables. See [Monitoring and Logging](monitoring_and_logging.md).
* **Valkey is now optional.** If `CACHE_HOSTNAME` is unset, Philter uses an ephemeral in-memory cache. Configure Valkey for a durable, shared cache. See [Settings](settings.md#cache-settings).
* **PDF redaction is asynchronous by default.** `POST /api/filter` with a PDF now returns `202 Accepted` with a `documentId`; download the result from the [Documents API](api_and_sdks/api/documents_api.md). Append `?async=false` to keep the previous synchronous behavior. Text redaction is unchanged.
* **Policies are edited as JSON.** The visual policy builder has been replaced by a JSON editor in the dashboard; you can also build policies in the [policy editor](https://policies.philterd.ai/) and paste the JSON in. The policy format itself is unchanged from 3.x: policies are still authored with a top-level `identifiers` object keyed by filter name, each carrying a `<name>FilterStrategies` array. Your existing 3.x policy JSON continues to work, except for the filter and strategy changes below.

### Policy and strategy changes

These affect only policies that use the feature in question. A policy that does not use a given filter or strategy needs no change. For the authoritative format, see the [Policy Schema](policies/policy_schema.md) and [Filter Strategies](policies/filter_strategies.md) reference.

* **Name detection now uses PhEye.** In 3.x, person names were detected by the NER filter (`ner` with `nerFilterStrategies`). In 4.x that filter is replaced by PhEye, configured under the `pheyes` array (or the single `person` filter), each entry using a `phEyeFilterStrategies` array. A policy that still uses `ner` will silently stop detecting names. PhEye calls a separate PhEye service, so set its endpoint with `phEyeConfiguration`. See the PhEye filters in the [Policy Schema](policies/policy_schema.md).

    Before (3.x):

    ```json
    { "identifiers": { "ner": { "nerFilterStrategies": [ { "strategy": "REDACT" } ] } } }
    ```

    After (4.x):

    ```json
    {
      "identifiers": {
        "pheyes": [
          {
            "phEyeFilterStrategies": [ { "strategy": "REDACT" } ],
            "phEyeConfiguration": { "endpoint": "http://ph-eye:5000" }
          }
        ]
      }
    }
    ```

* **`CRYPTO_REPLACE` now uses AES-GCM with a hex key.** In 3.x this strategy used AES-CBC and required a top-level `crypto` object with a base64 `key` and an initialization vector (`iv`). In 4.x it uses authenticated AES-GCM with a random nonce per value. Supply a hex-encoded `key` (64 hex characters for AES-256) and remove the `iv`, which is no longer used. The `key` may be given as `env:VAR_NAME` to read it from an environment variable instead of storing it in the policy.

    Before (3.x):

    ```json
    { "crypto": { "key": "<base64 key>", "iv": "<base64 iv>" } }
    ```

    After (4.x):

    ```json
    { "crypto": { "key": "env:CRYPTO_KEY" } }
    ```

* **`FPE_ENCRYPT_REPLACE` is now auto-keyed.** In 3.x this strategy required a `key` and `tweak` on the strategy itself. In 4.x Philter manages a stable format-preserving-encryption key per user automatically, so selecting `FPE_ENCRYPT_REPLACE` works with no configuration and stays deterministic and reversible for that account. To use your own key instead, supply a top-level `fpe` object with `key` and `tweak` (the configuration moved from the strategy to this top-level object). See [`fpe`](policies/policy_schema.md#fpe).

* **Span disambiguation is now configured per context.** In 3.x, span disambiguation was a global, instance-wide setting. In 4.x it is enabled on an individual context, with the context's entity type disambiguation option, when you create or edit the context. It is not configured in the policy or through global settings. See [Span Disambiguation](other_features/span_disambiguation.md).

## Upgrading from 1.x or 2.x to 3.x

Philter 3.x introduced significant changes to how policies and configuration are managed. Due to these changes, upgrading from Philter 1.x or 2.x to 3.x requires manual migration of your policies and configuration.

### Policy Storage Changes

In Philter 3.x, filter policies are no longer stored on the local file system. Instead, they are stored in a MongoDB database. This allows for easier management of policies across multiple instances of Philter and provides a more robust storage mechanism.

Because of this change:

1.  **Policies must be recreated:** You cannot simply copy policy files from `/opt/philter/policies` to a Philter 3.x instance.
2.  **Use the Dashboard or API:** Policies must be recreated using the Philter web dashboard or the Policies API.
3.  **Authentication:** Access to the Policies API now requires Bearer token authentication.

### Configuration Changes

Philter 3.x has moved away from `.properties` files for most configurations, favoring environment variables instead. This makes Philter more cloud-native and easier to configure in containerized environments like Docker and Kubernetes.

### Steps to Upgrade to 3.x

1.  **Back up your current policies:** Ensure you have copies of your existing policy JSON files from your current Philter instance (usually in `/opt/philter/policies`).
2.  **Launch Philter 3.x:** Deploy a new Philter 3.x instance. Ensure you have MongoDB, Valkey, and OpenSearch available as required.
3.  **Configure 3.x:** Use environment variables to configure your new Philter instance. Refer to the [Settings](settings.md) documentation for a full list of available variables.
4.  **Recreate Policies:**
    *   **Via Dashboard:** Open the Philter dashboard (default port 8080), log in (default admin/admin), and use the policy editor to recreate your policies. You can copy and paste the JSON from your old policies into the editor.
    *   **Via API:** Use the [Policies API](api_and_sdks/api/policies_api.md) to upload your old policy JSON files to the new instance. Note that you will need to provide an API token.
5.  **Update your clients:** Ensure your client applications are updated to use the new Bearer token authentication and point to the correct Philter 3.x endpoints.
6.  **Test:** Thoroughly test your redaction workflows to ensure they are performing as expected with the new version.
7.  **Decommission:** Once verified, decommission your old Philter instance.

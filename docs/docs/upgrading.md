# Upgrading Philter

We recommend reviewing the [Philter Release Notes](https://www.philterd.ai/philter-release-notes/) prior to upgrading.

## Upgrading to 4.x

Philter 4.x includes several breaking changes. Review the following before upgrading from 3.x:

* **An encryption key is now required.** Set `PHILTER_ENCRYPTION_KEY` to a base64-encoded 32-byte (AES-256) key (generate one with `openssl rand -base64 32`). Philter will not start without a valid key. Existing encrypted data continues to decrypt, because each record stores the key used to encrypt it — but set this consistently going forward. See [Settings](settings.md#encryption).
* **OpenSearch has been removed.** Usage metrics are no longer stored in OpenSearch and the in-application Metrics dashboard has been removed. Metrics are now exposed in Prometheus format at `/actuator/prometheus`; collect and visualize them with your own monitoring stack. Remove the `OPENSEARCH_*` and `API_REQUESTS_INDEXING_ENABLED` variables. See [Monitoring and Logging](monitoring_and_logging.md).
* **Valkey is now optional.** If `CACHE_HOSTNAME` is unset, Philter uses an ephemeral in-memory cache. Configure Valkey for a durable, shared cache. See [Settings](settings.md#cache-settings).
* **PDF redaction is asynchronous by default.** `POST /api/filter` with a PDF now returns `202 Accepted` with a `documentId`; download the result from the [Documents API](api_and_sdks/api/documents_api.md). Append `?async=false` to keep the previous synchronous behavior. Text redaction is unchanged.
* **Policies are authored as JSON.** The visual policy builder has been replaced by a JSON editor in the dashboard; you can also build policies in the [policy editor](https://policies.philterd.ai/) and paste the JSON in.

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

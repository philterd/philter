# Upgrading Philter

We recommend reviewing the [Philter Release Notes](https://www.philterd.ai/philter-release-notes/) prior to upgrading.

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

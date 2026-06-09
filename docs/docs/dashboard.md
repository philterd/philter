# Dashboard

Philter includes a user interface dashboard that can be accessed at `http://your-philter-endpoint:8080`.

The dashboard is protected by a login screen. The default administrator account is `admin` / `admin`. On the first login with the default password, Philter requires you to set a new password before you can use the dashboard. See [Login Security](login_security.md) for details on the forced password change and the failed-login lockout.

> The Philter UI dashboard is intended for configuration testing and policy management. Use Philter's [API](api_and_sdks/api.md) for document redaction.

## Capabilities

The Philter dashboard provides a comprehensive interface for managing your redaction environment.

### Testing Philter

The **Dashboard** home page allows you to test Philter's configuration by submitting text or PDF documents. You can select a filter policy and see how Philter redacts the information. This is useful for fine-tuning your policies before deploying them to production.

### Policy Management

In the **Policies** section, you can:

*   **Create and Edit Policies**: Edit a policy's JSON directly, or build one in the [policy editor](https://policies.philterd.ai/) and paste the JSON in. Policies are validated on save.
*   **Managed Policies**: Access a library of pre-configured policies for common use cases (e.g., HIPAA, GDPR). You can clone these to create your own custom versions.
*   **Always/Never Redact Lists**: Define terms that should always or never be redacted across *all* of your policies.

### Metrics and Usage

Philter exposes redaction, token, and API-request metrics in Prometheus format at `/actuator/prometheus`. Collect and visualize them with your own monitoring stack (for example, Prometheus and Grafana). See [Monitoring and Logging](monitoring_and_logging.md).

### API Key Management

The **API** section allows you to manage the API keys used for authenticating with Philter's [API](api_and_sdks/api.md). You can also find links to official client SDKs for Java, .NET, and Go.

### Custom Lists

The **Custom Lists** section allows you to manage reusable lists of terms. These lists can be referenced in your filter policies to easily include or exclude large sets of specific values (e.g., internal project names, employee IDs).

### Contexts and Referential Integrity

The **Contexts** section is used to manage redaction contexts, which enable:

*   **Referential Integrity**: Ensures consistent replacements for the same sensitive information across multiple documents within a context.
*   **Disambiguation**: Improves accuracy by resolving entity type ambiguities.

### Redaction Ledger

The **Redaction Ledger** section provides a tamper-evident, hash-chained record of the individual redactions performed in any [context](redaction/ledgers.md) that has the ledger enabled. From this view you can:

*   **Browse and search** your ledger chains by document id or filename.
*   **Verify a chain**: open a document's chain to see each recorded redaction (type, replacement, position, and timestamp), along with a badge confirming whether the cryptographic chain is intact or has been tampered with.
*   **Export a chain** as a JSON document for evidence or external review.
*   **Purge entries**: ledger entries are kept indefinitely by default; you can delete an individual document's chain, or purge entries older than a chosen number of days.

When cross-user access is enabled, administrators can additionally review every user's ledger chains from the **All Ledger** tab. Ledgers are enabled per context; see [Redaction Ledgers](redaction/ledgers.md) for the full design.

### User Management

Admin users can access the **Admin** section to manage users.

*   **Add Users**: Create new user accounts by providing an email address, password, and role (`admin` or `user`).
*   **Change Passwords**: Update a user's password.
*   **Set Roles**: Change a user's role to `admin` or `user`. Note that users cannot change their own role.
*   **Delete Users**: Remove user accounts. This will also delete all of the user's data, including API keys, contexts, policies, and ledger entries. Users cannot delete their own account.

### Audit Log

Administrators can review and export Philter's [audit log](auditing.md) from **Admin → Audit Log**. The audit log records security-relevant actions: authentication failures, user and API-key changes, policy changes, redaction and ledger activity, and account-configuration changes. Choose a **From** and **To** date (a range of up to 30 days) and click **Download Audit Log (CSV)** to export the events for compliance review or incident investigation. Audit events never contain sensitive values. See [Auditing](auditing.md) for the full list of recorded events and the export details.

### Settings and Webhooks

The **Settings** section lets each user configure account-level options, including a [webhook](api_and_sdks/api/webhooks.md) URL and secret to receive a signed notification when an asynchronous redaction completes or fails. The [redaction ledger](redaction/ledgers.md) is enabled per context, not in account settings.
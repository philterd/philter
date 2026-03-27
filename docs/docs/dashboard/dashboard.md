# Dashboard

Philter includes a user interface dashboard that can be accessed at `http://your-philter-endpoint:8080:8080`.

The dashboard is protected by a login screen. The default username and password for the administrator is `admin` / `admin`. It is recommended to change the administrator password after logging in.

> The Philter UI dashboard is intended for configuration testing and policy management. Use Philter's [API](../api_and_sdks/api.md) for document redaction.

## Capabilities

The Philter dashboard provides a comprehensive interface for managing your redaction environment.

### Testing Philter

The **Dashboard** home page allows you to test Philter's configuration by submitting text or PDF documents. You can select a filter policy and see how Philter redacts the information. This is useful for fine-tuning your policies before deploying them to production.

### Policy Management

In the **Policies** section, you can:

*   **Create and Edit Policies**: Use the visual policy editor to define PII/PHI filters, specify terms to always or never redact, and configure advanced options.
*   **Managed Policies**: Access a library of pre-configured policies for common use cases (e.g., HIPAA, GDPR). You can clone these to create your own custom versions.
*   **Global Terms**: Define terms that should always or never be redacted across *all* policies.

### Metrics and Usage

The **Metrics** section provides visualizations of your redaction activity:

*   **Token Counts**: View the number of sensitive tokens identified over time.
*   **Redaction Counts**: Monitor the number of redactions performed.
*   **API Requests**: Track the volume of requests to Philter's API.
*   **CSV Reports**: Download detailed usage reports for auditing and compliance.

### API Key Management

The **API** section allows you to manage the API keys used for authenticating with Philter's [API](../api_and_sdks/api.md). You can also find links to official client SDKs for Java, .NET, and Go.

### Custom Lists

The **Custom Lists** section allows you to manage reusable lists of terms. These lists can be referenced in your filter policies to easily include or exclude large sets of specific values (e.g., internal project names, employee IDs).

### Contexts and Referential Integrity

The **Contexts** section is used to manage redaction contexts, which enable:

*   **Referential Integrity**: Ensures consistent replacements for the same sensitive information across multiple documents within a context.
*   **Entity Co-referencing (coref)**: Maintains consistency for entity mentions (e.g., "John Smith" and "John").
*   **Disambiguation**: Improves accuracy by resolving entity type ambiguities.

### User Management
 
 Admin users can access the **Admin** section to manage users.
 
 *   **Add Users**: Create new user accounts by providing an email address, password, and role (`admin` or `user`).
 *   **Change Passwords**: Update a user's password.
 *   **Set Roles**: Change a user's role to `admin` or `user`. Note that users cannot change their own role.
 *   **Delete Users**: Remove user accounts. This will also delete all of the user's data, including API keys, contexts, policies, and ledger entries. Users cannot delete their own account.
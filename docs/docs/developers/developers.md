# Developers

Philter is designed to be easily integrated into your existing workflows and applications. Whether you are building a custom application, a data pipeline, or a large-scale data processing system, our API and developer tools provide the flexibility and power you need to protect sensitive information at scale.

View the [Developer Quick Start](./developer_quick_start.md) to see API examples.

## API Integration

The primary way for developers to interact with Philter is through the REST API. The API provides programmatic access to all the platform's core capabilities, including:

*   **Automated Redaction**: Process documents and raw text for PII/PHI identification and redaction.
*   **Policy Management**: Create and manage your redaction policies as code.

To get started with the API, please refer to the [API Documentation](../developers/developer_quick_start.md).

## API Authentication

All requests to the Philter API require authentication using an API Key. You can manage your API keys in the [API Keys](../account/api_keys.md) section of your account.

Authentication is performed by including your API key in the `Authorization` header of every request:

```http
Authorization: Bearer <YOUR_API_KEY>
```

## Interactive API Reference

We provide a comprehensive and interactive API reference using Swagger UI. This allows you to explore every endpoint, understand request and response formats, and even test API calls directly from your browser.

*   **Philterd API Swagger UI**: [https://your-philter-endpoint:8080/swagger-ui/index.html](https://your-philter-endpoint:8080/swagger-ui/index.html)

## Developer Guidelines

When building integrations with Philterd Data Services, keep the following guidelines in mind:

*   **Security First**: Use firewall rules from your cloud provider or own hardware to restrict API access to trusted clients.
*   **Error Handling**: Your application should gracefully handle common API response codes (e.g., 401 Unauthorized, 403 Forbidden, 429 Too Many Requests).
*   **Iterative Development**: Use a separate [Context](../redaction/contexts.md) for testing and development to keep your production data organized.

## Need Support?

If you have technical questions or need assistance with your integration, our team is here to help. Contact us at [support@philterd.ai](mailto:support@philterd.ai).


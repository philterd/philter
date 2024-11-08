# API

Philter's API is divided into three parts, the [Filtering API](api/filtering_api.md) the [Policies API](api/policies_api.md), and the [Alerts API](api/alerts_api.md).

* [Filtering API](api/filtering_api.md) - The filtering API is used to redact text. With the filtering API, you can send text or PDF documents to Philter and receive back the redacted text or PDF document.
* [Policies API](api/policies_api.md) - The policies API allows you to create, modify, and delete [policies](../policies/filter_policies.md). Policies can also be created manually with access to Philter, but the API provides a programmatic way to manage policies.
* [Alerts API](api/alerts_api.md) - The alerts API allows you to get and delete [alerts](../other_features/alerts.md) that were generated during redaction.

The Philter [SDKs](sdks.md) provide convenient methods for using Philter's API methods for various programming languages.

## Securing Philter's API

Philter's API supports one-way and two-way SSL/TLS authentication. See Philter's [Settings](../settings.md) for more information.

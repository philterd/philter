# API

Philter's API is divided into five parts, the [Filtering API](api/filtering_api.md), the [Policies API](api/policies_api.md), the [Custom Lists API](api/custom_lists_api.md), the [Contexts API](api/contexts_api.md), and the [Alerts API](api/alerts_api.md).

* [Filtering API](api/filtering_api.md) - The filtering API is used to redact text. With the filtering API, you can send text or PDF documents to Philter and receive back the redacted text or PDF document.
* [Policies API](api/policies_api.md) - The policies API allows you to create, modify, and delete [policies](../policies/filter_policies.md). Policies can also be created manually with access to Philter, but the API provides a programmatic way to manage policies.
* [Custom Lists API](api/custom_lists_api.md) - The custom lists API allows you to create, modify, and delete custom lists. Custom lists are used by policies to identify terms to be redacted.
* [Contexts API](api/contexts_api.md) - The contexts API allows you to create, modify, and delete contexts. Contexts are used to maintain referential integrity across documents.

## Securing Philter's API

Philter's API supports one-way and two-way SSL/TLS authentication. See Philter's [Settings](../settings.md) for more information.

## SDKs

The Philter [SDKs](sdks.md) provide convenient methods for using Philter's API methods for various programming languages.

# Filter Policies

The types of sensitive information identified by Philter and how that information is de-identified are controlled through policies. Policies are JSON configurations stored in Philter's database. Create or update them through the [Policies API](../api_and_sdks/api/policies_api.md).

Each policy has a `name` that is used by Philter to apply the appropriate de-identification methods. The `name` is passed to Philter’s [API](../api_and_sdks/api/filtering_api.md) along with the text to be filtered when submitting text to Philter. This provides flexibility and allows you to de-identify different types of documents in differing manners with a single instance of Philter. For example, you may have a policy for bankruptcy documents and a separate policy for financial documents.

> There are [sample policies](sample_policies.md) available for immediate use or customization to fit your use-cases.


### The Structure of a Policy

A policy:

* Has a name unique within its owning account. Set the name in the required `name` query parameter when saving it; the local filename does not set the policy name.
* Must have a list of `identifiers` that are filters for sensitive information.
    * Each `identifier` , or filter, can have zero or more [filter strategies](filter_strategies.md). A filter strategy tells Philter how to manipulate that type of sensitive information when it is identified.
* Can have an optional list of terms or patterns of information to [ignore](ignoring_specific_information.md).
* Can have encryption keys to support [encryption](filter_strategies.md#the-fpe_encrypt_replace-filter-strategy) of sensitive information.

### An Example Policy

The following is an example policy. In the example below you can see the [types of sensitive information](filters.md) that are enabled and the strategy for manipulating each type when found. This policy identifies email addresses and phone numbers and redacts each with the format given.

```
{
   "identifiers": {
      "emailAddress": {
         "emailAddressFilterStrategies": [
            {
               "strategy": "REDACT",
               "redactionFormat": "{{{REDACTED-%t}}}"
            }
         ]
      },
      "phoneNumber": {
         "phoneNumberFilterStrategies": [
            {
               "strategy": "REDACT",
               "redactionFormat": "{{{REDACTED-%t}}}"
            }
         ]
      }
   }
}
```

When an email address is identified by this policy, the email address is replaced with the text `{{{REDACTED-email-address}}}`. The `%t` gets replaced by the type of the filter. Likewise, when a phone number is found it is replaced with the text `{{{REDACTED-phone-number}}}`. You are free to change the redaction formats to whatever fits your use-case. See [Filter Strategies](filter_strategies.md) for all replacement options.

Save this JSON locally as `email-and-phone-numbers.json` for the upload below. The API query parameter assigns the stored name; another account can use the same name independently.

### Applying a Policy to Text

Use an [API key](../account/api_keys.md) holding the `policies:write` and `redact` scopes, such as one created with [`POST /api/api-keys`](../api_and_sdks/api/api_keys_api.md#create-a-key) and `{"scopes":["policies:write","redact"]}`. Set `API_KEY` in your shell to that key. Upload the policy:

```bash
curl --fail-with-body -k -X POST "https://localhost:8080/api/policies?name=email-and-phone-numbers" \
  -H "Authorization: Bearer $API_KEY" -H "Content-Type: application/json" \
  --data-binary @email-and-phone-numbers.json
```

A successful save returns 201. Saving a name that already exists returns 409 with reason `policy_exists`; replace an existing policy with `PUT /api/policies/{name}`. The policy is available immediately; no restart is needed. Apply it to a text file:

```bash
curl --fail-with-body -k -X POST "https://localhost:8080/api/filter?p=email-and-phone-numbers" \
  -H "Authorization: Bearer $API_KEY" -H "Content-Type: text/plain" \
  --data-binary @file.txt
```

These examples use the Docker image's self-signed certificate. Use your deployment's host and omit `-k` when using a trusted certificate.

In this command, we have provided the parameter `p` along with a value that is the name of the policy we want to use for this request. If we had multiple policies in Philter we could choose a different policy for this request simply by changing the name given to the parameter `p`. For more details see Philter’s [API](../api_and_sdks/api.md).

Philter will process the contents of `file.txt` by applying the policy named `email-and-phone-numbers`. As we saw in the policy above, this policy redacts email addresses and phone numbers. Philter will return the redacted text in response to the API call.

To manipulate the sensitive information by methods other than redaction, see the [Filter Strategies](filter_strategies.md).

### Sampling Requests for Review

A policy can select a share of its requests for human spot review, so a person can check whole documents for values that were not detected. Set `config.review.samplingRate` to the probability, from `0.0` to `1.0`, that a request is selected:

```
{
   "identifiers": { ... },
   "config": {
      "review": {
         "samplingRate": 0.10
      }
   }
}
```

The default is `0.0`, which selects no requests; `1.0` selects every request. Each request is selected independently at random, once per request whatever its size or format, and no count is kept between requests, so the share selected converges on the rate but is not exact over a small number of requests. For a PDF redacted with [`page_policy`](../api_and_sdks/api/filtering_api.md), the rate of the policy named by `p` applies and the page set policies' rates are ignored. A rate outside `0.0` to `1.0` is refused when the policy is saved. Selection does not change the redacted output.

When a request is selected:

* The `/api/filter` or `/api/explain` response has the header `X-Philter-Review: sampled`, and the `/api/explain` body has `"reviewSampled": true`. The header is absent when the request is not selected. An asynchronous document is selected when it is accepted, with the policy version pinned for it, so the `202 Accepted` response carries the header. See the [Filtering API](../api_and_sdks/api/filtering_api.md).
* The [audit log](../auditing.md) records a `redaction_review_sampled` event with the request ID, the document ID, and the policy name and version.

What happens to a selected request, such as queuing it for a reviewer, is up to your application. The header requests a check; its absence does not mean a result is correct. Detection is probabilistic, so validate results against your own data whether or not a request was selected.

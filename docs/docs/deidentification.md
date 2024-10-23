# De-identification Methods

There are several ways data can be de-identified, and which you use depends on the types of data you want to de-identify and your use-case for de-identifying the data. The terminology around the different methods is often used interchangeably, but there are differences between each method.

> In this User's Guide, we may use the terms `filter` and `redact` interchangeably.

In Philter, de-identification methods vary for each type of sensitive information. For example, all types can be replaced or redacted, but only dates can be shifted and only zip codes can be truncated. How a de-identification method is applied by Philter is called a filter strategy. Each type of sensitive information can have one or more filter strategies, and the combination of the filter strategies you select is called a policy. A policy determines how a document will be de-identified.

The following is a list of de-identification methods that describes how each method works and its applicability to Philter. Deidentifying a document is likely to require a combination of the following methods. For instance, you may want to redact names, encrypt credit card numbers, and shift appointment dates.

## Summary of Deidentification Methods

<table><thead><tr><th width="268">De-identification Method</th><th>Description</th></tr></thead><tbody><tr><td>Replacement</td><td>Replaces sensitive information with a defined value. For example, you might want to replace a credit card number with the literal value "CREDIT_CARD_NUMBER".</td></tr><tr><td>Redaction and Masking</td><td>Removes sensitive information. Philter gives you a choice of how to remove the sensitive information, whether it is by replacing it with ***** (masking) or by some other set of characters.</td></tr><tr><td>Encryption</td><td>Encrypts sensitive information.</td></tr><tr><td>Date Shifting</td><td>Shifts dates either forward or backward by some interval.</td></tr><tr><td>Bucketing</td><td>Categorizes data into buckets based on the data. Examples of bucketing is Philter can bucket dates into years, and zip codes by population.</td></tr></tbody></table>

> A difference between [Philter](https://philterd.ai/philter/) and other services is that Philter does not send your data to a third party for de-identification. Philter runs in your cloud and your data stays in your cloud.

## Deidentification Methods

### Redaction and Masking

Redaction and masking are two methods of de-identification that are often used interchangeably. The term redaction refers to removing a sensitive value from a document. When we hear the term redaction we often think of an image of a document with black bars across pieces of the text.

Masking is similar to redaction but allows for configuring how the sensitive value is removed. The most common example is using asterisks (i.e. \*\*\*\*\*\*) in place of a sensitive value.

### Replacement

Replacement is a method of de-identification that simply replaces a sensitive value with another value. Replacement is useful when the sensitive value is not needed once the document has been de-identified. Philter can replace a sensitive value with a preset value or with a random value.

In Philter's filter strategies, replacement is achieved by using the strategy to `REDACT`, `STATIC_REPLACE` , or `RANDOM_REPLACE` .

### Bucketing

### Date Shifting

### Encryption
# Referential Integrity

Referential integrity in the context of Philter is the process of replacing certain values with random but similar values while maintaining the same replacement value for the same original value. For example, the identified name of "John Smith" may be replaced with "David Jones", or an identified phone number of 123-555-9358 may be replaced by 842-436-2042. A [VIN](../policies/filters/common_filters/vins.md) number will be replaced by a 17 character randomly selected VIN number that adheres to the standard for VIN numbers.

Referential integrity is useful in instances where you want to remove sensitive information from text without changing the meaning of the text and while maintaining the same replacement values for the same original values. This is enabled for each type of sensitive information in the policy by setting the filter strategy to `RANDOM_REPLACE`. (See [Policies](../policies/filter_policies.md) for more information.)

## Referential Integrity

Referential integrity refers to the process of always anonymizing the same sensitive information with the same replacement values. For example, if the name "John Smith" is randomly replaced with "Pete Baker", all other occurrences of "John Smith" will also be replaced by "Pete Baker."

Referential integrity can be done on the document level or on the context level. When enabled on the document level, "John Smith" will only be replaced by "Pete Baker" in the same document. If "John Smith" occurs in a separate document it will be anonymized with a different random name. When enabled on the context level, "John Smith" will be replaced by "Pete Baker" whenever "John Smith" is found in all documents in the same context.

Enabling referential integrity on the context level requires a cache to store the sensitive information and the corresponding replacement values. Philter uses a Valkey cache when configured, and otherwise falls back to an in-memory cache. See Philter's [Settings](../settings.md#cache-settings) on how to configure the cache.

**Referential integrity does not depend on the cache.** A replacement is created once in MongoDB under a unique index on the token, with concurrent writers handed the stored value, and a cache miss reads MongoDB, so every instance resolves a token to the same replacement whatever its cache holds. A shared [Valkey](https://valkey.io/) cache is still recommended for a multi-instance deployment, for the reasons in [Caching](../caching.md#valkeyredis-cache-distributed-deployments): it raises the fleet-wide hit rate, reduces MongoDB traffic, carries invalidation to every instance when a mapping is overwritten or deleted, and three other behaviours depend on it. The in-memory cache is ephemeral and is lost on restart.

## Limits and behavior to be aware of

* **Context capacity.** Context-level referential integrity holds up to `MAX_CONTEXT_SIZE` distinct values per context (default `10000`; see [Contexts](../redaction/contexts.md)). When a context exceeds that limit, the least-recently-read mapping is evicted to make room. If an evicted value is then seen again in the same context, it is treated as new and receives a different replacement. For consistent replacement across a very large or long-lived context, raise `MAX_CONTEXT_SIZE` so the number of distinct values stays within the limit.
* **Case sensitivity.** Values are matched exactly, including case. "John Smith" and "john smith" are treated as different values and may receive different replacements within the same context.

The referential integrity cache will contain PHI. It is important that you take the necessary precautions to secure the cache and all communication to and from the cache.

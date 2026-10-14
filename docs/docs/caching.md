# Caching

Philter caches several things to avoid repeated database lookups on the hot path:

* **API keys**, so that authenticating a request does not query the database on every call.
* **Context entries** (the token to replacement mappings used for consistent redaction within a context), so that repeated redactions in the same context reuse prior replacements quickly.
* **Sign-in counters**: failed [sign-ins](api_and_sdks/api/sign_in_api.md) per username, username locks, and sign-in requests per client address, used to [lock and rate-limit](api_and_sdks/api/sign_in_api.md#lockout-and-rate-limiting) password sign-in.

These use the shared cache backend described below. One additional cache is always kept in-process: the per-request **policy and redact-list cache** used by the filtering endpoints. It is never written to Valkey/Redis, because stored policies can contain PII in their filter-strategy conditions and the always/never redact terms are themselves sensitive. Each instance rebuilds it on a short TTL, so it needs no shared backend.

Philter supports two cache backends.

## In-memory cache (default)

When no cache host is configured, Philter uses a built-in, in-process cache. It requires no extra infrastructure and is the right choice for a single Philter instance. Because it lives inside the JVM, the cached data is ephemeral (it is lost on restart) and is **not shared between instances**. The Docker Compose configuration shipped with Philter runs this way, and Philter logs a warning at startup noting that the cache is in-memory.

Expired string entries and context hashes are reclaimed by a process-wide background
task, with a one-second delay between cleanup passes, even if those keys are never read again.
Reads also reject expired values immediately. Closing an individual cache wrapper does not stop
cleanup for other users of the shared store.

The in-memory backend bounds live and non-expiring entries by entry count and accounted bytes. At capacity, writes become cache misses. See the capacity settings below.

## Valkey/Redis cache (distributed deployments)

`CACHE_HOSTNAME` is **required when more than one Philter instance shares a database**: every instance must use the same Valkey/Redis, and running several instances without one is not a supported deployment. Without a shared cache, each instance keeps its own in-process cache, so an API key revoked on one instance would remain valid in another instance's cache until that entry expires (`API_KEY_CACHE_TTL_SECONDS`), and the per-address [sign-in](sign_in_security.md#lockout-and-rate-limiting) rate limit would be counted per instance. A single instance needs no shared cache.

Newly created context replacements are **not** in this list, because consistency there is enforced in
the database by a unique constraint on the token, an atomic insert, and concurrent writers being handed the stored
winner, and because a cache miss reads the database, so instances agree on a replacement whatever their caches
hold. Separate caches lower the fleet-wide hit rate and add database traffic rather than changing the answer.

Invalidation is the part that does need a shared cache. When a mapping is overwritten by an import or
deleted, Philter forgets it in the cache it can reach, which is every instance's when they share one and
only the local one when they do not. Note that eviction does not make a change instantaneous even with a
shared cache: a reader that loaded the old value just before the write can still store it again afterwards.

Pointing every instance at the same [Valkey](https://valkey.io/) (or Redis) server gives a durable, shared
cache that resolves both the stale-key and the invalidation problems.

### Horizontal scaling

API requests (`/api/**`) are stateless: each one is authenticated from its own API key and depends on no server-side session, so any instance can serve any request and you can run as many instances as you need behind a plain load balancer. The shared cache above is what keeps those instances consistent.

### Configuration

Set the following environment variables on each Philter instance. When `CACHE_HOSTNAME` is unset or blank, Philter falls back to the in-memory cache.

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `CACHE_HOSTNAME` | Yes, to enable Valkey/Redis | _(unset, uses in-memory)_ | Hostname of the Valkey/Redis server. |
| `CACHE_PORT` | No | `6379` | Port of the Valkey/Redis server. |
| `CACHE_PASSWORD` | No | _(none)_ | Password, if the server requires authentication. |
| `CACHE_SSL` | No | `false` | Set to `true` to connect over TLS. |

### Protecting the cache

Treat Valkey like the database. Philter encrypts what it stores there, but anything that can reach the server can still delete entries, which costs only a database read, and can see how much is cached and when.

* **Values are encrypted and authenticated.** Each value is encrypted with AES-256-GCM under a key derived from `PHILTER_ENCRYPTION_KEY`, and bound to the name it is stored under. A value that was not written by Philter, was copied from another entry, or was written back after it expired reads as absent, so Philter goes to the database instead. This is designed so that something able to write to Valkey cannot make Philter accept an API key it did not issue, or change the replacements written into redacted output.
* **Names are hashed.** Key and field names are replaced by keyed hashes, so the cache does not show user IDs, context names, or API key hashes.
* **Not covered:** the per-address sign-in rate limit counts, which Valkey increments itself and so are stored as plain numbers under hashed names. Changing them can only weaken that limit; account lockout is kept in the database. Context replacements carry no expiry of their own, so a replacement removed from the cache can be written back until its context's entry expires; it is still a replacement Philter issued for that same value.

Every instance sharing a cache must use the same `PHILTER_ENCRYPTION_KEY`, as they must for a shared database. When the key changes, entries written under the old key read as absent and are replaced as they are used. Philter logs one warning the first time it finds an entry it cannot decrypt.

When `CACHE_HOSTNAME` names a host other than this machine and `CACHE_PASSWORD` is empty or `CACHE_SSL` is `false`, Philter logs a warning at startup. Set a password, use TLS unless the cache is reachable only on a private network, and allow only Philter's instances to connect.

### Cache lifetimes (TTL)

Two caches keep entries for a short, configurable time. Both default to a low value so stale data is short-lived; raise them to trade freshness for fewer database reads.

| Variable | Default | Description |
|----------|---------|-------------|
| `API_KEY_CACHE_TTL_SECONDS` | `60` | How long a resolved API key is cached. Revoking a key through the API evicts it from the cache immediately; this TTL bounds how long a key revoked out-of-band (for example, edited directly in the database) keeps working. |
| `REDACTION_CACHE_TTL_SECONDS` | `60` | How long the filtering endpoints keep a user's policy and always/never redact lists in-process. A cached copy is used only while its revisions match the stored ones, checked on every request, so an edited or deleted policy or list applies to the next request on every instance, with or without a shared cache; this setting bounds memory, not staleness. This cache is always in-process and is never written to Valkey/Redis. |

### Docker Compose example

The bundled `docker-compose.yml` uses the in-memory cache. To add a shared cache, add a Valkey service with a password and set `CACHE_HOSTNAME` and `CACHE_PASSWORD` on the `philter` service to point at it. Valkey is reachable only on the Compose network here, so this example does not use TLS, and Philter logs the warning described in [Protecting the cache](#protecting-the-cache):

```yaml
  philter:
    # ...existing configuration...
    environment:
      CACHE_HOSTNAME: valkey
      CACHE_PORT: 6379
      CACHE_PASSWORD: ${CACHE_PASSWORD:?set CACHE_PASSWORD}
      CACHE_SSL: false
      # ...existing environment...
    depends_on:
      # ...existing dependencies...
      valkey:
        condition: service_healthy

  valkey:
    image: valkey/valkey:latest
    networks:
      - philter
    environment:
      CACHE_PASSWORD: ${CACHE_PASSWORD:?set CACHE_PASSWORD}
    command: ["sh", "-c", "exec valkey-server --save 60 1 --loglevel warning --requirepass \"$$CACHE_PASSWORD\""]
    healthcheck:
      test: ["CMD-SHELL", "valkey-cli --no-auth-warning -a \"$$CACHE_PASSWORD\" ping"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 10s
```

The in-memory backend bounds both entry count (including every hash field) and accounted retained bytes. Defaults are 100,000 entries and 64 MiB; configure `IN_MEMORY_CACHE_MAX_ENTRIES` and `IN_MEMORY_CACHE_MAX_BYTES` with positive values. A full store makes room rather than refusing: it drops expired entries, then those nearest to expiring, so a heavy user lowers the hit rate instead of stopping caching. Sign-in counts are kept in a separate store with the same bounds, so no amount of cached data can push them out. Increasing capacity does not make the in-memory backend suitable for multiple instances; those still require a shared cache.

## Context mapping freshness

Every cached mapping has a fixed maximum age of 3,600 seconds, measured from the start of the database observation that populated it. Reads reject expired mappings even if unrelated writes keep the containing context hash alive. A delayed cache fill cannot restart that age. This bounds stale mappings after import/deletion or a concurrent fill; it does not provide immediate consistency after mutation. The bound assumes reasonably synchronized instance clocks.

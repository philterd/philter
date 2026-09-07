# Caching

Philter caches several things to avoid repeated database lookups on the hot path:

* **API keys**, so that authenticating a request does not query MongoDB on every call.
* **Context entries** (the token to replacement mappings used for consistent redaction within a context), so that repeated redactions in the same context reuse prior replacements quickly.
* **Login attempt counters**, used to lock an account after too many failed logins (see [Login Security](login_security.md)).

These use the shared cache backend described below. One additional cache is always kept in-process: the per-request **policy and redact-list cache** used by the filtering endpoints. It is and is never written to Valkey/Redis, because stored policies can contain PII in their filter-strategy conditions and the always/never redact terms are themselves sensitive. Each instance rebuilds it on a short TTL, so it needs no shared backend.

Philter supports two cache backends.

## In-memory cache (default)

When no cache host is configured, Philter uses a built-in, in-process cache. It requires no extra infrastructure and is the right choice for a single Philter instance. Because it lives inside the JVM, the cached data is ephemeral (it is lost on restart) and is **not shared between instances**. The Docker Compose configuration shipped with Philter runs this way, and Philter logs a warning at startup noting that the cache is in-memory.

Expired string entries, login counters, and context hashes are reclaimed by a process-wide background
task, with a one-second delay between cleanup passes, even if those keys are never read again.
Reads also reject expired values immediately. Closing an individual cache wrapper does not stop
cleanup for other users of the shared store.

The in-memory backend bounds live and non-expiring entries by entry count and accounted bytes. At capacity, ordinary writes become cache misses; counter overflow blocks login for the failure window. See the capacity settings below.

## Valkey/Redis cache (distributed deployments)

When you run more than one Philter instance behind a load balancer, the instances **must** share a cache. Configuring `CACHE_HOSTNAME` is effectively required, not optional, for any multi-instance deployment. Without a shared cache, each instance keeps its own in-process cache, which causes:

* **Login lockout is evadable (security).** Failed-login counters are kept per instance, so an attacker who spreads failed logins across instances is never locked out, because each instance only sees a fraction of the attempts. A shared cache enforces the lockout across the whole fleet. See [Login Security](login_security.md).
* **Stale credentials.** An API key revoked on one instance could remain valid in another instance's cache until that entry expires.

Newly created context replacements are **not** in this list, because consistency there is enforced in
MongoDB by a unique index on the token, an atomic upsert, and concurrent writers being handed the stored
winner, and because a cache miss reads MongoDB, so instances agree on a replacement whatever their caches
hold. Separate caches lower the fleet-wide hit rate and add MongoDB traffic rather than changing the answer.

Invalidation is the part that does need a shared cache. When a mapping is overwritten by an import or
deleted, Philter forgets it in the cache it can reach, which is every instance's when they share one and
only the local one when they do not. Note that eviction does not make a change instantaneous even with a
shared cache: a reader that loaded the old value just before the write can still store it again afterwards.

Pointing every instance at the same [Valkey](https://valkey.io/) (or Redis) server gives a durable, shared
cache that resolves both problems.

### Horizontal scaling: the API, not the dashboard

Only the API scales horizontally. API requests (`/api/**`) are stateless: each one is authenticated from its own API key and depends on no server-side session, so any instance can serve any request and you can run as many instances as you need behind a plain load balancer. The shared cache above is what keeps those instances consistent.

The Vaadin dashboard (the web UI) is session-based. Its server-side session cannot be serialized to Valkey/Redis and is not shared between instances, so the dashboard does not scale across instances and runs as a single instance. This is not a bottleneck in practice: the dashboard is a low-traffic admin console, while the redaction workload that needs to scale goes through the stateless API. The dashboard's inactivity timeout is described in [Login Security](login_security.md).

### Configuration

Set the following environment variables on each Philter instance. When `CACHE_HOSTNAME` is unset or blank, Philter falls back to the in-memory cache.

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `CACHE_HOSTNAME` | Yes, to enable Valkey/Redis | _(unset, uses in-memory)_ | Hostname of the Valkey/Redis server. |
| `CACHE_PORT` | No | `6379` | Port of the Valkey/Redis server. |
| `CACHE_PASSWORD` | No | _(none)_ | Password, if the server requires authentication. |
| `CACHE_SSL` | No | `false` | Set to `true` to connect over TLS. |

### Cache lifetimes (TTL)

Two caches keep entries for a short, configurable time. Both default to a low value so stale data is short-lived; raise them to trade freshness for fewer database reads.

| Variable | Default | Description |
|----------|---------|-------------|
| `API_KEY_CACHE_TTL_SECONDS` | `60` | How long a resolved API key is cached. Deleting a key through the dashboard or API evicts it from the cache immediately; this TTL bounds how long a key revoked out-of-band (for example, edited directly in the database) keeps working. |
| `REDACTION_CACHE_TTL_SECONDS` | `60` | How long the filtering endpoints cache a user's policy and always/never redact lists in-process. Also the upper bound on how long an edited or deleted policy / redact list keeps being used. This cache is always in-process and is never written to Valkey/Redis. |

### Docker Compose example

The bundled `docker-compose.yml` uses the in-memory cache. To add a shared cache, add a Valkey service and set `CACHE_HOSTNAME` on the `philter` service to point at it:

```yaml
  philter:
    # ...existing configuration...
    environment:
      CACHE_HOSTNAME: valkey
      CACHE_PORT: 6379
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
    command: valkey-server --save 60 1 --loglevel warning
    healthcheck:
      test: ["CMD", "valkey-cli", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 10s
```

The in-memory backend bounds both entry count (including every hash field) and accounted retained bytes. Defaults are 100,000 entries and 64 MiB; configure `IN_MEMORY_CACHE_MAX_ENTRIES` and `IN_MEMORY_CACHE_MAX_BYTES` with positive values. Ordinary overflow becomes a cache miss. Login counters are preserved, and counter overflow blocks dashboard login for the failure window. Increasing capacity does not make the in-memory backend suitable for multiple instances; those still require a shared cache.

## Context mapping freshness

Every cached mapping has a fixed maximum age of 3,600 seconds, measured from the start of the database observation that populated it. Reads reject expired mappings even if unrelated writes keep the containing context hash alive. A delayed cache fill cannot restart that age. This bounds stale mappings after import/deletion or a concurrent fill; it does not provide immediate consistency after mutation. The bound assumes reasonably synchronized instance clocks.

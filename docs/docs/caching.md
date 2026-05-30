# Caching

Philter caches two things to avoid repeated database lookups on the hot path:

* **API keys**, so that authenticating a request does not query MongoDB on every call.
* **Context entries** (the token to replacement mappings used for consistent redaction within a context), so that repeated redactions in the same context reuse prior replacements quickly.

Philter supports two cache backends.

## In-memory cache (default)

When no cache host is configured, Philter uses a built-in, in-process cache. It requires no extra infrastructure and is the right choice for a single Philter instance. Because it lives inside the JVM, the cached data is ephemeral (it is lost on restart) and is **not shared between instances**. The Docker Compose configuration shipped with Philter runs this way, and Philter logs a warning at startup noting that the cache is in-memory.

## Valkey/Redis cache (distributed deployments)

When you run more than one Philter instance behind a load balancer, the instances must share a cache. Without a shared cache:

* An API key revoked on one instance could remain valid in another instance's cache until that entry expires.
* Context replacements written by one instance would not be visible to the others, so the same input value could be redacted to different replacements depending on which instance handled the request.

Pointing every instance at the same [Valkey](https://valkey.io/) (or Redis) server gives a durable, shared cache that resolves both problems.

### Configuration

Set the following environment variables on each Philter instance. When `CACHE_HOSTNAME` is unset or blank, Philter falls back to the in-memory cache.

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `CACHE_HOSTNAME` | Yes, to enable Valkey/Redis | _(unset, uses in-memory)_ | Hostname of the Valkey/Redis server. |
| `CACHE_PORT` | No | `6379` | Port of the Valkey/Redis server. |
| `CACHE_PASSWORD` | No | _(none)_ | Password, if the server requires authentication. |
| `CACHE_SSL` | No | `false` | Set to `true` to connect over TLS. |

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

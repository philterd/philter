# Settings

Philter has settings to control how it operates. The settings and how to configure each are described below.

> The configuration for the types of sensitive information that Philter identifies are defined
> in [filter policies](policies/filter_policies.md) outside of Philter's configuration properties described on this page.

## Configuring Philter

### The Philter Settings File

Philter looks for its settings in a `philter.properties` file in the current directory.

### Using Environment Variables

Properties can also be set via environment variables. Environment variables take precedence over properties set in `philter.properties`.

## Database Settings

Philter requires a MongoDB database to store policies and other data. See [Database](database.md) for what is stored and for connection-string examples (authentication, replica sets, and Atlas).

| Environment Variable | Description | Default Value |
|----------------------|-------------|---------------|
| `MONGODB_CONNECTION_STRING` | The MongoDB connection string. | `mongodb://localhost:27017` |

## Encryption

Philter encrypts sensitive data at rest and requires an encryption key. Philter will not start if the key is missing or invalid.

| Environment Variable | Description | Default Value |
|----------------------|-------------|---------------|
| `PHILTER_ENCRYPTION_KEY` | A base64-encoded 32-byte (AES-256) key. Generate one with `openssl rand -base64 32`. Use the same value across restarts and instances. | (none — required) |

## Cache Settings

The cache is used for API key and context caching. Philter supports Valkey/Redis as the backend cache. The cache is **optional**: when `CACHE_HOSTNAME` is unset or blank, Philter uses an in-memory cache instead. The in-memory cache is ephemeral — it is not shared across instances and is lost on restart — and a warning is printed at startup. Configure Valkey for a durable, shared cache.

| Environment Variable | Description | Default Value |
|----------------------|-------------|---------------|
| `CACHE_HOSTNAME` | The hostname or IP address of the Valkey cache. Leave unset to use an in-memory cache. | (empty — in-memory) |
| `CACHE_PORT` | The Valkey port. | `6379` |
| `CACHE_PASSWORD` | The Valkey password. | (empty) |
| `CACHE_SSL` | Whether to use SSL for communication with the Valkey cache. | `false` |

## Metrics

Philter exposes metrics in Prometheus format at `/actuator/prometheus`. See [Monitoring and Logging](monitoring_and_logging.md). There are no metrics-storage settings to configure.

## API Access

| Environment Variable | Description | Default Value |
|----------------------|-------------|---------------|
| `API_IP_ALLOWLIST` | Optional comma-separated list of IPv4 addresses/CIDR ranges allowed to call the API. When set, authenticated requests from other addresses receive `403 Forbidden`. A bare address is treated as a single host. IPv4 only. | (empty, allow all) |

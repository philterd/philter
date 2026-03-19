# Referential Integrity with Valkey

The referential integrity feature in [Philter](https://www.philterd.ai/philter/) ensures that filtered values are anonymized consistently across documents or contexts. When Philter is deployed in a cluster and is using referential integrity across contexts, a Valkey cache is required. The cache stores the replacement values so that all instances of Philter have access to the same replacement values for the same original values.

> The Valkey cache will contain PHI. It is important to prepare your Valkey cache such that it can contain PHI.

### Configuring Valkey Cache

To enable Philter to use the Valkey cache, set the following environment variables for Philter:

| Environment Variable | Description | Default Value |
|----------------------|-------------|---------------|
| `CACHE_HOSTNAME` | The hostname or IP address of the Valkey cache. | `localhost` |
| `CACHE_PASSWORD` | The Valkey password. | (empty) |
| `CACHE_SSL` | Whether or not to use SSL for communication with the Valkey cache. | `false` |

Replace `localhost` with the IP address or host name of your Valkey cache.

> If you are using Valkey on AWS ElastiCache see [ElastiCache for Valkey In-Transit Encryption (TLS)](https://docs.aws.amazon.com/AmazonElastiCache/latest/red-ug/in-transit-encryption.html) for information on using in-transit encryption.

### Restart Philter

After starting (or restarting) Philter, Philter will use the Valkey cache for referential integrity across contexts.

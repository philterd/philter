# Consistent Anonymization with Redis

The consistent anonymization feature in [Philter](https://www.philterd.ai/philter/) ensures that filtered values are anonymized consistently across documents or contexts. When Philter is deployed in a cluster and is using consistent anonymization across contexts, a Redis cache is required. The cache stores the anonymized values so that all instances of Philter have access to the values.

> The Redis cache will contain PHI. It is important to prepare your Redis cache such that it can contain PHI.


### Enabling Consistent Anonymization

To enable consistent anonymization in Philter set the following property in Philter's configuration:

```
consistent.anonymization=true
consistent.anonymization.scope=context
```

### Configuring Redis Cache

To enable Philter to use the Redis cache, set the following options in Philter's configuration:

```
anonymization.cache.service=redis
anonymization.cache.service.host=127.0.0.1
anonymization.cache.service.port=6379
anonymization.cache.service.ssl=true
```

Replace `127.0.0.1` with the IP address or host name of your Redis cache.

> If you are using Redis on AWS ElastiCache see [ElastiCache for Redis In-Transit Encryption (TLS)](https://docs.aws.amazon.com/AmazonElastiCache/latest/red-ug/in-transit-encryption.html) for information on using in-transit encryption.


### Restart Philter

After starting (or restarting) Philter, Philter will use the Redis cache for consistent anonymization across contexts. You can restart Philter with the command:

```
sudo systemctl restart philter.service
```

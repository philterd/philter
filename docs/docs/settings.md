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

Philter requires a MongoDB database to store policies and other data.

| Environment Variable | Description | Default Value |
|----------------------|-------------|---------------|
| `MONGODB_CONNECTION_STRING` | The MongoDB connection string. | `mongodb://localhost:27017` |

## Cache Settings

The cache service is used for API key and context caching. Philter supports Valkey as the backend cache.

| Environment Variable | Description | Default Value |
|----------------------|-------------|---------------|
| `CACHE_HOSTNAME` | The hostname or IP address of the Valkey cache. | `localhost` |
| `CACHE_PASSWORD` | The Valkey password. | (empty) |
| `CACHE_SSL` | Whether or not to use SSL for communication with the Valkey cache. | `false` |

## Usage and Auditing Settings (OpenSearch)

Philter can send redaction and API request usage data to OpenSearch.

| Environment Variable | Description | Default Value |
|----------------------|-------------|---------------|
| `OPENSEARCH_HOST` | The OpenSearch hostname. | `localhost` |
| `OPENSEARCH_PORT` | The OpenSearch port. | `9200` |
| `OPENSEARCH_SCHEME` | The OpenSearch scheme (`http` or `https`). | `http` |
| `OPENSEARCH_USERNAME` | The OpenSearch username. | (empty) |
| `OPENSEARCH_PASSWORD` | The OpenSearch password. | (empty) |
| `REDACTIONS_INDEX_NAME` | The name of the index for redaction usage. | `pds-redactions` |
| `API_REQUESTS_INDEX_NAME` | The name of the index for API request usage. | `pds-api-requests` |

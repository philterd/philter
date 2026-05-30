# System Requirements

When launched from a cloud marketplace, Philter is pre-configured and contains all required dependencies.

Philter requires the following:

* 2 vCPU (e.g., m5.large instance type on AWS)
* 8 GB of RAM (16 GB recommended)
* Java 21
* MongoDB
* Valkey (optional — if not configured, Philter uses an in-memory cache that is not shared across instances and is lost on restart)

Philter also requires an encryption key to be configured via the `PHILTER_ENCRYPTION_KEY` environment variable; see [Settings](settings.md#encryption). Metrics are exposed for [Prometheus](monitoring_and_logging.md) and require no additional dependency.

## Supported Operating Systems

Philter is designed to run on Linux-based systems. It has been tested and is known to work on Ubuntu, CentOS, and Amazon Linux. Other Linux distributions may also work but are not officially supported.
# System Requirements

When launched from a cloud marketplace, Philter is pre-configured and contains all required dependencies. It stores its data with SQLite, on the virtual machine's disk.

Philter requires the following:

* 2 vCPU (e.g., m5.large instance type on AWS)
* 8 GB of RAM (16 GB recommended)
* Java 25
* Storage: nothing to install for SQLite, the default, which keeps Philter's data in a file for a single instance; or MongoDB, which several instances can share (see [Database](database.md#supported-mongodb-versions) for the version to use)
* Valkey (optional; if not configured, Philter uses an in-memory cache that is not shared across instances and is lost on restart)

Philter also requires an encryption key to be configured via the `PHILTER_ENCRYPTION_KEY` environment variable; see [Settings](settings.md#encryption). On a fresh install it requires `PHILTER_BOOTSTRAP_API_KEY`, which it needs until the `admin` user has had an API key; see [API Access](settings.md#api-access). Metrics are exposed for [Prometheus](monitoring_and_logging.md) and require no additional dependency.

## Memory

PDF redaction renders each page it redacts to an image in memory, so memory use grows with how many PDFs are redacted at once. At the default limits, allow about 500 MB of heap for each PDF in [`PDF_MAX_CONCURRENT`](settings.md#redaction-engine) (2 by default), on top of what Philter otherwise uses. The Docker image gives the JVM 75% of the container's memory for its heap (`-XX:MaxRAMPercentage=75`); when running the jar directly, set `-Xmx` or `-XX:MaxRAMPercentage`, since the JVM otherwise takes only a quarter.

Philter refuses PDFs that exceed its [PDF limits](settings.md#redaction-engine) before rendering them, but a PDF built to exhaust memory can still find a case the limits do not cover. Run Philter with a container memory limit and a restart policy, so such a document stops one instance rather than the host, and send PDFs from untrusted sources [asynchronously](api_and_sdks/api/filtering_api.md), where they are redacted one at a time.

## Supported Operating Systems

Philter is designed to run on Linux-based systems. It has been tested and is known to work on Ubuntu, CentOS, and Amazon Linux. Other Linux distributions may also work but are not officially supported.
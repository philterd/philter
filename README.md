# Philter

> [!NOTE]
> The `main` branch is currently for Philter 4.0.0 development. For 3.x, refer to the tags. The latest of which is `3.4.0`.

Philter is an API-based application to identify and manipulate (redact, anonymize, and more) PII, PHI, and other sensitive information.

Philter is built upon the open source PII and PHI redaction engine [Phileas](https://github.com/philterd/phileas). Philter provides an API on top of Phileas that allows for redaction and management of filtering policies.

Philter was released as open source under the Apache License, version 2.0, in July 2024 for version 2.6.0, but Philter dates back to 2019. See the [Release Notes](https://github.com/philterd/philter/blob/main/RELEASE_NOTES.md) for a description of past versions.

For Philter's User Guide please see https://philterd.github.io/philter/.

## Philter on the Cloud Marketplaces

Philter is available on the cloud marketplaces as a turnkey redaction solution. These cloud images are pre-configured and ready to be used immediately after launch.

* [Philter on the AWS Marketplace](https://aws.amazon.com/marketplace/pp/B07YVB8FFT?ref=_ptnr_philterd)
* [Philter on the Google Cloud Marketplace](https://console.cloud.google.com/marketplace/product/philterd-public/philter)
* [Philter on the Azure Marketplace](https://azuremarketplace.microsoft.com/en-us/marketplace/apps/philterdllc1687189098111.philter?tab=Overview)


## Building and Running Philter

Philter is built and run with Java 25. It is built with Maven:

```
mvn clean install
```

To run Philter:

```
./compose.sh
./compose.sh up
```

`compose.sh` passes its arguments through to `docker compose`, defaulting to `build`.

On its first run the script generates two secrets into `.env` and reuses them afterwards:

* `PHILTER_ENCRYPTION_KEY` encrypts sensitive data at rest. Keep it safe and use the same
  value across restarts and instances: Philter refuses to start without it, and data
  encrypted with it cannot be recovered if the key is lost or changed.
* `PHILTER_BOOTSTRAP_API_KEY` is seeded onto the `admin` user at first start so the API
  works without visiting the dashboard. Revoke it in the dashboard when you no longer
  need it.

To supply either yourself, put it in `.env` before the first run and the script keeps it.

Once the containers are running, submit text to Philter's API for redaction:

```
API_KEY=$(grep PHILTER_BOOTSTRAP_API_KEY .env | cut -d= -f2)

curl -k "https://localhost:8080/api/filter" --data "George Washington lives in 90210 and his SSN was 123-45-6789." -H "Content-type: text/plain" -H "Authorization: Bearer $API_KEY"
```

You can also access the UI at https://localhost:8080. Sign in as `admin` / `admin`; you are
required to set a new password before you can use the dashboard.

Interactive API documentation (Swagger UI) is available at https://localhost:8080/swagger-ui/index.html.

Philter serves HTTPS using a self-signed certificate that it generates the first time it starts, so `curl` needs `-k` and your browser will warn before showing the UI. See [TLS](https://philterd.github.io/philter/settings/#tls) for how to install your own certificate, or how to serve plain HTTP when a load balancer terminates TLS in front of Philter.

Philter uses a built-in in-memory cache by default and can be configured to use a shared Valkey/Redis cache for distributed deployments. See [Caching](https://philterd.github.io/philter/caching/) in the user documentation.

### Docker Images

Released versions are published to Docker Hub as [`philterd/philter`](https://hub.docker.com/r/philterd/philter/tags), tagged by version. There is no `latest` tag, so name the version you want:

```
docker pull philterd/philter:3.4.0
```

The `docker-compose.yml` in this repository builds the image from source instead, so it runs the code on your current branch.

## License

As of Philter 2.6.0, Philter is licensed under the Apache License, version 2.0. Previous versions were under a proprietary license.

Copyright 2024-2026 Philterd, LLC. Copyright 2018-2023 Mountain Fog, Inc.

# Philter

**For a hosted document redaction service, please visit [Philterd Data Services](https://www.philterd.ai/data-services).**

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

Philter is built with Maven (requiring >= Java 17):

```
mvn clean install
```

To run Philter:

```
docker compose build
docker compose up
```

Once the containers are running, you can submit text to Philter's API for redaction:

```
curl -k "https://localhost:8080/api/filter" --data "George Washington lives in 90210 and his SSN was 123-45-6789." -H "Content-type: text/plain"
```

You can also access the UI at http://localhost:9000.

## License

As of Philter 2.6.0, Philter is licensed under the Apache License, version 2.0. Previous versions were under a proprietary license.

Copyright 2024 Philterd, LLC. Copyright 2018-2023 Mountain Fog, Inc.

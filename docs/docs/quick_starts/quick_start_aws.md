# Philter Quick Start on AWS

Philter on AWS runs on its own EC2 instance in your AWS account, launched from [AWS Marketplace](https://aws.amazon.com/marketplace/pp/B07YVB8FFT?ref=_ptnr_mf_launch). A free trial period is available during which there is no charge for the Philter software, but there may be charges for the underlying AWS infrastructure.

The step-by-step quick start is [Philter on AWS: quick start](https://philterd.ai/dev/start/aws/). It covers launching Philter, reading its API key over SSH, redacting your first text, and creating a key for your application. This page is the reference for the marketplace virtual machine.

> Cloud virtual machines launched from a cloud marketplace may not be immediately suitable for a HIPAA environment. Refer to your compliance officer for your organization's requirements to ensure compliance with all relevant regulations.

## At a glance

| | |
|---|---|
| Recommended size | `c7i.xlarge`. Smaller sizes are intended only for testing. See [System Requirements](../system_requirements.md). |
| Operating system | Ubuntu 26.04 |
| Ports | `22` (SSH) and `8080` (Philter's API). The listing opens both; restrict them to specific CIDR ranges. |
| SSH | `ubuntu`, with the EC2 key pair chosen at launch |
| Keys | Generated on first boot into `/opt/philter/.env`, readable only by root |
| API | HTTPS on port `8080` with a self-signed certificate |

## Keys

Philter has no user interface; it is administered through its [API](../api_and_sdks/api.md). It requires two values, which the virtual machine generates on its first boot and keeps in `/opt/philter/.env`. Every virtual machine launched from the image gets its own. Read them over SSH with `sudo cat /opt/philter/.env`.

* `PHILTER_ENCRYPTION_KEY` encrypts sensitive data at rest. Back it up, and do not change it for the life of the deployment: data encrypted with it cannot be recovered if it is lost or changed. See [Encryption](../settings.md#encryption).
* `PHILTER_BOOTSTRAP_API_KEY` is the first API key. Philter seeds it onto the `admin` user with every scope. See [bootstrap settings](../settings.md#bootstrap-and-in-memory-capacity).

For applications, create keys holding only the scopes they need, such as `redact`, with [`POST /api/api-keys`](../api_and_sdks/api/api_keys_api.md#create-a-key). To retire the bootstrap key, create a replacement administrator key holding every scope, then revoke the bootstrap key with the replacement: a key cannot revoke itself or a key holding scopes it lacks. [Philter UI](https://github.com/philterd/philter-ui), a separate web application in development, is being built to sign people in through Philter with their own username and password once `PASSWORD_SIGN_IN_ENABLED` is set; see [Sign-in Security](../sign_in_security.md).

## Where your data is stored

Philter on the marketplace image stores its data, including policies, contexts, the redaction ledger, and the audit log, in a [SQLite](../database.md#sqlite) database on the virtual machine's disk. No database server runs beside it. Back up the disk regularly, or back up the database with SQLite's online backup as described in [Database](../database.md#sqlite), and keep `PHILTER_ENCRYPTION_KEY` with the backups, since the data cannot be read without it. One virtual machine runs one Philter; to run several instances behind a load balancer, use MongoDB storage as described in [High Availability](../high_availability.md), and move the existing data to it with the `migrate-storage` command as described in [Moving from SQLite to MongoDB](../database.md#moving-from-sqlite-to-mongodb).

## TLS

Philter serves HTTPS on port 8080 with a self-signed certificate, so clients must skip verification (`curl -k`) until it is replaced. Replace it with a certificate owned by your organization before production use. See [TLS](../settings.md#tls).

## Next Steps

* [Policies](../policies/filter_policies.md) control the types of sensitive information Philter redacts and how.
* The [API](../api_and_sdks/api.md) describes every endpoint, and [SDKs](../api_and_sdks/sdks.md) cover client libraries.

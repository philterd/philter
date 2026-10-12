# Quick Start with SQLite

Philter can keep all of its data in a single SQLite file instead of MongoDB, so a single Philter instance runs with no database server. Use it to evaluate Philter, or for a small, single-server, edge, or air-gapped deployment. Several instances behind a load balancer need MongoDB; see [High Availability](../high_availability.md).

## Run Philter with Docker Compose

From a clone of the [Philter repository](https://github.com/philterd/philter), build Philter and start it with the SQLite compose file:

```
mvn clean install
./compose.sh -f docker-compose.sqlite.yml build
./compose.sh -f docker-compose.sqlite.yml up
```

On its first run, `compose.sh` generates `PHILTER_ENCRYPTION_KEY` and `PHILTER_BOOTSTRAP_API_KEY` into `.env` and reuses them afterwards. Back up the encryption key: data encrypted with it cannot be recovered without it. See [Encryption](../settings.md#encryption).

The compose file sets `STORAGE_TYPE=sqlite` and keeps the database at `/opt/philter/data/philter.db` in the `philter-data` volume, so the data survives the container being recreated.

## Run Philter with Docker

To run the Philter image without Compose, set the storage, the database file, and the two required values, and mount a volume for the data:

```
docker run -p 8080:8080 \
  -e STORAGE_TYPE=sqlite \
  -e SQLITE_PATH=/opt/philter/data/philter.db \
  -e PHILTER_ENCRYPTION_KEY="$(openssl rand -base64 32)" \
  -e PHILTER_BOOTSTRAP_API_KEY="sk_$(openssl rand -hex 16)" \
  -v philter-data:/opt/philter/data \
  philter
```

Keep the encryption key you generate: Philter does not start with a different one, and the data cannot be read without it.

## Try it out

Set `API_KEY` to the bootstrap key, then redact some text:

```
API_KEY=$(grep PHILTER_BOOTSTRAP_API_KEY .env | cut -d= -f2)

curl -k "https://localhost:8080/api/filter" --data "His SSN was 123-45-6789." -H "Content-type: text/plain" -H "Authorization: Bearer $API_KEY"
```

Philter serves HTTPS with a self-signed certificate, so `curl` needs `-k`. See [TLS](../settings.md#tls).

## Next steps

* [SQLite storage](../database.md#sqlite): the single-instance limit, the files, and how to back them up.
* [Settings](../settings.md): `STORAGE_TYPE`, `SQLITE_PATH`, and everything else Philter reads from its environment.

# Code of Conduct

In the interest of fostering an open and welcoming environment, we as contributors and maintainers pledge to making participation in our project and our community a harassment-free experience for everyone, regardless of age, body size, disability, ethnicity, gender identity and expression, level of experience, nationality, personal appearance, race, religion, or sexual identity and orientation.

Please read and understand the [Code of Conduct](https://github.com/philterd/philter/blob/main/CODE_OF_CONDUCT.md).

# Storage

Philter reaches its data only through storage interfaces, so a storage implementation can be added without
changing the rest of Philter. There are two: MongoDB, the default, and SQLite, for a single Philter instance.

## Where the code lives

* `ai.philterd.philter.data.services` holds the storage interfaces, one per kind of record (for example
  `PolicyDataService`, `UserService`, and `LedgerDataService`), and the `Listings` paging types they share. Each
  interface's Javadoc states the guarantees every implementation must provide, such as which writes are
  conditional, what must hold under concurrent calls, and what may expire.
* `ai.philterd.philter.data.entities` holds the records the interfaces read and write. They know nothing about
  how they are stored. Record ids are 24-character hexadecimal strings in every implementation (see
  `ai.philterd.philter.data.RecordIds`).
* Each storage interface has an abstract `Stored...` class beside it (for example `StoredPolicyDataService`)
  holding the rules every implementation shares: validation, auditing, cache eviction, key and password
  handling, signing, legal-hold checks, and what a refusal looks like. It declares, as abstract methods, the
  reads and atomic writes an implementation must provide, each documenting what it must guarantee. Shared
  helpers for implementations are in the same package: `SecretFields` (how secret fields are encrypted at rest),
  `DuplicateRecordException` (a uniqueness clash), `EvidenceGuard` (the per-owner lock on evidence deletion and
  legal holds), and `AdmissionGuard` (the lock on queue admission).
* `ai.philterd.philter.data.mongodb` holds the MongoDB implementation: a `Mongo...` class extending each
  `Stored...` class, the `...Documents` classes that map each record to and from its stored document, and the
  MongoDB-specific helpers.
* `ai.philterd.philter.data.sqlite` holds the SQLite implementation: a `Sqlite...` class extending each
  `Stored...` class, over plain JDBC. `SqliteDatabase` runs every write in a transaction that takes SQLite's write
  lock as it begins, so a read and the write that depends on it are atomic. The version 1 tables are in
  `SqliteSchema`; later changes are migrations in `SqliteMigrations`. Only this package may use the SQLite
  driver or JDBC (`org.sqlite`, `java.sql`). Only this package may use the MongoDB driver (`com.mongodb`) or BSON (`org.bson`);
  `StorageBoundaryTest` fails the build if any other class does.
* Interfaces for other storage-backed services live with their callers: `AuditLogService` and
  `StoredAuditEventPublisher` in `ai.philterd.philter.audit`, `ContextServices` in
  `ai.philterd.philter.services.context`, `VectorServices` in `ai.philterd.philter.services.vectors`, and
  `PiiCountAggregatePublisher` in `ai.philterd.philter.services.diffuse`.

## How an implementation is selected

The `STORAGE_TYPE` environment variable sets the `philter.storage` property, which defaults to `mongodb`.
Each implementation registers its beans in a Spring configuration that applies only when it is selected:
`MongoStorageConfiguration` for MongoDB and `SqliteStorageConfiguration` for SQLite. `ai.philterd.philter.config.StorageConfiguration` lists the known
values and stops Philter from starting with any other.

## Changing how data is stored

Philter records the version of its data layout and migrates the data at startup (see
`ai.philterd.philter.data.schema.SchemaMigrator`). When a change needs existing data rewritten, such as a
renamed field or a restructured document, add a `Migration` numbered one more than the last, as a class in the
storage package, and list it in that implementation's migrations (`MongoMigrations` for MongoDB). A migration
should be safe to run again, since Philter may stop after its changes but before they are recorded. Migrations
are never edited or removed once released. Each storage implementation keeps its version in a `SchemaStore`.

To add an implementation, add a package beside `data.mongodb` with a class extending each `Stored...` class,
implementing only its abstract reads and writes, and a configuration that applies when its `STORAGE_TYPE` is
selected. Add the value to `StorageConfiguration.TYPES`, and document it in `docs/docs/settings.md`. It must
provide the guarantees in each interface's and abstract method's Javadoc. A rule that does not depend on how
data is stored belongs in the `Stored...` class, not in an implementation, so every implementation shares it.

## Contract tests

The guarantees in the interfaces' Javadoc are tested by the contract suite in `ai.philterd.philter.data.contract`
(under `src/test`). Each `...Contract` interface holds the tests for one storage interface, written against the
interface alone, and `StorageUnderTest` is how a contract reaches an implementation: it supplies each service,
another instance over the same data for guarantees that must hold across instances, and a few hooks for what the
interfaces do not expose, such as moving a session key's expiry into the past.

To run the suite against an implementation, write one test class that implements every `...Contract` interface
and returns a `StorageUnderTest` for that implementation from `storage()`, over storage that is empty when each
test starts. `MongoStorageContractIT` does this for MongoDB with `MongoStorageUnderTest`, and
`SqliteStorageContractIT` for SQLite with `SqliteStorageUnderTest`; both run in the normal build. Run them alone
with:

```
mvn verify -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false -DskipSqliteITs=true \
    -Dit.test=MongoStorageContractIT,SqliteStorageContractIT
```

The integration tests that reach storage only through Philter's API also run a second time with
`STORAGE_TYPE=sqlite`, in the `sqlite-integration-tests` execution of the failsafe plugin; `sqlite.it.test` in
`pom.xml` lists them. Add a new API test there when it does not read or write the database directly. Skip that
second run with `-DskipSqliteITs=true`.

When you add a guarantee to an interface, add a test for it to that interface's contract, so every
implementation is tested for it.

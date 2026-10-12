# Code of Conduct

In the interest of fostering an open and welcoming environment, we as contributors and maintainers pledge to making participation in our project and our community a harassment-free experience for everyone, regardless of age, body size, disability, ethnicity, gender identity and expression, level of experience, nationality, personal appearance, race, religion, or sexual identity and orientation.

Please read and understand the [Code of Conduct](https://github.com/philterd/philter/blob/main/CODE_OF_CONDUCT.md).

# Storage

Philter reaches its data only through storage interfaces, so a storage implementation can be added without
changing the rest of Philter. MongoDB is the only implementation today.

## Where the code lives

* `ai.philterd.philter.data.services` holds the storage interfaces, one per kind of record (for example
  `PolicyDataService`, `UserService`, and `LedgerDataService`), and the `Listings` paging types they share. Each
  interface's Javadoc states the guarantees every implementation must provide, such as which writes are
  conditional, what must hold under concurrent calls, and what may expire.
* `ai.philterd.philter.data.entities` holds the records the interfaces read and write. They know nothing about
  how they are stored. Record ids are 24-character hexadecimal strings in every implementation (see
  `ai.philterd.philter.data.RecordIds`).
* `ai.philterd.philter.data.mongodb` holds the MongoDB implementation: a `Mongo...` class for each interface,
  the `...Documents` classes that map each record to and from its stored document, and the MongoDB-specific
  helpers. Only this package may use the MongoDB driver (`com.mongodb`) or BSON (`org.bson`);
  `StorageBoundaryTest` fails the build if any other class does.
* Interfaces for other storage-backed services live with their callers: `AuditLogService` and
  `StoredAuditEventPublisher` in `ai.philterd.philter.audit`, `ContextServices` in
  `ai.philterd.philter.services.context`, `VectorServices` in `ai.philterd.philter.services.vectors`, and
  `PiiCountAggregatePublisher` in `ai.philterd.philter.services.diffuse`.

## How an implementation is selected

The `STORAGE_TYPE` environment variable sets the `philter.storage` property, which defaults to `mongodb`.
Each implementation registers its beans in a Spring configuration that applies only when it is selected:
`MongoStorageConfiguration` for MongoDB. `ai.philterd.philter.config.StorageConfiguration` lists the known
values and stops Philter from starting with any other.

To add an implementation, add a package beside `data.mongodb` with a class for each interface and a
configuration that applies when its `STORAGE_TYPE` is selected, add the value to `StorageConfiguration.TYPES`,
and document it in `docs/docs/settings.md`. It must provide the guarantees in each interface's Javadoc.

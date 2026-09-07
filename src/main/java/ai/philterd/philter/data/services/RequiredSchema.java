package ai.philterd.philter.data.services;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import org.bson.Document;
import org.bson.conversions.Bson;
import java.util.concurrent.TimeUnit;

/** Establishes the fresh-release schema and rejects incompatible or inaccessible definitions. */
final class RequiredSchema {
    private RequiredSchema() { }

    static void ensureIndex(MongoCollection<Document> collection, Bson keys, IndexOptions options) {
        final String name = collection.createIndex(keys, options);
        Document actual = null;
        for (Document index : collection.listIndexes()) {
            if (name.equals(index.getString("name"))) { actual = index; break; }
        }
        if (actual == null || Boolean.TRUE.equals(options.isUnique()) != actual.getBoolean("unique", false)
                || !java.util.Objects.equals(options.getExpireAfter(TimeUnit.SECONDS), ttl(actual))) {
            throw new IllegalStateException("Required index options were not established: " + name);
        }
    }

    private static Long ttl(Document index) {
        final Object value = index.get("expireAfterSeconds");
        return value instanceof Number number ? number.longValue() : null;
    }

    static void rejectAutomaticExpiry(MongoCollection<Document> collection) {
        for (Document index : collection.listIndexes()) {
            if (index.containsKey("expireAfterSeconds")) {
                throw new IllegalStateException("Automatic expiry is forbidden on evidence collection " + collection.getNamespace());
            }
        }
    }
    static void rejectUnexpectedExpiry(MongoCollection<Document> collection, String permittedField) {
        for (Document index : collection.listIndexes()) {
            if (index.containsKey("expireAfterSeconds") && !new Document(permittedField, 1).equals(index.get("key"))) {
                throw new IllegalStateException("Unexpected automatic expiry index on " + collection.getNamespace());
            }
        }
    }
}

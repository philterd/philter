package ai.philterd.philter.services;

import ai.philterd.philter.data.entities.AbstractEntity;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.bson.types.ObjectId;

public class AbstractService<T extends AbstractEntity> {

    private static final String database = "philter";

    public static final int MAX_RESULTS_PER_PAGE = 25;

    protected final MongoCollection<Document> collection;

    public AbstractService(final MongoClient mongoClient, final String collectionName) {

        final MongoDatabase mongoDatabase = mongoClient.getDatabase(database);
        this.collection = mongoDatabase.getCollection(collectionName);

    }

    public ObjectId save(T entity) {
        return collection.insertOne(entity.toDocument()).getInsertedId().asObjectId().getValue();
    }

    public void update(final T entity) {

        collection.updateOne(
                Filters.eq("_id", entity.getId()),
                new Document("$set", entity.toDocument())
        );

    }

}

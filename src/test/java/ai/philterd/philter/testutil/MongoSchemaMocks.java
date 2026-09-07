package ai.philterd.philter.testutil;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.ListIndexesIterable;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.IndexOptions;
import org.bson.Document;
import org.bson.conversions.Bson;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Models acknowledged index definitions for unit fixtures; fault tests use their own server/mocks. */
public final class MongoSchemaMocks {
    private MongoSchemaMocks() { }
    public static void configure(MongoCollection<Document> collection) {
        final List<Document> indexes = new ArrayList<>();
        lenient().when(collection.createIndex(any(Bson.class), any(IndexOptions.class))).thenAnswer(call -> {
            IndexOptions options = call.getArgument(1);
            String name = "test_index_" + indexes.size();
            Document doc = new Document("name", name).append("unique", Boolean.TRUE.equals(options.isUnique()))
                    .append("key", Document.parse(((Bson) call.getArgument(0)).toBsonDocument(Document.class, com.mongodb.MongoClientSettings.getDefaultCodecRegistry()).toJson()));
            if (options.getExpireAfter(TimeUnit.SECONDS) != null) doc.append("expireAfterSeconds", options.getExpireAfter(TimeUnit.SECONDS));
            indexes.add(doc);
            return name;
        });
        ListIndexesIterable<Document> iterable = mock(ListIndexesIterable.class);
        lenient().when(collection.listIndexes()).thenReturn(iterable);
        lenient().when(iterable.iterator()).thenAnswer(call -> {
            final var iterator = indexes.iterator();
            MongoCursor<Document> cursor = mock(MongoCursor.class);
            lenient().when(cursor.hasNext()).thenAnswer(c -> iterator.hasNext());
            lenient().when(cursor.next()).thenAnswer(c -> iterator.next());
            return cursor;
        });
    }
}

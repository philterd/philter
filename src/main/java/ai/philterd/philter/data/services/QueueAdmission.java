package ai.philterd.philter.data.services;

import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.*;
import org.bson.Document;
import java.util.Date;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/** Serializes admission across instances. Uncertain writes retain the guard for operator recovery. */
final class QueueAdmission {
    private static final long ACQUISITION_WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(500);
    private final MongoCollection<Document> state;
    QueueAdmission(MongoClient client) {
        state = client.getDatabase("philter").getCollection("queue_admission")
                .withReadPreference(ReadPreference.primary()).withWriteConcern(WriteConcern.MAJORITY);
    }
    <T> T execute(Supplier<T> action) {
        try {
            state.updateOne(Filters.eq("_id", "queue"), Updates.setOnInsert("created_at", new Date()), new UpdateOptions().upsert(true));
        } catch (com.mongodb.MongoWriteException race) {
            if (race.getError().getCode() != 11000) throw race;
        }
        final String token = UUID.randomUUID().toString();
        final long deadline = System.nanoTime() + ACQUISITION_WAIT_NANOS;
        while (state.updateOne(Filters.and(Filters.eq("_id", "queue"), Filters.exists("token", false)),
                Updates.combine(Updates.set("token", token), Updates.set("started_at", new Date()))).getModifiedCount() != 1) {
            // Retry only an acknowledged contention result. Database exceptions propagate:
            // acquisition may have succeeded, so neither clearing nor replaying it is safe.
            final long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw busy();
            final long delay = Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(
                    ThreadLocalRandom.current().nextLong(5, 26)));
            try {
                TimeUnit.NANOSECONDS.sleep(delay);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw busy();
            }
            if (deadline - System.nanoTime() <= 0) throw busy();
        }
        final T result;
        try { result = action.get(); }
        catch (QueueCapacityException rejected) { release(token); throw rejected; }
        // No finally: an ambiguous server write must not race a subsequent admission.
        release(token);
        return result;
    }
    private static QueueCapacityException busy() {
        return new QueueCapacityException("Queue admission is busy or requires recovery. Retry later.", 503);
    }
    private void release(String token) {
        if (state.updateOne(Filters.and(Filters.eq("_id", "queue"), Filters.eq("token", token)),
                Updates.combine(Updates.unset("token"), Updates.unset("started_at"))).getModifiedCount() != 1) {
            throw new IllegalStateException("Queue admission guard could not be released.");
        }
    }
}

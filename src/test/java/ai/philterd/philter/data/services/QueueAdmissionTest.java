package ai.philterd.philter.data.services;

import com.mongodb.MongoTimeoutException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QueueAdmissionTest {
    private MongoCollection<Document> state;
    private QueueAdmission admission;
    private final UpdateResult busy = UpdateResult.acknowledged(0, 0L, null);
    private final UpdateResult acquired = UpdateResult.acknowledged(1, 1L, null);
    @BeforeEach void setup() {
        var client = mock(MongoClient.class); var database = mock(MongoDatabase.class);
        state = mock(MongoCollection.class);
        when(client.getDatabase("philter")).thenReturn(database);
        when(database.getCollection("queue_admission")).thenReturn(state);
        when(state.withReadPreference(any())).thenReturn(state);
        when(state.withWriteConcern(any())).thenReturn(state);
        admission = new QueueAdmission(client);
    }
    @Test void contentionRetriesAcquisitionButExecutesActionOnlyOnce() {
        when(state.updateOne(any(Bson.class), any(Bson.class))).thenReturn(busy, acquired, acquired);
        var calls = new AtomicInteger();
        assertEquals(1, admission.execute(calls::incrementAndGet));
        assertEquals(1, calls.get());
        verify(state, times(3)).updateOne(any(Bson.class), any(Bson.class));
    }
    @Test void persistentContentionHasABoundedWaitAndNeverRunsAction() {
        when(state.updateOne(any(Bson.class), any(Bson.class))).thenReturn(busy);
        long start = System.nanoTime();
        assertEquals(503, assertThrows(QueueCapacityException.class,
                () -> admission.execute(() -> { fail("Action must not run"); return null; })).getStatus());
        long elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsed >= 450 && elapsed < 2500, "bounded contention wait: " + elapsed);
        verify(state, atLeast(2)).updateOne(any(Bson.class), any(Bson.class));
    }
    @Test void acquisitionTimeoutIsNotRetriedOrReleased() {
        when(state.updateOne(any(Bson.class), any(Bson.class))).thenThrow(new MongoTimeoutException("uncertain acquisition"));
        assertThrows(MongoTimeoutException.class,
                () -> admission.execute(() -> { fail("Action must not run"); return null; }));
        verify(state, times(1)).updateOne(any(Bson.class), any(Bson.class));
    }
    @Test void uncertainActionIsNotReplayedOrReleased() {
        when(state.updateOne(any(Bson.class), any(Bson.class))).thenReturn(acquired);
        var calls = new AtomicInteger();
        assertThrows(MongoTimeoutException.class, () -> admission.execute(() -> {
            calls.incrementAndGet(); throw new MongoTimeoutException("uncertain insert");
        }));
        assertEquals(1, calls.get());
        verify(state, times(1)).updateOne(any(Bson.class), any(Bson.class));
    }
    @Test void interruptedWaitDoesNotAcquireAndPreservesInterrupt() {
        when(state.updateOne(any(Bson.class), any(Bson.class))).thenReturn(busy);
        Thread.currentThread().interrupt();
        try {
            assertThrows(QueueCapacityException.class,
                    () -> admission.execute(() -> { fail("Action must not run"); return null; }));
            assertTrue(Thread.currentThread().isInterrupted());
            verify(state, times(1)).updateOne(any(Bson.class), any(Bson.class));
        } finally { Thread.interrupted(); }
    }
}

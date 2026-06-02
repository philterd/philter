/*
 *     Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.philterd.philter.services.vectors;

import ai.philterd.phileas.model.filtering.FilterType;
import ai.philterd.phileas.model.filtering.Span;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.testutil.AbstractMongoIT;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Integration tests for {@link MongoVectorService} against a real (in-memory) MongoDB. These exercise
 * the actual {@code hashAndInsert} writes, the {@code getVectorRepresentation} hash&rarr;count
 * aggregation read back from storage, per-user scoping, and {@code deleteByContext} — full driver
 * round-trips the mock-based unit tests can only approximate.
 */
class MongoVectorServiceIT extends AbstractMongoIT {

    private final ObjectId userId = new ObjectId();

    private MongoVectorService service;

    @BeforeEach
    void setUpService() {
        service = new MongoVectorService(mongoClient, userId, mock(AuditEventPublisher.class));
    }

    private static Span personSpan() {
        final Span span = new Span();
        span.setFilterType(FilterType.PERSON);
        return span;
    }

    @Test
    void hashAndInsertThenGetVectorRepresentationReturnsHashCounts() {
        // 1.5 appears twice, 2.5 once; the 0.0 hash must be skipped entirely.
        service.hashAndInsert("ctx", new double[]{1.5, 1.5, 2.5, 0.0}, personSpan(), 8);

        final Map<Double, Double> representation = service.getVectorRepresentation("ctx", FilterType.PERSON);

        assertEquals(2.0, representation.get(1.5), 1e-9);
        assertEquals(1.0, representation.get(2.5), 1e-9);
        assertEquals(2, representation.size(), "the zero hash must not be stored");
    }

    @Test
    void getVectorRepresentationIsScopedByFilterType() {
        service.hashAndInsert("ctx", new double[]{1.5}, personSpan(), 8);

        final Span email = new Span();
        email.setFilterType(FilterType.EMAIL_ADDRESS);
        service.hashAndInsert("ctx", new double[]{9.9}, email, 8);

        // Only PERSON vectors come back for the PERSON request.
        final Map<Double, Double> person = service.getVectorRepresentation("ctx", FilterType.PERSON);
        assertEquals(1, person.size());
        assertEquals(1.0, person.get(1.5), 1e-9);

        final Map<Double, Double> emailRep = service.getVectorRepresentation("ctx", FilterType.EMAIL_ADDRESS);
        assertEquals(1, emailRep.size());
        assertEquals(1.0, emailRep.get(9.9), 1e-9);
    }

    @Test
    void getVectorRepresentationIsScopedByContext() {
        service.hashAndInsert("ctxA", new double[]{1.5}, personSpan(), 8);

        // A different context sees nothing.
        assertTrue(service.getVectorRepresentation("ctxB", FilterType.PERSON).isEmpty());
    }

    @Test
    void vectorsAreScopedByUser() {
        service.hashAndInsert("ctx", new double[]{1.5}, personSpan(), 8);

        // A service for a different user must not see the first user's vectors.
        final MongoVectorService otherUser =
                new MongoVectorService(mongoClient, new ObjectId(), mock(AuditEventPublisher.class));

        assertTrue(otherUser.getVectorRepresentation("ctx", FilterType.PERSON).isEmpty());
        // The owning user still sees its vector.
        assertEquals(1.0, service.getVectorRepresentation("ctx", FilterType.PERSON).get(1.5), 1e-9);
    }

    @Test
    void deleteByContextRemovesOnlyThatContextsVectors() {
        service.hashAndInsert("ctxA", new double[]{1.5, 2.5}, personSpan(), 8);
        service.hashAndInsert("ctxB", new double[]{3.5}, personSpan(), 8);

        service.deleteByContext("ctxA");

        assertTrue(service.getVectorRepresentation("ctxA", FilterType.PERSON).isEmpty());
        // The other context is untouched.
        assertEquals(1.0, service.getVectorRepresentation("ctxB", FilterType.PERSON).get(3.5), 1e-9);
    }

    @Test
    void deleteByContextIsScopedByUser() {
        service.hashAndInsert("ctx", new double[]{1.5}, personSpan(), 8);

        // A different user deleting the same context name must not remove this user's vectors.
        final MongoVectorService otherUser =
                new MongoVectorService(mongoClient, new ObjectId(), mock(AuditEventPublisher.class));
        otherUser.deleteByContext("ctx");

        assertEquals(1.0, service.getVectorRepresentation("ctx", FilterType.PERSON).get(1.5), 1e-9);
    }

}

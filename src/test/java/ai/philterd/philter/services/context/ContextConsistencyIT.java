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
package ai.philterd.philter.services.context;

import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.services.cache.ContextCache;
import ai.philterd.philter.testutil.AbstractMongoIT;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * The property a customer sees: one person, one pseudonym, across documents redacted at the same
 * time. Written against {@code computeReplacementIfAbsent}, which is what the redaction pipeline
 * calls, so it holds whatever the storage underneath does.
 */
class ContextConsistencyIT extends AbstractMongoIT {

    private MongoContextService serviceFor(final ObjectId userId, final String contextName) {
        return new MongoContextService(mongoClient, new ContextCache(null, 0, null, false),
                userId, contextName, mock(AuditEventPublisher.class));
    }

    @Test
    @DisplayName("Two documents redacted at once are given the same pseudonym for the same person")
    void concurrentRedactionsAgreeOnOnePseudonym() throws Exception {

        final List<String> disagreements = new ArrayList<>();

        for (int round = 0; round < 30; round++) {

            final ObjectId user = new ObjectId();
            final String context = "case-" + round;

            // Two requests, each with its own service instance and its own cache, as two concurrent
            // API calls have.
            final MongoContextService one = serviceFor(user, context);
            final MongoContextService two = serviceFor(user, context);

            final CountDownLatch go = new CountDownLatch(1);
            final List<String> pseudonyms = Collections.synchronizedList(new ArrayList<>());

            final Thread a = redactor(go, pseudonyms, one, "John Smith", "David Jones");
            final Thread b = redactor(go, pseudonyms, two, "John Smith", "Alice Brown");

            a.start();
            b.start();
            go.countDown();
            a.join(20_000);
            b.join(20_000);

            if (Set.copyOf(pseudonyms).size() != 1) {
                disagreements.add("round " + round + ": " + pseudonyms);
            }

        }

        assertEquals(List.of(), disagreements,
                "the same person was redacted two different ways in one context");

    }

    @Test
    @DisplayName("A later document reuses the pseudonym the first one established")
    void alaterDocumentReusesTheStoredPseudonym() {

        final ObjectId user = new ObjectId();
        final MongoContextService first = serviceFor(user, "case");

        final String established = first.computeReplacementIfAbsent("John Smith", "PERSON", () -> "David Jones");
        assertEquals("David Jones", established);

        // A fresh instance, so nothing is answered from an in-process cache.
        final MongoContextService later = serviceFor(user, "case");
        assertEquals("David Jones", later.computeReplacementIfAbsent("John Smith", "PERSON", () -> "Someone Else"));
        assertEquals("David Jones", later.getReplacement("John Smith"));

    }

    private static Thread redactor(final CountDownLatch go, final List<String> pseudonyms,
                                   final MongoContextService service, final String token, final String proposal) {
        return new Thread(() -> {
            try {
                go.await(10, TimeUnit.SECONDS);
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            pseudonyms.add(service.computeReplacementIfAbsent(token, "PERSON", () -> proposal));
        });
    }

}

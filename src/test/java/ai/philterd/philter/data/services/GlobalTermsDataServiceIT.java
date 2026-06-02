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
package ai.philterd.philter.data.services;

import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.GlobalTermsEntity;
import ai.philterd.philter.testutil.AbstractMongoIT;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * Integration tests for {@link GlobalTermsDataService} against a real (in-memory) MongoDB. These
 * exercise the saveOrUpdate insert/update branches with real round-trips, prove that a second
 * saveOrUpdate updates rather than duplicates the per-user document, and verify that the lookup is
 * scoped to the owning user.
 */
class GlobalTermsDataServiceIT extends AbstractMongoIT {

    private GlobalTermsDataService service;

    @BeforeEach
    void setUpService() {
        service = new GlobalTermsDataService(mongoClient, mock(AuditEventPublisher.class));
    }

    @Test
    void saveOrUpdatePersistsAndFindReadsItBack() {
        final ObjectId user = new ObjectId();
        service.saveOrUpdate("req", user, List.of("ssn", "secret"), List.of("public"), "source");

        final GlobalTermsEntity found = service.find(user);
        assertNotNull(found);
        assertEquals(user, found.getUserId());
        assertEquals(List.of("ssn", "secret"), found.getTermsToAlwaysRedact());
        assertEquals(List.of("public"), found.getTermsToNeverRedact());
    }

    @Test
    void findReturnsNullWhenNothingSaved() {
        assertNull(service.find(new ObjectId()));
    }

    @Test
    void secondSaveOrUpdateUpdatesAndDoesNotDuplicate() {
        final ObjectId user = new ObjectId();
        service.saveOrUpdate("req", user, List.of("a"), List.of("b"), "source");
        // A second save for the same user must update the existing document, not insert another.
        service.saveOrUpdate("req", user, List.of("c", "d"), List.of("e"), "source");

        final MongoCollection<Document> globalTerms =
                mongoClient.getDatabase("philter").getCollection("global_terms");
        assertEquals(1L, globalTerms.countDocuments(new Document("user_id", user)));

        final GlobalTermsEntity found = service.find(user);
        assertEquals(List.of("c", "d"), found.getTermsToAlwaysRedact());
        assertEquals(List.of("e"), found.getTermsToNeverRedact());
    }

    @Test
    void globalTermsAreScopedByUser() {
        final ObjectId userA = new ObjectId();
        final ObjectId userB = new ObjectId();
        service.saveOrUpdate("req", userA, List.of("a"), List.of("b"), "source");

        // Another user has no global terms of their own.
        assertNull(service.find(userB));

        // Saving for userB does not affect userA's terms.
        service.saveOrUpdate("req", userB, List.of("x"), List.of("y"), "source");
        assertEquals(List.of("a"), service.find(userA).getTermsToAlwaysRedact());
        assertEquals(List.of("x"), service.find(userB).getTermsToAlwaysRedact());
    }

}

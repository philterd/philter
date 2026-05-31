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
package ai.philterd.philter.services.diffuse;

import ai.philterd.philter.data.entities.AdminSettingsEntity;
import ai.philterd.philter.data.services.AdminSettingsDataService;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PiiCountAggregatePublisherTest {

    @Mock private MongoClient mongoClient;
    @Mock private MongoDatabase mongoDatabase;
    @Mock private MongoCollection<Document> collection;
    @Mock private AdminSettingsDataService adminSettingsDataService;

    @BeforeEach
    void setUp() {
        when(mongoClient.getDatabase("philter")).thenReturn(mongoDatabase);
        when(mongoDatabase.getCollection(PiiCountAggregatePublisher.COLLECTION)).thenReturn(collection);
    }

    private PiiCountAggregatePublisher publisher() {
        return new PiiCountAggregatePublisher(mongoClient, adminSettingsDataService);
    }

    @Test
    void doesNotWriteWhenDisabled() {
        final AdminSettingsEntity settings = new AdminSettingsEntity();
        settings.setDiffuseCountsEnabled(false);
        when(adminSettingsDataService.findAdminSettings()).thenReturn(settings);

        publisher().record("ctx", Set.of("SSN", "EMAIL_ADDRESS"));

        verify(collection, never()).updateOne(any(Bson.class), any(Bson.class), any(UpdateOptions.class));
    }

    @Test
    void doesNotWriteWhenNoPiiTypes() {
        // With nothing to record there is no need to even consult the admin setting.
        publisher().record("ctx", Set.of());

        verify(collection, never()).updateOne(any(Bson.class), any(Bson.class), any(UpdateOptions.class));
    }

    @Test
    void incrementsDocumentPresenceCountsWhenEnabled() {
        final AdminSettingsEntity settings = new AdminSettingsEntity();
        settings.setDiffuseCountsEnabled(true);
        when(adminSettingsDataService.findAdminSettings()).thenReturn(settings);
        when(collection.updateOne(any(Bson.class), any(Bson.class), any(UpdateOptions.class))).thenReturn(mock(UpdateResult.class));

        publisher().record("ctx-a", Set.of("SSN", "EMAIL_ADDRESS"));

        final ArgumentCaptor<Bson> filterCaptor = ArgumentCaptor.forClass(Bson.class);
        final ArgumentCaptor<Bson> updateCaptor = ArgumentCaptor.forClass(Bson.class);
        final ArgumentCaptor<UpdateOptions> optionsCaptor = ArgumentCaptor.forClass(UpdateOptions.class);
        verify(collection).updateOne(filterCaptor.capture(), updateCaptor.capture(), optionsCaptor.capture());

        // Upsert scoped to (context, bucket_start).
        final Document filter = (Document) filterCaptor.getValue();
        assertEquals("ctx-a", filter.getString("context"));
        org.junit.jupiter.api.Assertions.assertTrue(filter.containsKey("bucket_start"), "must bucket by start time");
        org.junit.jupiter.api.Assertions.assertTrue(optionsCaptor.getValue().isUpsert(), "must upsert");

        // Each present type incremented by exactly one (document presence), plus the total.
        final Document update = (Document) updateCaptor.getValue();
        final Document inc = (Document) update.get("$inc");
        assertEquals(1, inc.getInteger("counts.SSN"));
        assertEquals(1, inc.getInteger("counts.EMAIL_ADDRESS"));
        assertEquals(1, inc.getInteger("total_documents"));
    }

}

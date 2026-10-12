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
package ai.philterd.philter.data.mongodb;

import ai.philterd.philter.data.entities.ContextEntity;
import ai.philterd.philter.services.filtering.ContextSnapshots;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The context captured with an asynchronous redaction is written as MongoDB has always written it, since ledger
 * entries record a hash of it: {@link ContextSnapshots} must produce the driver's JSON byte for byte, and read it
 * back as the driver did.
 */
class ContextSnapshotsCompatibilityTest {

    private static ContextEntity context(final String id, final String userId, final String name, final Integer slot,
                                         final Date timestamp, final String scope) {
        final ContextEntity context = new ContextEntity();
        context.setId(id);
        context.setUserId(userId);
        context.setContextName(name);
        context.setMaxSize(1234);
        context.setDisambiguation(true);
        context.setDisambiguationScope(scope);
        context.setLedger(true);
        context.setSlot(slot);
        context.setTtlInHours(7);
        context.setTimestamp(timestamp);
        return context;
    }

    private static List<ContextEntity> contexts() {
        final List<ContextEntity> contexts = new ArrayList<>();
        contexts.add(context(MongoIds.newId(), MongoIds.newId(), "claims", 3, new Date(1791000000123L), "Context"));
        contexts.add(context(null, MongoIds.newId(), "no id", null, null, null));
        contexts.add(context(MongoIds.newId(), null, null, 0, new Date(0L), "Document"));
        contexts.add(context(MongoIds.newId(), MongoIds.newId(), "zero millis", 1, new Date(1791000000000L), "Document"));
        contexts.add(context(MongoIds.newId(), MongoIds.newId(), "tenths", 1, new Date(1791000000100L), "Document"));
        contexts.add(context(MongoIds.newId(), MongoIds.newId(), "before 1970", 1, new Date(-1000L), "Document"));
        contexts.add(context(MongoIds.newId(), MongoIds.newId(), "after 9999", 1, new Date(253402300800000L), "Document"));
        // Every character class the driver writes as itself or escapes.
        final StringBuilder all = new StringBuilder();
        for (char c = 0; c < 0x3000; c++) {
            all.append(c);
        }
        all.append("\ud83d\ude00\u2028\u2029\ufeff\ue000\u200b");
        contexts.add(context(MongoIds.newId(), MongoIds.newId(), all.toString(), 2, new Date(1L), all.toString()));
        final Random random = new Random(171);
        for (int i = 0; i < 500; i++) {
            final StringBuilder name = new StringBuilder();
            for (int j = 0; j < 20; j++) {
                name.append((char) random.nextInt(0x10000));
            }
            contexts.add(context(MongoIds.newId(), MongoIds.newId(), name.toString(), random.nextInt(10),
                    new Date((long) (random.nextDouble() * 300000000000000L) - 10000000000000L), "Context"));
        }
        return contexts;
    }

    @Test
    void theCaptureIsTheDriversJsonByteForByte() {
        for (final ContextEntity context : contexts()) {
            assertEquals(ContextDocuments.toDocument(context).toJson(), ContextSnapshots.toJson(context));
        }
    }

    @Test
    void aCaptureReadsBackAsTheDriverReadIt() {
        for (final ContextEntity context : contexts()) {
            final String json = ContextDocuments.toDocument(context).toJson();
            final ContextEntity expected = ContextDocuments.fromDocument(Document.parse(json));
            final ContextEntity actual = ContextSnapshots.fromJson(json);
            assertEquals(expected.getId(), actual.getId());
            assertEquals(expected.getUserId(), actual.getUserId());
            assertEquals(expected.getContextName(), actual.getContextName());
            assertEquals(expected.getMaxSize(), actual.getMaxSize());
            assertEquals(expected.isDisambiguation(), actual.isDisambiguation());
            assertEquals(expected.getDisambiguationScope(), actual.getDisambiguationScope());
            assertEquals(expected.isLedger(), actual.isLedger());
            assertEquals(expected.getSlot(), actual.getSlot());
            assertEquals(expected.getTtlInHours(), actual.getTtlInHours());
            assertEquals(expected.getTimestamp(), actual.getTimestamp());
        }
    }

    @Test
    void missingSettingsTakeTheirDefaultsAsTheDriverGaveThem() {
        final String json = "{\"context_name\": \"bare\"}";
        final ContextEntity expected = ContextDocuments.fromDocument(Document.parse(json));
        final ContextEntity actual = ContextSnapshots.fromJson(json);
        assertEquals(expected.getMaxSize(), actual.getMaxSize());
        assertEquals(expected.getTtlInHours(), actual.getTtlInHours());
        assertEquals(expected.getDisambiguationScope(), actual.getDisambiguationScope());
        assertEquals(expected.isLedger(), actual.isLedger());
        assertEquals(expected.getSlot(), actual.getSlot());
    }

}

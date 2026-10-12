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
package ai.philterd.philter.services.encryption;

import ai.philterd.philter.data.mongodb.WebhookDeliveryDocuments;
import ai.philterd.philter.data.entities.WebhookDeliveryEntity;
import ai.philterd.philter.data.mongodb.MongoIds;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WebhookSecretEncryptionTest {
    @Test
    void storedSecretRequiresMasterKeyAndRejectsTampering() {
        final var encryption = new LocalEncryptionService(new LocalKeyProvider(
                java.util.Base64.getEncoder().encodeToString(new byte[32])));
        final var delivery = new WebhookDeliveryEntity();
        delivery.setUserId(MongoIds.newId());
        delivery.setSecret("webhook-signing-secret");
        final var stored = WebhookDeliveryDocuments.toDocument(delivery, encryption);
        assertNotEquals(delivery.getSecret(), stored.getString("secret"));
        assertTrue(stored.getString("secret_encrypted_key").startsWith("v2:"));
        assertEquals(delivery.getSecret(), WebhookDeliveryDocuments.fromDocument(stored, encryption).getSecret());
        final byte[] otherKey = new byte[32];
        otherKey[0] = 1;
        final var otherEncryption = new LocalEncryptionService(new LocalKeyProvider(
                java.util.Base64.getEncoder().encodeToString(otherKey)));
        assertThrows(RuntimeException.class, () -> WebhookDeliveryDocuments.fromDocument(stored, otherEncryption));
        stored.put("secret", "corrupt-ciphertext");
        assertThrows(RuntimeException.class, () -> WebhookDeliveryDocuments.fromDocument(stored, encryption));
    }
}

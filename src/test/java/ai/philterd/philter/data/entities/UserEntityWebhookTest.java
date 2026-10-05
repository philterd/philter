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
package ai.philterd.philter.data.entities;

import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.testutil.TestEncryptionService;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserEntityWebhookTest {

    @Test
    void webhookFieldsRoundTrip() {
        final UserEntity user = new UserEntity();
        user.setEmail("a@b.c");
        user.setWebhookUrl("https://example.com/hook");
        user.setWebhookSecret("the-secret-value");

        final EncryptionService encryptionService = new TestEncryptionService();

        final Document doc = user.toDocument(encryptionService);
        assertEquals("https://example.com/hook", doc.getString("webhook_url"));
        // The webhook secret is encrypted at rest, so the stored value is not the plaintext.
        assertNotEquals("the-secret-value", doc.getString("webhook_secret"));

        final UserEntity restored = UserEntity.fromDocument(doc, encryptionService);
        assertEquals("https://example.com/hook", restored.getWebhookUrl());
        assertEquals("the-secret-value", restored.getWebhookSecret());
    }

    @Test
    void webhookFieldsNullByDefault() {
        final UserEntity restored = UserEntity.fromDocument(new Document("email", "a@b.c"), Mockito.mock(EncryptionService.class));
        assertNull(restored.getWebhookUrl());
        assertNull(restored.getWebhookSecret());
    }

    @Test
    void fieldsFromEarlierVersionsAreIgnored() {
        // Users stored before 4.0 carry dashboard sign-in fields; they must still load.
        final Document stored = new Document("_id", new ObjectId()).append("username", "legacy").append("role", "admin")
                .append("password", "$2a$10$abcdefghijklmnopqrstuv").append("password_change_required", true)
                .append("security_version", 3L).append("mfa_enabled", true).append("mfa_secret", "not-decryptable")
                .append("mfa_failed_attempts", 2).append("mfa_locked", true);
        final UserEntity restored = UserEntity.fromDocument(stored, new TestEncryptionService());
        assertEquals("legacy", restored.getUsername());
        assertEquals("admin", restored.getRole());
        assertFalse(restored.toDocument(new TestEncryptionService()).containsKey("mfa_secret"));
    }

    @Test
    void deactivatedFieldsRoundTrip() {
        final UserEntity user = new UserEntity();
        user.setEmail("a@b.c");
        final Date deactivatedAt = new Date();
        user.setDeactivated(true);
        user.setDeactivatedAt(deactivatedAt);

        final Document doc = user.toDocument(Mockito.mock(EncryptionService.class));
        assertTrue(doc.getBoolean("deactivated"));
        assertEquals(deactivatedAt, doc.getDate("deactivated_at"));

        final UserEntity restored = UserEntity.fromDocument(doc, Mockito.mock(EncryptionService.class));
        assertTrue(restored.isDeactivated());
        assertEquals(deactivatedAt, restored.getDeactivatedAt());
    }

    @Test
    void deactivatedFalseByDefault() {
        final UserEntity restored = UserEntity.fromDocument(new Document("email", "a@b.c"), Mockito.mock(EncryptionService.class));
        assertFalse(restored.isDeactivated());
        assertNull(restored.getDeactivatedAt());
    }

}

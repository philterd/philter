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

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class LocalKeyProviderTest {

    private static String validKey() {
        return Base64.getEncoder().encodeToString(new byte[32]);
    }

    @Test
    public void dataKeyIsNeverTheMasterKey() {

        final String master = validKey();
        final KeyResponse response = new LocalKeyProvider(master).getKey("any-user");

        // The data key is random per record and the stored form is wrapped, so neither reveals the
        // master key. This is the property whose absence let anyone with read access to MongoDB
        // decrypt every record and recover the master key from any single document.
        assertNotEquals(master, response.getPlainKey());
        assertNotEquals(master, response.getEncryptedKey());
        assertFalse(response.getEncryptedKey().contains(master));
        assertEquals(32, Base64.getDecoder().decode(response.getPlainKey()).length);

    }

    @Test
    public void wrappedKeyRoundTripsThroughTheMasterKey() {

        final LocalKeyProvider provider = new LocalKeyProvider(validKey());
        final KeyResponse response = provider.getKey("any-user");

        assertEquals(response.getPlainKey(), provider.decryptKey(response.getEncryptedKey()));

    }

    @Test
    public void everyRecordGetsItsOwnDataKey() {

        final LocalKeyProvider provider = new LocalKeyProvider(validKey());

        assertNotEquals(provider.getKey("user").getPlainKey(), provider.getKey("user").getPlainKey());

    }

    @Test
    public void wrappedKeyCannotBeUnwrappedWithADifferentMasterKey() {

        final String wrapped = new LocalKeyProvider(validKey()).getKey("any-user").getEncryptedKey();

        final byte[] other = new byte[32];
        other[0] = 1;
        final LocalKeyProvider otherProvider = new LocalKeyProvider(Base64.getEncoder().encodeToString(other));

        assertThrows(IllegalStateException.class, () -> otherProvider.decryptKey(wrapped));

    }

    @Test
    public void preReleaseKeysStillRead() {

        // Records written before the wrapping fix stored the key itself. They must stay readable so an
        // existing development database keeps working; nothing writes this form any more.
        final String legacy = validKey();

        assertEquals(legacy, new LocalKeyProvider(validKey()).decryptKey(legacy));

    }

    @Test
    public void nullKeyThrows() {
        assertThrows(IllegalStateException.class, () -> new LocalKeyProvider(null));
    }

    @Test
    public void blankKeyThrows() {
        assertThrows(IllegalStateException.class, () -> new LocalKeyProvider("   "));
    }

    @Test
    public void nonBase64KeyThrows() {
        assertThrows(IllegalStateException.class, () -> new LocalKeyProvider("not valid base64!!!"));
    }

    @Test
    public void wrongLengthKeyThrows() {
        // 16 bytes (AES-128) is not allowed; the local provider requires AES-256 (32 bytes).
        final String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThrows(IllegalStateException.class, () -> new LocalKeyProvider(shortKey));
    }

}

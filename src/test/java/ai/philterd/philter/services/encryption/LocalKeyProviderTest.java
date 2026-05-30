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
import static org.junit.jupiter.api.Assertions.assertThrows;

public class LocalKeyProviderTest {

    private static String validKey() {
        return Base64.getEncoder().encodeToString(new byte[32]);
    }

    @Test
    public void validKeyRoundTrips() {

        final String key = validKey();
        final LocalKeyProvider provider = new LocalKeyProvider(key);

        final KeyResponse response = provider.getKey("any-user");

        assertEquals(key, response.getPlainKey());
        assertEquals(key, response.getEncryptedKey());

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

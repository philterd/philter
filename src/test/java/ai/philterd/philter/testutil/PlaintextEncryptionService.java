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
package ai.philterd.philter.testutil;

import ai.philterd.philter.services.encryption.EncryptResult;
import ai.philterd.philter.services.encryption.EncryptedBytes;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.services.encryption.KeyProvider;
import ai.philterd.philter.services.encryption.KeyResponse;

/**
 * Stores values as they are. A test that must show data was removed, not just unreadable, uses this, so any copy
 * left behind is visible in the raw storage.
 */
public final class PlaintextEncryptionService extends EncryptionService {

    private static final String KEY = "plaintext";

    public PlaintextEncryptionService() {
        super(new KeyProvider() {
            @Override
            public KeyResponse getKey(final String userId) {
                return new KeyResponse(KEY, KEY);
            }

            @Override
            public String decryptKey(final String storedKey) {
                return storedKey;
            }
        });
    }

    @Override
    public String generateEncryptionKey() {
        return KEY;
    }

    @Override
    public EncryptResult encrypt(final String data, final String userId) {
        return new EncryptResult(data, KEY);
    }

    @Override
    public String decrypt(final String encryptedText, final String encryptedDataKey) {
        return encryptedText;
    }

    @Override
    public EncryptedBytes encryptBytes(final byte[] data, final String userId) {
        return new EncryptedBytes(data.clone(), KEY);
    }

    @Override
    public byte[] decryptBytes(final byte[] encrypted, final String encryptedDataKey) {
        return encrypted.clone();
    }

}

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

import java.util.concurrent.atomic.AtomicInteger;

/** Encrypts and decrypts as a {@link TestEncryptionService} does, counting each encryption and decryption. */
public final class CountingEncryptionService extends EncryptionService {

    private final EncryptionService delegate;
    private final AtomicInteger encryptions = new AtomicInteger();
    private final AtomicInteger decryptions = new AtomicInteger();

    /** Counting what {@code delegate} does, with its key. */
    public CountingEncryptionService(final EncryptionService delegate) {
        super(new KeyProvider() {
            @Override
            public KeyResponse getKey(final String userId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String decryptKey(final String storedKey) {
                throw new UnsupportedOperationException();
            }
        });
        this.delegate = delegate;
    }

    public int encryptions() {
        return encryptions.get();
    }

    public int decryptions() {
        return decryptions.get();
    }

    @Override
    public String generateEncryptionKey() {
        return delegate.generateEncryptionKey();
    }

    @Override
    public EncryptResult encrypt(final String data, final String userId) {
        encryptions.incrementAndGet();
        return delegate.encrypt(data, userId);
    }

    @Override
    public String decrypt(final String encryptedText, final String encryptedDataKey) {
        decryptions.incrementAndGet();
        return delegate.decrypt(encryptedText, encryptedDataKey);
    }

    @Override
    public EncryptedBytes encryptBytes(final byte[] data, final String userId) {
        encryptions.incrementAndGet();
        return delegate.encryptBytes(data, userId);
    }

    @Override
    public byte[] decryptBytes(final byte[] encrypted, final String encryptedDataKey) {
        decryptions.incrementAndGet();
        return delegate.decryptBytes(encrypted, encryptedDataKey);
    }

}

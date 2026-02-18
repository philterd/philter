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

import org.apache.commons.codec.digest.DigestUtils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.security.Security;
import java.util.Base64;

public abstract class EncryptionService {

    public abstract String generateEncryptionKey();
    public abstract EncryptResult encrypt(final String data, final String userId);
    public abstract String decrypt(final String encryptedText, final String encryptedDataKey);

    protected final int KEY_LENGTH_BITS = 256;
    protected final String ALGORITHM = "AES/GCM/NoPadding";
    protected final KeyProvider keyProvider;

    public EncryptionService(final KeyProvider keyProvider) {
        Security.addProvider(new BouncyCastleProvider());
        this.keyProvider = keyProvider;
    }

    public static String hashSha256(final String data) {
        return DigestUtils.sha256Hex(data);
    }

    public static String hashSha256(final byte[] data) {
        return DigestUtils.sha256Hex(data);
    }

    public static String hashSha512(final String data) {
        return DigestUtils.sha512Hex(data);
    }

    public static byte[] base64Decode(final String data) {
        return Base64.getDecoder().decode(data);
    }

}

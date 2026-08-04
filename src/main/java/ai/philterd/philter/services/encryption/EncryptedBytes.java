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

/**
 * Ciphertext as raw bytes, with the wrapped data key that decrypts it. Kept separate from
 * {@link EncryptResult}, which carries base64 text, so binary payloads are never base64-encoded.
 *
 * @param ciphertext the IV followed by the AES-GCM ciphertext and tag
 * @param encryptionKey the per-record data key, wrapped under the master key
 */
public record EncryptedBytes(byte[] ciphertext, String encryptionKey) {
}

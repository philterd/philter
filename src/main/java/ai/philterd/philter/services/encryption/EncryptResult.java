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

public class EncryptResult {

    private final String encryptedText;
    private final String encryptionKey;

    public EncryptResult(final String encryptedText, final String encryptionKey) {
        this.encryptedText = encryptedText;
        this.encryptionKey = encryptionKey;
    }

    @Override
    public String toString() {
        return "encryptedText: " + encryptedText + ", encryptionKey: " + encryptionKey;
    }

    public String getEncryptedText() {
        return encryptedText;
    }

    public String getEncryptionKey() {
        return encryptionKey;
    }

}

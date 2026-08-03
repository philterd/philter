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

public abstract class KeyProvider {

    public abstract KeyResponse getKey(final String userId);

    /**
     * Recovers the plaintext data key from the value stored alongside a record, which is the
     * {@link KeyResponse#getEncryptedKey()} written at encryption time. Only the provider can do this,
     * since only it holds the master key.
     */
    public abstract String decryptKey(final String storedKey);

}

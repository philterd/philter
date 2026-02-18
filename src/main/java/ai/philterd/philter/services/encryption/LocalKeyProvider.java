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

public class LocalKeyProvider extends KeyProvider {

    @Override
    public KeyResponse getKey(String userId) {
        // The keys can be the same for local.
        // TODO: Fix these.
        return new KeyResponse("7pjz77MxDHdtzgqz8v9898989118MB3Yp1a7QCu56EU", "7pjz77MxDHdtzgqz8v9898989118MB3Yp1a7QCu56EU");
    }

}

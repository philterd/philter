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
package ai.philterd.philter.api.responses;

/**
 * Response body for {@code POST /api/reidentify/text}: the text with each {@code CRYPTO_REPLACE} token that
 * decrypted replaced by its original value, and how many tokens were found, restored, and left as they were.
 */
public class ReidentifyTextResponse {

    private final String text;
    private final int found;
    private final int restored;
    private final int failed;

    public ReidentifyTextResponse(final String text, final int found, final int restored, final int failed) {
        this.text = text;
        this.found = found;
        this.restored = restored;
        this.failed = failed;
    }

    public String getText() {
        return text;
    }

    public int getFound() {
        return found;
    }

    public int getRestored() {
        return restored;
    }

    public int getFailed() {
        return failed;
    }

}

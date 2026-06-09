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

import java.util.List;

/**
 * Response body for {@code POST /api/reidentify}. Each element in {@code results} corresponds to
 * one input value from the request. A successful reversal has {@code decrypted} set and
 * {@code error} null; a failed reversal has {@code error} set and {@code decrypted} null.
 */
public class ReidentifyResponse {

    public static class ReidentifyResult {

        private final String encrypted;
        private final String decrypted;
        private final String error;

        public ReidentifyResult(final String encrypted, final String decrypted) {
            this.encrypted = encrypted;
            this.decrypted = decrypted;
            this.error = null;
        }

        public ReidentifyResult(final String encrypted, final String decrypted, final String error) {
            this.encrypted = encrypted;
            this.decrypted = decrypted;
            this.error = error;
        }

        public String getEncrypted() {
            return encrypted;
        }

        public String getDecrypted() {
            return decrypted;
        }

        public String getError() {
            return error;
        }

    }

    private final List<ReidentifyResult> results;

    public ReidentifyResponse(final List<ReidentifyResult> results) {
        this.results = results;
    }

    public List<ReidentifyResult> getResults() {
        return results;
    }

}

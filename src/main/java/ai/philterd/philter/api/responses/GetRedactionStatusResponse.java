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

public class GetRedactionStatusResponse {

    private final String status;
    private final String documentId;

    public GetRedactionStatusResponse(final String status, final String documentId) {
        this.status = status;
        this.documentId = documentId;
    }

    public String getStatus() {
        return status;
    }

    public String getDocumentId() {
        return documentId;
    }

}

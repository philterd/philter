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

public class RedactTextResponse {

    private boolean success;
    private String message;
    private String redactedText;
    private long tokens;
    private long redactions;

    public static RedactTextResponse success(final String redactedText, final long tokens, final long redactions) {
        final RedactTextResponse response = new RedactTextResponse();
        response.setRedactedText(redactedText);
        response.setSuccess(true);
        response.setTokens(tokens);
        response.setRedactions(redactions);
        response.setMessage("");
        return response;
    }

    public static RedactTextResponse failure(final String message) {
        final RedactTextResponse response = new RedactTextResponse();
        response.setMessage(message);
        response.setSuccess(false);
        return response;
    }

    private RedactTextResponse() {

    }

    private void setRedactedText(final String redactedText) {
        this.redactedText = redactedText;
    }

    private void setMessage(final String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }

    private void setSuccess(final boolean success) {
        this.success = success;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getRedactedText() {
        return redactedText;
    }

    public long getTokens() {
        return tokens;
    }

    public void setTokens(long tokens) {
        this.tokens = tokens;
    }

    public long getRedactions() {
        return redactions;
    }

    public void setRedactions(long redactions) {
        this.redactions = redactions;
    }

}

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
package ai.philterd.philter.api.exceptions;

import ai.philterd.philter.api.responses.GenericResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** An error body without a reason gets the one its status implies; anything else passes through untouched. */
class ErrorReasonAdviceTest {

    private final ErrorReasonAdvice advice = new ErrorReasonAdvice();

    private Object write(final int status, final Object body) {
        final MockHttpServletResponse servlet = new MockHttpServletResponse();
        servlet.setStatus(status);
        return advice.beforeBodyWrite(body, null, null, null, null, new ServletServerHttpResponse(servlet));
    }

    @Test
    @DisplayName("A GenericResponse error without a reason gets the status's reason, keeping its message and field")
    void fillsAGenericResponse() {
        final GenericResponse filled = (GenericResponse) write(409, new GenericResponse("Taken.", null, "name"));
        assertEquals("Taken.", filled.getMessage());
        assertEquals("conflict", filled.getReason());
        assertEquals("name", filled.getField());
    }

    @Test
    @DisplayName("A JSON string error without a reason gets one, written without HTML escaping")
    void fillsAJsonString() {
        assertEquals("{\"message\":\"Use older_than_days=30.\",\"reason\":\"invalid_request\"}",
                write(400, "{\"message\":\"Use older_than_days=30.\"}"));
    }

    @Test
    @DisplayName("A reason the handler gave, a success, and a non-error body are left alone")
    void leavesOthersAlone() {
        final GenericResponse specific = new GenericResponse("No.", "self_action_refused");
        assertSame(specific, write(409, specific));
        final GenericResponse success = new GenericResponse("Deleted.");
        assertSame(success, write(200, success));
        assertEquals("{\"chains\":[]}", write(404, "{\"chains\":[]}"));
        assertEquals("plain", write(400, "plain"));
    }

}

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

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RestApiExceptionsTest {

    private final RestApiExceptions handler = new RestApiExceptions();

    // ----- Unit tests: handler logic -----

    @Test
    void missingAuthorizationHeaderReturnsUnauthorizedMessage() {
        final MissingRequestHeaderException ex = mock(MissingRequestHeaderException.class);
        when(ex.getHeaderName()).thenReturn(HttpHeaders.AUTHORIZATION);

        assertEquals("Unauthorized.", handler.handleMissingRequestHeaderException(ex));
    }

    @Test
    void missingAuthorizationHeaderMatchIsCaseInsensitive() {
        // The header name from Spring is lowercase in some servlet containers.
        final MissingRequestHeaderException ex = mock(MissingRequestHeaderException.class);
        when(ex.getHeaderName()).thenReturn("authorization");

        assertEquals("Unauthorized.", handler.handleMissingRequestHeaderException(ex));
    }

    @Test
    void missingOtherRequiredHeaderReturnsGenericMessage() {
        final MissingRequestHeaderException ex = mock(MissingRequestHeaderException.class);
        when(ex.getHeaderName()).thenReturn("X-Custom-Header");

        assertEquals("A required header is missing.", handler.handleMissingRequestHeaderException(ex));
    }

    // ----- MockMvc tests: HTTP status mapping -----
    //
    // Spring's @ResponseStatus on the handler method only takes effect when the exception is
    // processed through the DispatcherServlet. These tests use a minimal stub controller whose
    // required @RequestHeader triggers MissingRequestHeaderException naturally, so the full
    // ControllerAdvice → response-status chain is exercised.

    /** Minimal controller whose single endpoint requires the Authorization header. */
    @RestController
    static class StubController {
        @GetMapping("/stub/auth")
        public String requiresAuth(
                final @RequestHeader(HttpHeaders.AUTHORIZATION) String auth) {
            return "ok";
        }

        @GetMapping("/stub/custom")
        public String requiresCustomHeader(
                final @RequestHeader("X-Custom-Header") String custom) {
            return "ok";
        }
    }

    private MockMvc buildMockMvc() {
        return MockMvcBuilders.standaloneSetup(new StubController())
                .setControllerAdvice(handler)
                .build();
    }

    @Test
    void missingAuthorizationHeaderYields401() throws Exception {
        final String body = buildMockMvc()
                .perform(get("/stub/auth"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        assertEquals("Unauthorized.", body);
    }

    @Test
    void missingOtherRequiredHeaderYields401() throws Exception {
        final String body = buildMockMvc()
                .perform(get("/stub/custom"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        assertEquals("A required header is missing.", body);
    }

}

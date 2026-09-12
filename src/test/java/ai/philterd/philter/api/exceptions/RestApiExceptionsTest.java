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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        @GetMapping(value = "/stub/auth", produces = "text/plain")
        public String requiresAuth(
                final @RequestHeader(HttpHeaders.AUTHORIZATION) String auth) {
            return "ok";
        }

        @GetMapping("/stub/custom")
        public String requiresCustomHeader(
                final @RequestHeader("X-Custom-Header") String custom) {
            return "ok";
        }

        /** Stands in for SizeLimitingInputStream, which throws this once a body exceeds its limit. */
        @GetMapping("/stub/toolarge")
        public String tooLarge() {
            throw new PayloadTooLargeException("The request body exceeds the maximum allowed size of 10240 bytes.");
        }

        /** Stands in for ContentTypeVerifyingFilter, which throws this on a mislabelled body. */
        @GetMapping("/stub/wrongtype")
        public String wrongType() {
            throw new UnsupportedMediaTypeException("The request declares text/plain but the body is PDF.");
        }

        /** Stands in for any validation that rejects what the caller sent. */
        @GetMapping("/stub/badrequest")
        public String badRequest() {
            throw new BadRequestException("Password-protected PDF input is not supported.");
        }

        @GetMapping("/stub/badrequest-nomessage")
        public String badRequestWithoutMessage() {
            throw new BadRequestException(null);
        }

        /** Its message is a filesystem path, which must not reach the caller. */
        @GetMapping("/stub/notfound")
        public String fileNotFound() throws java.io.FileNotFoundException {
            throw new java.io.FileNotFoundException("/srv/philter/secret/models/config.json (No such file or directory)");
        }
    }

    @RestController
    static class JsonOnlyController {
        @org.springframework.web.bind.annotation.PostMapping(value = "/stub/json", consumes = "application/json")
        public String acceptJson(@org.springframework.web.bind.annotation.RequestBody String body) { return body; }
    }

    @Test
    void unsupportedRequestContentTypeReturns415() throws Exception {
        MockMvcBuilders.standaloneSetup(new JsonOnlyController()).setControllerAdvice(handler).build()
                .perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/stub/json")
                        .contentType("text/plain").content("text"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void aBadRequestIsAnsweredWithTheMessageItWasConstructedWith() throws Exception {
        final String body = buildMockMvc()
                .perform(get("/stub/badrequest"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertEquals("Password-protected PDF input is not supported.", body);
    }

    @Test
    void aBadRequestWithNoMessageStillSaysSomethingUseful() throws Exception {
        final String body = buildMockMvc()
                .perform(get("/stub/badrequest-nomessage"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertEquals("A required parameter is missing or contains an invalid value.", body);
    }

    @Test
    void aFileNotFoundLeaksNeitherThePathNorTheCause() throws Exception {
        final String body = buildMockMvc()
                .perform(get("/stub/notfound"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertEquals("The request body is missing or could not be read.", body);
        assertFalse(body.contains("/srv/philter"), "a server path must not reach the caller: " + body);
    }

    @Test
    void anUnreadableBodyLeaksNoParserDetail() throws Exception {
        final String body = MockMvcBuilders.standaloneSetup(new JsonOnlyController()).setControllerAdvice(handler).build()
                .perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/stub/json")
                        .contentType("application/json").content(""))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertEquals("The request body is missing or could not be read.", body);
        assertFalse(body.toLowerCase(java.util.Locale.ROOT).contains("jackson"), body);
        assertFalse(body.contains("nested exception"), body);
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
    void aMislabelledBodyYields415AndNamesBothTypes() throws Exception {
        // Without a handler this fell to the catch-all as a 500, which tells the caller nothing
        // about why a document they believe is fine was refused.
        final String body = buildMockMvc()
                .perform(get("/stub/wrongtype"))
                .andExpect(status().isUnsupportedMediaType())
                .andReturn().getResponse().getContentAsString();
        assertEquals("The request declares text/plain but the body is PDF.", body);
    }

    @Test
    void oversizedPayloadYields413AndNamesTheLimit() throws Exception {
        // Without a handler this fell through to the Exception catch-all and returned
        // 500 "An unknown error has occurred.", telling the caller nothing.
        final String body = buildMockMvc()
                .perform(get("/stub/toolarge"))
                .andExpect(status().isPayloadTooLarge())
                .andReturn().getResponse().getContentAsString();
        assertEquals("The request body exceeds the maximum allowed size of 10240 bytes.", body);
    }

    @Test
    void missingOtherRequiredHeaderYields401() throws Exception {
        final String body = buildMockMvc()
                .perform(get("/stub/custom"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        assertEquals("A required header is missing.", body);
    }

    @Test
    void incompatibleAcceptReturns406() throws Exception {
        buildMockMvc().perform(get("/stub/auth").header("Authorization", "Bearer test").accept("image/png"))
                .andExpect(status().isNotAcceptable());
    }

    @Test
    void admissionRejectionsPreserveStatusAndRetryGuidance() {
        for (int status : new int[] {429, 503}) {
            var response = handler.handleQueueCapacity(new ai.philterd.philter.data.services.QueueCapacityException("busy", status));
            assertEquals(status, response.getStatusCode().value());
            assertEquals("5", response.getHeaders().getFirst("Retry-After"));
        }
    }

}

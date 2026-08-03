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
package ai.philterd.philter.api.filters.size;

import ai.philterd.philter.api.exceptions.PayloadTooLargeException;
import ai.philterd.philter.model.Constants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The size limit was previously keyed to {@code /api/redact/documents} and {@code /api/risk}, neither
 * of which exists, so the redaction endpoint silently fell through to the much smaller configuration
 * limit and rejected any real document. These tests pin which limit each endpoint gets.
 */
class SizeLimitingFilterTest {

    /** Reads the wrapped request body, which is what actually enforces the limit. */
    private static Exception filterAndReadBody(final String path, final String method, final byte[] body)
            throws Exception {

        final MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setContent(body);

        final Exception[] captured = new Exception[1];
        final HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
        final SizeLimitingFilter filter = new SizeLimitingFilter(resolver);

        final FilterChain chain = (req, res) -> {
            try {
                ((HttpServletRequest) req).getInputStream().readAllBytes();
            } catch (final PayloadTooLargeException e) {
                captured[0] = e;
            }
        };

        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return captured[0];
    }

    private static byte[] bytes(final int length) {
        return "x".repeat(length).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void filterEndpointAcceptsABodyLargerThanTheConfigurationLimit() throws Exception {
        // The exact size that used to fail: comfortably over 10 KB, far under 10 MB.
        final Exception thrown = filterAndReadBody("/api/filter", "POST",
                bytes((int) Constants.MAX_FILE_SIZE_BYTES_OTHER * 4));

        assertNull(thrown, "a document endpoint must accept more than the configuration limit");
    }

    @Test
    void explainEndpointAlsoAcceptsADocumentSizedBody() throws Exception {
        final Exception thrown = filterAndReadBody("/api/explain", "POST",
                bytes((int) Constants.MAX_FILE_SIZE_BYTES_OTHER * 4));

        assertNull(thrown, "explain accepts document content and must take the document limit");
    }

    @Test
    void filterEndpointRejectsABodyOverTheDocumentLimit() throws Exception {
        final Exception thrown = filterAndReadBody("/api/filter", "POST",
                bytes(Constants.MAX_FILE_SIZE_BYTES + 1));

        assertNotNull(thrown, "a body over the document limit must be rejected");
        assertInstanceOf(PayloadTooLargeException.class, thrown);
        assertTrue(thrown.getMessage().contains(String.valueOf(Constants.MAX_FILE_SIZE_BYTES)),
                "the message must state the limit: " + thrown.getMessage());
    }

    @Test
    void configurationEndpointsKeepTheSmallerLimit() throws Exception {
        final Exception thrown = filterAndReadBody("/api/policies", "POST",
                bytes((int) Constants.MAX_FILE_SIZE_BYTES_OTHER + 1));

        assertNotNull(thrown, "a configuration body over the smaller limit must still be rejected");
        assertInstanceOf(PayloadTooLargeException.class, thrown);
    }

    @Test
    void getRequestsAreNotSizeLimited() throws Exception {
        assertNull(filterAndReadBody("/api/ledger", "GET", bytes(Constants.MAX_FILE_SIZE_BYTES + 1)));
    }

    @Test
    void noSizeLimitIsKeyedToAnEndpointThatDoesNotExist() {
        // Guards the actual defect: a limit pinned to a path no controller serves is silently dead.
        for (final String endpoint : FilterConstants.DOCUMENT_ENDPOINTS) {
            assertTrue(endpoint.equals("/api/filter") || endpoint.equals("/api/explain"),
                    "unexpected document endpoint, verify a controller serves it: " + endpoint);
        }
        assertEquals(2, FilterConstants.DOCUMENT_ENDPOINTS.size());
    }

}

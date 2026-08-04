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
package ai.philterd.philter.api.filters.content;

import ai.philterd.philter.api.exceptions.UnsupportedMediaTypeException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A mislabelled document fails in the worst direction without this check: a PDF sent as
 * {@code text/plain} runs through the text pipeline, matches almost nothing in the compressed bytes,
 * and returns {@code 200} with output that looks redacted. These tests pin that such a request is
 * refused, that legitimate requests are untouched, and that the body still arrives intact.
 */
class ContentTypeVerifyingFilterTest {

    private static final byte[] PDF = "%PDF-1.7\nrest of the document".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DOCX = {0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x06, 0x00, 0x11, 0x22};
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01};
    private static final byte[] TEXT = "Patient SSN 123-45-6789.".getBytes(StandardCharsets.UTF_8);
    /** Compound File Binary Format, the container behind legacy .doc, .xls and .ppt. */
    private static final byte[] OLE2 = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
            (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1, 0x00, 0x00};
    private static final byte[] GIF = {0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10};

    /** Runs the filter, returning the exception it refused with, or null when it passed the request on. */
    private static Exception filter(final String path, final String contentType, final byte[] body,
                                    final byte[][] bodySeenByChain) throws Exception {

        final MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setContentType(contentType);
        request.setContent(body);

        final Exception[] refused = new Exception[1];
        final HandlerExceptionResolver resolver = (req, res, handler, ex) -> {
            refused[0] = ex;
            return new ModelAndView();
        };

        final FilterChain chain = (req, res) -> {
            if (bodySeenByChain != null) {
                bodySeenByChain[0] = ((HttpServletRequest) req).getInputStream().readAllBytes();
            }
        };

        new ContentTypeVerifyingFilter(resolver).doFilter(request, new MockHttpServletResponse(), chain);
        return refused[0];
    }

    @Test
    void aPdfDeclaredAsTextIsRefused() throws Exception {
        final Exception refused = filter("/api/filter", "text/plain", PDF, null);

        assertNotNull(refused, "a PDF declared as text must not be redacted as text");
        assertInstanceOf(UnsupportedMediaTypeException.class, refused);
        assertTrue(refused.getMessage().contains("text/plain"), refused.getMessage());
        assertTrue(refused.getMessage().contains("PDF"), refused.getMessage());
    }

    @Test
    void anOfficeDocumentDeclaredAsTextIsRefused() throws Exception {
        // The case detection alone could not catch: a .docx is a ZIP, so "not a PDF" would have
        // fallen through to the text pipeline and scanned compressed bytes.
        final Exception refused = filter("/api/filter", "text/plain", DOCX, null);

        assertNotNull(refused);
        assertTrue(refused.getMessage().contains("Office Open XML"), refused.getMessage());
    }

    @Test
    void anImageDeclaredAsTextIsRefused() throws Exception {
        final Exception refused = filter("/api/filter", "text/plain", PNG, null);

        assertNotNull(refused);
        assertTrue(refused.getMessage().contains("PNG"), refused.getMessage());
    }

    @Test
    void aLegacyOfficeDocumentDeclaredAsTextIsRefused() throws Exception {
        final Exception refused = filter("/api/filter", "text/plain", OLE2, null);

        assertNotNull(refused);
        assertTrue(refused.getMessage().contains("legacy Microsoft Office"), refused.getMessage());
    }

    @Test
    void aGifDeclaredAsTextIsRefused() throws Exception {
        final Exception refused = filter("/api/filter", "text/plain", GIF, null);

        assertNotNull(refused);
        assertTrue(refused.getMessage().contains("GIF"), refused.getMessage());
    }

    @Test
    void aJpegDeclaredAsTextIsRefused() throws Exception {
        // Philter does not accept images on any endpoint, so a JPEG is refused rather than scanned
        // as prose.
        final Exception refused = filter("/api/filter", "text/plain", JPEG, null);

        assertNotNull(refused);
        assertTrue(refused.getMessage().contains("JPEG"), refused.getMessage());
    }

    @Test
    void textDeclaredAsPdfIsRefused() throws Exception {
        final Exception refused = filter("/api/filter", "application/pdf", TEXT, null);

        assertNotNull(refused);
        assertTrue(refused.getMessage().contains("application/pdf"), refused.getMessage());
    }

    @Test
    void aGenuinePdfIsAcceptedAndArrivesIntact() throws Exception {
        final byte[][] seen = new byte[1][];
        final Exception refused = filter("/api/filter", "application/pdf", PDF, seen);

        assertNull(refused, "a valid PDF must pass");
        assertArrayEquals(PDF, seen[0], "peeking the signature must not consume the body");
    }

    @Test
    void genuineTextIsAcceptedAndArrivesIntact() throws Exception {
        final byte[][] seen = new byte[1][];
        final Exception refused = filter("/api/filter", "text/plain", TEXT, seen);

        assertNull(refused);
        assertArrayEquals(TEXT, seen[0]);
    }

    @Test
    void aBodyShorterThanASignatureIsNotRefused() throws Exception {
        final byte[][] seen = new byte[1][];
        final byte[] tiny = "hi".getBytes(StandardCharsets.UTF_8);

        assertNull(filter("/api/filter", "text/plain", tiny, seen));
        assertArrayEquals(tiny, seen[0], "a body shorter than the peek must still arrive in full");
    }

    @Test
    void anEmptyBodyIsLeftToTheEndpoint() throws Exception {
        assertNull(filter("/api/filter", "text/plain", new byte[0], null),
                "an empty body has nothing to contradict");
    }

    @Test
    void theExplainEndpointIsVerifiedToo() throws Exception {
        assertNotNull(filter("/api/explain", "text/plain", PDF, null),
                "explain accepts document content and must be checked as well");
    }

    @Test
    void otherEndpointsAreNotTouched() throws Exception {
        final byte[][] seen = new byte[1][];
        final byte[] policy = "{\"identifiers\":{}}".getBytes(StandardCharsets.UTF_8);

        assertNull(filter("/api/policies", "application/json", policy, seen));
        assertArrayEquals(policy, seen[0]);
    }

    @Test
    void detectionNamesEachFormatItRecognizes() {
        assertEquals(DocumentType.PDF, DocumentType.detect(PDF));
        assertEquals(DocumentType.ZIP, DocumentType.detect(DOCX));
        assertEquals(DocumentType.PNG, DocumentType.detect(PNG));
        assertEquals(DocumentType.OLE2, DocumentType.detect(OLE2));
        assertEquals(DocumentType.GIF, DocumentType.detect(GIF));
        assertEquals(DocumentType.JPEG, DocumentType.detect(JPEG));
        assertEquals(DocumentType.UNKNOWN, DocumentType.detect(TEXT));
        assertEquals(DocumentType.UNKNOWN, DocumentType.detect(new byte[0]));
        assertEquals(DocumentType.UNKNOWN, DocumentType.detect(null));
    }

}

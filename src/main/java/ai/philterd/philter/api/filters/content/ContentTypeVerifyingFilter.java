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
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
import java.util.Set;

/**
 * Verifies that a submitted document is what the caller said it is.
 *
 * <p>Philter routes on the declared {@code Content-Type}. Without this check a mislabelled body fails
 * silently in the worst direction: a PDF sent as {@code text/plain} runs through the text pipeline,
 * matches almost nothing in the compressed bytes, and returns {@code 200} with output that looks
 * redacted. The caller receives a false assurance rather than an error.
 *
 * <p>Detection does not replace the declared type, it cross-checks it. PDF has a signature and plain
 * text does not, so detection alone would have to treat anything unrecognized as text, which is how a
 * {@code .docx} (a ZIP underneath) would slip through. The declared type turns that open-ended
 * blocklist into a closed allowlist.
 *
 * <p>Detection is unconditional. Comparing eight bytes costs on the order of a nanosecond, so there
 * is nothing to gain by making it configurable.
 */
public class ContentTypeVerifyingFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ContentTypeVerifyingFilter.class);

    /** Endpoints that accept a document body. */
    private static final Set<String> VERIFIED_PATHS = Set.of("/api/filter", "/api/explain");

    private final HandlerExceptionResolver resolver;

    public ContentTypeVerifyingFilter(@Qualifier("handlerExceptionResolver") final HandlerExceptionResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response,
                                    final FilterChain filterChain) throws ServletException, IOException {

        if (!"POST".equalsIgnoreCase(request.getMethod()) || !VERIFIED_PATHS.contains(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        final ContentTypeVerifyingRequestWrapper wrapped = new ContentTypeVerifyingRequestWrapper(request);
        final DocumentType detected = DocumentType.detect(wrapped.getLeadingBytes());
        final String declared = request.getContentType();

        try {
            verify(declared, detected);
        } catch (final UnsupportedMediaTypeException e) {
            LOGGER.warn("Rejected a request whose body contradicts its declared content type: {}", e.getMessage());
            if (resolver.resolveException(request, response, null, e) == null) {
                throw e;
            }
            return;
        }

        filterChain.doFilter(wrapped, response);

    }

    private static void verify(final String declared, final DocumentType detected) {

        // An empty body has nothing to contradict. Endpoints reject it on their own terms.
        if (detected == DocumentType.UNKNOWN && isDeclaredText(declared)) {
            return;
        }

        if (isDeclaredPdf(declared)) {
            if (detected != DocumentType.PDF) {
                throw new UnsupportedMediaTypeException("The request declares application/pdf but the body is "
                        + detected.getDescription() + ".");
            }
            return;
        }

        if (isDeclaredText(declared) && detected != DocumentType.UNKNOWN) {
            // Text has no signature, so anything recognized as a binary format is not text.
            throw new UnsupportedMediaTypeException("The request declares text/plain but the body is "
                    + detected.getDescription() + ".");
        }

    }

    private static boolean isDeclaredPdf(final String contentType) {
        return contentType != null && contentType.toLowerCase().startsWith("application/pdf");
    }

    private static boolean isDeclaredText(final String contentType) {
        return contentType != null && contentType.toLowerCase().startsWith("text/plain");
    }

}

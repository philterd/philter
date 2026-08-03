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

import ai.philterd.philter.model.Constants;
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

/**
 * A filter that wraps incoming requests with a SizeLimitingRequestWrapper
 * to enforce file size limits on API endpoints.
 * Document endpoints take {@code MAX_FILE_SIZE_BYTES}; all other POST and PUT bodies take
 * {@code MAX_FILE_SIZE_BYTES_OTHER}.
 * 
 * This filter uses HandlerExceptionResolver to forward exceptions to the
 * GlobalSaasExceptionHandler for consistent error handling.
 */
public class SizeLimitingFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(SizeLimitingFilter.class);

    private final HandlerExceptionResolver resolver;

    /**
     * Creates a new SizeLimitingFilter with the specified exception resolver.
     *
     * @param resolver the handler exception resolver to use for forwarding exceptions
     */
    public SizeLimitingFilter(@Qualifier("handlerExceptionResolver") final HandlerExceptionResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response, final FilterChain filterChain)
            throws ServletException, IOException {

        final String path = request.getRequestURI();
        final String method = request.getMethod();

        // Determine the appropriate size limit based on endpoint and method
        long sizeLimit = -1;

        if ("POST".equalsIgnoreCase(method) && FilterConstants.DOCUMENT_ENDPOINTS.contains(path)) {

            // Endpoints that accept a document to redact.
            sizeLimit = Constants.MAX_FILE_SIZE_BYTES;

        } else if ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)) {

            // Everything else carries configuration, not documents.
            sizeLimit = Constants.MAX_FILE_SIZE_BYTES_OTHER;

        }

        if (sizeLimit > 0) {
            LOGGER.debug("Applying a {} byte size limit to {} {}", sizeLimit, method, path);
        }

        if (sizeLimit > 0) {

            try {
                final SizeLimitingRequestWrapper wrappedRequest = new SizeLimitingRequestWrapper(request, sizeLimit);
                filterChain.doFilter(wrappedRequest, response);
            } catch (Exception e) {
                // Forward the exception to the Global Controller Advice
                final var modelAndView = resolver.resolveException(request, response, null, e);
                if (modelAndView == null) {
                    // If the resolver couldn't handle it, re-throw the exception
                    throw e;
                }
            }

        } else {

            // For all other requests (GET, DELETE, etc.), pass through without size limiting
            filterChain.doFilter(request, response);

        }

    }

}

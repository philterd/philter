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
 * - Document redaction and risk assessment uploads: 10MB limit
 * - All other POST and PUT endpoints: 10KB limit
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

        if ("POST".equalsIgnoreCase(method) && (path.equals(FilterConstants.DOCUMENT_REDACTION_ENDPOINT) || path.equals(FilterConstants.RISK_ASSESSMENT_ENDPOINT))) {

            // Document redaction and risk assessment uploads
            sizeLimit = Constants.MAX_FILE_SIZE_BYTES;
            LOGGER.debug("Applying 10MB size limit to request: {} {}", method, path);

        } else if ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)) {

            // All other POST and PUT endpoints
            sizeLimit = Constants.MAX_FILE_SIZE_BYTES_OTHER;
            LOGGER.debug("Applying 10KB size limit to request: {} {}", method, path);

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

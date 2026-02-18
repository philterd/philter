package ai.philterd.philter.api.filters.auth;

import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.services.RequestIdGenerator;
import ai.philterd.philter.services.usage.AbstractUsageService;
import com.google.gson.Gson;
import com.mongodb.client.MongoClient;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.net.util.SubnetUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.filter.GenericFilterBean;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

public class ApiAuthenticationFilter extends GenericFilterBean {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiAuthenticationFilter.class);
    private static final Pattern API_KEY_PATTERN = Pattern.compile("^sk_[a-zA-Z0-9]{32}$");

    private final ApiKeyDataService apiKeyService;
    private final Gson gson;

    public ApiAuthenticationFilter(final MongoClient mongoClient, final AuditEventPublisher auditEventPublisher, final Gson gson) {
        this.apiKeyService = new ApiKeyDataService(mongoClient, auditEventPublisher);
        this.gson = gson;
    }

    @Override
    public void doFilter(final ServletRequest request, final ServletResponse response, final FilterChain chain) throws IOException, ServletException {

        final String path = ((HttpServletRequest) request).getRequestURI();

        LOGGER.debug("API path requested: {}", path);

        // Don't authorize requests to health check endpoint.
        if ("/health".equals(path) || "/actuator/health".equals(path)) {

            LOGGER.trace("Request to health check endpoint, ignoring: {}", path);

            final Map<String, Object> health = Map.of("health", "ok");

            final Properties props = new Properties();
            props.load(getClass().getClassLoader().getResourceAsStream("git.properties"));

            // Use the short hash.
            final String gitHash = props.getProperty("git.commit.id.full").substring(0, 7);

            // Just return HTTP OK here without going to a controller.
            HttpServletResponse httpServletResponse = (HttpServletResponse) response;
            httpServletResponse.setContentType("application/json");
            httpServletResponse.setCharacterEncoding("UTF-8");
            httpServletResponse.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
            httpServletResponse.setHeader("Pragma", "no-cache");
            httpServletResponse.setHeader("X-App-Version", gitHash);
            httpServletResponse.setStatus(HttpServletResponse.SC_OK);

            final String responseToClient = gson.toJson(health);

            httpServletResponse.getWriter().write(responseToClient);
            httpServletResponse.getWriter().flush();

        } else if (path.startsWith("/v3/api-docs") || path.startsWith("/swagger-ui/")) {

            // Allow all access to the OpenAPI specs.
            chain.doFilter(request, response);

        } else {

            // Create a request ID.
            final String requestId = RequestIdGenerator.generate();
            request.setAttribute("requestId", requestId);

            final HttpServletRequest httpRequest = (HttpServletRequest) request;
            String apiKey = httpRequest.getHeader("Authorization");
            ApiKeyEntity apiKeyEntity = null;

            if (apiKey != null) {

                if (apiKey.startsWith("Bearer ")) {
                    apiKey = apiKey.substring(7).trim();
                }

                // Validate the API key format before checking the database.
                if (!API_KEY_PATTERN.matcher(apiKey).matches()) {

                    LOGGER.warn("Unauthorized request");

                    final HttpServletResponse httpServletResponse = (HttpServletResponse) response;
                    httpServletResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    final String errorMessage = "{\"error\": \"Unauthorized\", \"message\": \"Unauthorized request\"}";
                    response.getWriter().write(errorMessage);
                    return;

                }

                // Look up the API key in the database.
                apiKeyEntity = apiKeyService.findOneByApiKey(apiKey);

            }

            if (apiKeyEntity != null) {

                // TODO: Implement IP address restrictions.

            } else {

                LOGGER.warn("Unauthorized access attempt with header: {}", apiKey);

                HttpServletResponse httpServletResponse = (HttpServletResponse) response;
                httpServletResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                String errorMessage = "{\"error\": \"Unauthorized\", \"message\": \"Invalid or missing credentials\"}";
                response.getWriter().write(errorMessage);
                return;

            }

            final ContentCachingRequestWrapper reqWrapper = new ContentCachingRequestWrapper((HttpServletRequest) request);
            final ContentCachingResponseWrapper resWrapper = new ContentCachingResponseWrapper((HttpServletResponse) response);

            final long startTime = System.currentTimeMillis();

            try {

                chain.doFilter(reqWrapper, resWrapper);

            } finally {

                final long duration = System.currentTimeMillis() - startTime;
                final int status = resWrapper.getStatus();

                final Map<String, Object> logEntry = new HashMap<>();
                logEntry.put("timestamp", AbstractUsageService.getNow());
                logEntry.put("method", reqWrapper.getMethod());
                logEntry.put("uri", reqWrapper.getRequestURI());
                logEntry.put("status", status);
                logEntry.put("duration_ms", duration);
                logEntry.put("api_key", apiKey);

                // TODO: Index this API request (if enabled).
                queueApiRequest(logEntry);

                // Copy the request back to the client.
                resWrapper.copyBodyToResponse();

            }

        }

    }

    @Async
    protected void queueApiRequest(final Map<String, Object> logEntry) {
        // TODO: Put this request onto a queue to be indexed.
    }

    public boolean isIpAddressAllowed(final String ipAddress) {

        return true;

//        final List<String> ipAddressRestrictions = getIpAddressRestrictionsAsList();
//
//        for (final String ipAddressRestriction : ipAddressRestrictions) {
//
//            try {
//
//                final SubnetUtils utils = new SubnetUtils(ipAddressRestriction);
//                utils.setInclusiveHostCount(true);
//                final SubnetUtils.SubnetInfo info = utils.getInfo();
//                final boolean isInRange = info.isInRange(ipAddress);
//
//                if (isInRange) {
//                    return true;
//                }
//
//            } catch (Exception ex) {
//
//                // Probably a malformed IP address or CIDR range.
//                LOGGER.error("Error checking IP address {} against IP address restrictions: {}", ipAddress, ex.getMessage());
//
//            }
//
//        }
//
//        return false;

    }

}
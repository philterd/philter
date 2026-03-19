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
package ai.philterd.philter.api.filters.auth;

import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.services.RequestIdGenerator;
import ai.philterd.philter.services.usage.AbstractUsageService;
import ai.philterd.philter.services.usage.apirequests.OpenSearchApiRequestsUsageService;
import com.google.gson.Gson;
import com.mongodb.client.MongoClient;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.filter.GenericFilterBean;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

public class ApiAuthenticationFilter extends GenericFilterBean {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiAuthenticationFilter.class);
    private static final Pattern API_KEY_PATTERN = Pattern.compile("^sk_[a-zA-Z0-9]{32}$");
    private static final String API_REQUESTS_INDEXING_ENABLED = System.getenv().getOrDefault("API_REQUESTS_INDEXING_ENABLED", "false");

    private final ApiKeyDataService apiKeyService;
    private final OpenSearchApiRequestsUsageService openSearchApiRequestsUsageService;
    private final Gson gson;

    public ApiAuthenticationFilter(final MongoClient mongoClient, final AuditEventPublisher auditEventPublisher, final OpenSearchApiRequestsUsageService openSearchApiRequestsUsageService, final Gson gson) {
        this.apiKeyService = new ApiKeyDataService(mongoClient, auditEventPublisher);
        this.openSearchApiRequestsUsageService = openSearchApiRequestsUsageService;
        this.gson = gson;
    }

    @Override
    public void doFilter(final ServletRequest request, final ServletResponse response, final FilterChain chain) throws IOException, ServletException {

        final String path = ((HttpServletRequest) request).getRequestURI();

        LOGGER.debug("API path requested: {}", path);

        // Don't authorize requests to health check endpoint.
        if ("/api/health".equals(path) || "/api/status".equals(path)) {

            LOGGER.trace("Request to health check/status endpoint, allowing: {}", path);

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
            httpServletResponse.setStatus(HttpServletResponse.SC_OK);

            final Map<String, Object> health = Map.of("health", "ok", "git-commit", gitHash);

            final String responseToClient = gson.toJson(health);

            httpServletResponse.getWriter().write(responseToClient);
            httpServletResponse.getWriter().flush();

        } else if (path.startsWith("/v3/api-docs") || path.startsWith("/swagger-ui/")) {

            // Allow all access to the OpenAPI specs.
            chain.doFilter(request, response);

        } else if(path.startsWith("/api/")) {

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

            if(apiKeyEntity != null) {

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

            // TODO: Set an appropriate value for the limit.
            final ContentCachingRequestWrapper reqWrapper = new ContentCachingRequestWrapper((HttpServletRequest) request, 0);
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

                // Index this API request (if enabled).
                if(Strings.CI.equals(API_REQUESTS_INDEXING_ENABLED, "true")) {
                    indexApiRequest(logEntry);
                }

                // Copy the request back to the client.
                resWrapper.copyBodyToResponse();

            }

        } else {

            chain.doFilter(request, response);

        }

    }

    @Async
    protected void indexApiRequest(final Map<String, Object> logEntry) {

        try {
            openSearchApiRequestsUsageService.index(logEntry);
        } catch (Exception ex) {
            LOGGER.error("Error indexing API request: {}", ex.getMessage());
        }

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
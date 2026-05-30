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

import ai.philterd.philter.api.controllers.AbstractApiController;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.services.RequestIdGenerator;
import com.google.gson.Gson;
import com.mongodb.client.MongoClient;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.net.util.SubnetUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.GenericFilterBean;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class ApiAuthenticationFilter extends GenericFilterBean {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiAuthenticationFilter.class);
    private static final Pattern API_KEY_PATTERN = Pattern.compile("^sk_[a-zA-Z0-9]{32}$");

    // Optional comma-separated list of IPv4 addresses/CIDR ranges allowed to call the API.
    // Empty (the default) disables the restriction and allows all source addresses.
    private static final List<SubnetUtils.SubnetInfo> IP_ALLOWLIST =
            parseIpAllowlist(System.getenv().getOrDefault("API_IP_ALLOWLIST", ""));

    private final ApiKeyDataService apiKeyService;
    private final MeterRegistry meterRegistry;
    private final Gson gson;

    public ApiAuthenticationFilter(final MongoClient mongoClient, final AuditEventPublisher auditEventPublisher, final MeterRegistry meterRegistry, final Gson gson) {
        this.apiKeyService = new ApiKeyDataService(mongoClient, auditEventPublisher);
        this.meterRegistry = meterRegistry;
        this.gson = gson;
    }

    @Override
    public void doFilter(final ServletRequest request, final ServletResponse response, final FilterChain chain) throws IOException, ServletException {

        final String path = ((HttpServletRequest) request).getRequestURI();

        LOGGER.debug("API path requested: {}", path);

        // The status and health endpoints are unauthenticated and served by
        // StatusApiController, which reports the application version and the
        // supported redaction policy schema version. Both paths return the same
        // response.
        if ("/api/status".equals(path) || "/api/health".equals(path)) {

            LOGGER.trace("Request to status/health endpoint, allowing without authorization: {}", path);
            chain.doFilter(request, response);

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

                // Enforce IP address restrictions, if an allowlist is configured.
                final String clientIpAddress = AbstractApiController.getClientIpAddress(httpRequest);

                if (!isIpAddressAllowed(clientIpAddress)) {

                    LOGGER.warn("Forbidding request from an IP address not permitted by API_IP_ALLOWLIST.");

                    final HttpServletResponse httpServletResponse = (HttpServletResponse) response;
                    httpServletResponse.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\": \"Forbidden\", \"message\": \"Your IP address is not allowed to access this API.\"}");
                    return;

                }

            } else {

                LOGGER.warn("Unauthorized access attempt with header: {}", apiKey);

                HttpServletResponse httpServletResponse = (HttpServletResponse) response;
                httpServletResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                String errorMessage = "{\"error\": \"Unauthorized\", \"message\": \"Invalid or missing credentials\"}";
                response.getWriter().write(errorMessage);
                return;

            }

            // The response is wrapped so its status can be recorded for metrics and then copied
            // back to the client. The request body is intentionally not buffered, since it can be
            // a large upload (for example, a PDF).
            final ContentCachingResponseWrapper resWrapper = new ContentCachingResponseWrapper((HttpServletResponse) response);

            try {

                chain.doFilter(httpRequest, resWrapper);

            } finally {

                // Record the API request for Prometheus, using low-cardinality tags only.
                meterRegistry.counter("philter.api.requests",
                        "method", httpRequest.getMethod(),
                        "status", String.valueOf(resWrapper.getStatus())).increment();

                // Copy the buffered response back to the client.
                resWrapper.copyBodyToResponse();

            }

        } else {

            chain.doFilter(request, response);

        }

    }

    public boolean isIpAddressAllowed(final String ipAddress) {
        return isIpAddressAllowed(IP_ALLOWLIST, ipAddress);
    }

    /**
     * Returns true if the address is permitted by the allowlist. An empty allowlist allows all
     * addresses. Only IPv4 addresses/ranges are supported; when an allowlist is configured, an
     * address that cannot be evaluated (null, malformed, or IPv6) is denied.
     */
    static boolean isIpAddressAllowed(final List<SubnetUtils.SubnetInfo> allowlist, final String ipAddress) {

        // No allowlist configured: all addresses are allowed.
        if (allowlist.isEmpty()) {
            return true;
        }

        if (ipAddress == null || ipAddress.isBlank()) {
            return false;
        }

        for (final SubnetUtils.SubnetInfo subnet : allowlist) {
            try {
                if (subnet.isInRange(ipAddress)) {
                    return true;
                }
            } catch (final Exception ex) {
                // Malformed or non-IPv4 (e.g. IPv6) address cannot match an IPv4 allowlist entry.
                LOGGER.debug("Could not evaluate IP address against allowlist entry {}: {}", subnet.getCidrSignature(), ex.getMessage());
            }
        }

        return false;

    }

    /**
     * Parses a comma-separated list of IPv4 addresses and/or CIDR ranges into matchable subnets.
     * Bare addresses are treated as a single host (/32). Invalid entries are logged and skipped.
     */
    static List<SubnetUtils.SubnetInfo> parseIpAllowlist(final String raw) {

        final List<SubnetUtils.SubnetInfo> allowlist = new ArrayList<>();

        if (raw == null || raw.isBlank()) {
            return allowlist;
        }

        for (String entry : raw.split(",")) {

            entry = entry.trim();

            if (entry.isEmpty()) {
                continue;
            }

            try {
                final String cidr = entry.contains("/") ? entry : entry + "/32";
                final SubnetUtils utils = new SubnetUtils(cidr);
                utils.setInclusiveHostCount(true);
                allowlist.add(utils.getInfo());
            } catch (final Exception ex) {
                LOGGER.error("Ignoring invalid API_IP_ALLOWLIST entry '{}': {}", entry, ex.getMessage());
            }

        }

        return allowlist;

    }

}
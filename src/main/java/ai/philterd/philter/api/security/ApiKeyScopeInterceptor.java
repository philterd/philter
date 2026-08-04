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
package ai.philterd.philter.api.security;

import ai.philterd.philter.api.controllers.AbstractApiController;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.model.ApiKeyScope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * Refuses a request whose API key does not carry the scope its endpoint declares with
 * {@link RequiresScope}.
 *
 * <p>This runs after {@code ApiAuthenticationFilter}, which has already resolved and validated the key
 * and left it on the request. Scopes narrow a key, never widen it: every other check still applies
 * afterwards, so an admin-only operation needs the scope <em>and</em> the admin role, and reaching
 * another user's data needs the {@code owner} parameter and cross-user access on top.
 *
 * <p>The refusal is {@code 403 Forbidden}, not the {@code 404} used for cross-user access. The caller
 * is asking about their own data with a credential they hold, so there is nothing to conceal; telling
 * them the key lacks a scope is actionable and reveals nothing they could not already see.
 *
 * <p>A handler under {@code /api/} that declares no scope is refused. Failing closed means adding an
 * endpoint without a scope makes it unreachable rather than silently unprotected.
 */
public class ApiKeyScopeInterceptor implements HandlerInterceptor {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiKeyScopeInterceptor.class);

    /**
     * Endpoints served without an API key, which therefore have no scope to check: the health and
     * status probes and the public signing keys. Kept here, next to the enforcement, so the set of
     * unauthenticated endpoints is stated in exactly one place on this path.
     */
    private static final Set<String> UNAUTHENTICATED_PATHS = Set.of("/api/status", "/api/health");

    private static final String SIGNING_KEY_PATH = "/api/signing-key";

    @Override
    public boolean preHandle(final HttpServletRequest request, final HttpServletResponse response,
                             final Object handler) throws Exception {

        final String path = request.getRequestURI();

        if (!path.startsWith("/api/") || isUnauthenticated(path)) {
            return true;
        }

        if (!(handler instanceof final HandlerMethod handlerMethod)) {
            return true;
        }

        final RequiresScope requiresScope = handlerMethod.getMethodAnnotation(RequiresScope.class);

        if (requiresScope == null) {
            LOGGER.error("Refusing {} {}: the handler declares no required scope. Annotate it with @RequiresScope.",
                    request.getMethod(), path);
            return refuse(response, "This endpoint is not available.");
        }

        final ApiKeyEntity apiKeyEntity =
                (ApiKeyEntity) request.getAttribute(AbstractApiController.API_KEY_ENTITY_ATTRIBUTE);

        if (apiKeyEntity == null) {
            // The authentication filter should already have refused this. Treat a missing key as a
            // refusal rather than assuming the request is authorized.
            LOGGER.warn("Refusing {} {}: no API key on the request.", request.getMethod(), path);
            return refuse(response, "Unauthorized.");
        }

        final ApiKeyScope scope = requiresScope.value();

        if (!apiKeyEntity.hasScope(scope)) {
            LOGGER.warn("Refusing {} {}: the API key does not carry the '{}' scope.",
                    request.getMethod(), path, scope.getScope());
            return refuse(response, "This API key does not have the '" + scope.getScope() + "' scope.");
        }

        return true;

    }

    private static boolean isUnauthenticated(final String path) {
        return UNAUTHENTICATED_PATHS.contains(path)
                || path.equals(SIGNING_KEY_PATH) || path.startsWith(SIGNING_KEY_PATH + "/");
    }

    private static boolean refuse(final HttpServletResponse response, final String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\": \"Forbidden\", \"message\": \"" + message + "\"}");
        return false;
    }

}

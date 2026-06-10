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
package ai.philterd.philter.api.controllers;

import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.config.AdminAccessConfig;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.services.cache.ApiKeyCache;
import ai.philterd.philter.services.encryption.EncryptionService;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.types.ObjectId;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public abstract class AbstractApiController {

    private static final Logger LOGGER = LogManager.getLogger(AbstractApiController.class);

    /**
     * Request attribute under which {@code ApiAuthenticationFilter} stashes the {@link ApiKeyEntity} it
     * resolved while authenticating the request, so controllers can reuse it instead of looking the API
     * key up a second time.
     */
    public static final String API_KEY_ENTITY_ATTRIBUTE = "apiKeyEntity";

    protected final ApiKeyDataService apiKeyService;
    protected final ApiKeyCache apiKeyCache;

    protected AbstractApiController(final ApiKeyDataService apiKeyService, final ApiKeyCache apiKeyCache) {
        this.apiKeyService = apiKeyService;
        this.apiKeyCache = apiKeyCache;
    }

    public ApiKeyEntity getApiKeyEntity(final String authorizationHeader) {

        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return null;
        }

        // Derive the credential and its hash from THIS request's header. The hash is the ground truth
        // the rest of this method is checked against — identity always flows from the request's own
        // credential, never from ambient state.
        final String apiKey = authorizationHeader.substring(7).trim();
        final String apiKeyHash = EncryptionService.hashSha256(apiKey);

        // Fast path: reuse the entity the authentication filter resolved for this request, but only if
        // it provably matches the credential presented on this request. Verifying the stashed entity's
        // hash against the request's own key hash makes identity confusion impossible to act on: if the
        // request-scoped attribute were ever the wrong one (a stale thread-bound value, async dispatch,
        // etc.), the hashes would not match and the stash is not trusted — it falls through to a fresh,
        // request-scoped resolution below (fail closed). A match guarantees the stash belongs to this
        // exact credential.
        final ApiKeyEntity fromRequest = getAuthenticatedApiKeyFromRequest();
        if (fromRequest != null && apiKeyHash.equals(fromRequest.getApiKeyHash())) {
            return fromRequest;
        }

        // Resolve from the cache (keyed by the hash so a deleted key can be evicted without the
        // plaintext), then the database. This path also serves callers that did not pass through the
        // filter, such as unit tests.
        if (apiKeyCache.containsApiKey(apiKeyHash)) {
            return apiKeyCache.get(apiKeyHash);
        }

        final ApiKeyEntity apiKeyEntity = apiKeyService.findOneByApiKey(apiKey);
        if (apiKeyEntity != null) {
            apiKeyCache.insert(apiKeyHash, apiKeyEntity);
            return apiKeyEntity;
        }

        return null;

    }

    /**
     * Returns the {@link ApiKeyEntity} that {@code ApiAuthenticationFilter} stored on the current
     * request while authenticating it, or {@code null} if there is no bound request or no such
     * attribute (for example, in a unit test that exercises the controller without the filter). The
     * caller must verify the returned entity matches the request's credential before trusting it.
     */
    private static ApiKeyEntity getAuthenticatedApiKeyFromRequest() {
        final RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            final Object attribute = servletAttributes.getRequest().getAttribute(API_KEY_ENTITY_ATTRIBUTE);
            if (attribute instanceof ApiKeyEntity apiKeyEntity) {
                return apiKeyEntity;
            }
        }
        return null;
    }

    /** Whether the given user is an admin. Admins may act on any user's resources. */
    protected boolean isAdmin(final UserService userService, final ObjectId userId) {
        final UserEntity user = userService.findOneById(userId);
        return user != null && "admin".equalsIgnoreCase(user.getRole());
    }

    /**
     * Resolves the user whose resources a request targets. A bare request operates on the caller's own
     * resources. To act on another user's resources the caller supplies that user's username via
     * {@code ownerUsername}; this is allowed only for an admin. Returns {@code null} when the caller is not
     * authorized (a non-admin naming another user) or the named owner does not exist — callers map both
     * to a 404 so endpoints never reveal the existence of a user the caller may not access.
     *
     * @param userService   Used to resolve the owner and check admin status.
     * @param callerUserId  The id of the authenticated caller.
     * @param ownerUsername The username of the owner to target, or null/blank for the caller's own resources.
     */
    protected ObjectId resolveTargetUserId(final UserService userService, final ObjectId callerUserId, final String ownerUsername) {
        if (ownerUsername == null || ownerUsername.isBlank()) {
            return callerUserId;
        }
        final UserEntity owner = userService.findByUsername(ownerUsername);
        if (owner == null) {
            return null;
        }
        // Reaching another user's resources requires admin rights AND cross-user access being enabled
        // (the ADMIN_CROSS_USER_ACCESS_ENABLED kill switch). Naming your own username always works.
        if (!owner.getId().equals(callerUserId)
                && !(isCrossUserAccessEnabled() && isAdmin(userService, callerUserId))) {
            return null;
        }
        return owner.getId();
    }

    /**
     * Whether admins may act on other users' resources via the {@code owner} parameter. Reads the
     * {@code ADMIN_CROSS_USER_ACCESS_ENABLED} kill switch; overridable for tests.
     */
    protected boolean isCrossUserAccessEnabled() {
        return AdminAccessConfig.isCrossUserAccessEnabled();
    }

    /**
     * Records an audit event when an admin acts on <em>another</em> user's resource (resolved via the
     * {@code owner} parameter), attributing the action to the acting admin (the subject) and naming the
     * affected user (the associated object). A no-op when the target is the caller's own resource, so it
     * is safe to call unconditionally after {@link #resolveTargetUserId}.
     *
     * @param action A short description of the operation, e.g. {@code "delete policy 'x'"}.
     */
    protected void auditAdminCrossUserAccess(final AuditEventPublisher auditEventPublisher, final String requestId,
                                             final ObjectId callerUserId, final ObjectId targetUserId, final String action) {
        if (targetUserId != null && !targetUserId.equals(callerUserId)) {
            auditEventPublisher.auditEvent(requestId, AuditLogEvent.ADMIN_CROSS_USER_ACCESS, callerUserId, targetUserId,
                    null, "action: " + action);
        }
    }

    public static String getClientIpAddress(final HttpServletRequest httpServletRequest) {

        // With App Runner you can access the original source IPv4 and IPv6 addresses of the traffic entering your application.
        // The original source IP addresses are preserved by assigning the X-Forwarded-For request header to them.
        // This enables your applications to fetch the original source IP addresses when needed.

        String ipAddress = httpServletRequest.getHeader("X-Forwarded-For");

        if (ipAddress == null || ipAddress.isEmpty() || "unknown".equalsIgnoreCase(ipAddress)) {

            // Fallback to getRemoteAddr() if X-Forwarded-For is not present or invalid
            ipAddress = httpServletRequest.getRemoteAddr();

        } else {

            // X-Forwarded-For can contain multiple IPs if passing through multiple proxies
            // The first IP in the list is typically the client's original IP
            int commaIndex = ipAddress.indexOf(',');
            if (commaIndex > -1) {
                ipAddress = ipAddress.substring(0, commaIndex).trim();
            }

        }

        return ipAddress;

    }

}
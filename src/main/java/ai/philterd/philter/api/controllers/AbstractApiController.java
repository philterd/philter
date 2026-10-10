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

import ai.philterd.philter.api.exceptions.BadRequestException;
import ai.philterd.philter.api.exceptions.UnauthorizedException;
import ai.philterd.philter.model.ErrorReasons;
import ai.philterd.philter.api.responses.GenericResponse;
import ai.philterd.philter.api.responses.OwnedNameResponse;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.config.AdminAccessConfig;
import ai.philterd.philter.config.LedgerDeletionConfig;
import ai.philterd.philter.config.TrustedProxiesConfig;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.services.cache.ApiKeyCache;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.utils.IpAddresses;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.types.ObjectId;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public abstract class AbstractApiController {

    private static final Logger LOGGER = LogManager.getLogger(AbstractApiController.class);

    /**
     * Request attribute under which {@code ApiAuthenticationFilter} stashes the {@link ApiKeyEntity} it
     * resolved while authenticating the request, so controllers can reuse it instead of looking the API
     * key up a second time.
     */
    public static final String API_KEY_ENTITY_ATTRIBUTE = "apiKeyEntity";

    /** Request attribute holding the request's id, which the authentication filter sets for every /api request. */
    public static final String REQUEST_ID_ATTRIBUTE = "requestId";

    /** Response header returning the request's id, so a client can quote it and find it in the audit log. */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    /**
     * The id of the request being served, as the authentication filter set it, or a new one when there is
     * none, such as in a unit test that calls a controller directly.
     */
    protected static String currentRequestId() {
        if (RequestContextHolder.getRequestAttributes() instanceof final ServletRequestAttributes attributes
                && attributes.getRequest().getAttribute(REQUEST_ID_ATTRIBUTE) instanceof final String requestId) {
            return requestId;
        }
        return ai.philterd.philter.services.RequestIdGenerator.generate();
    }

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
        // Deactivated users included: deactivation keeps a user's data, including evidence under legal hold,
        // so an administrator must still be able to read and manage it. A deactivated caller cannot get here.
        final UserEntity owner = userService.findAnyByUsername(ownerUsername);
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
     * Whether the caller may list every user's resources with {@code all_users}: an administrator, with
     * {@code ADMIN_CROSS_USER_ACCESS_ENABLED}, the same as reaching one other user with {@code owner}.
     * Callers answer a refusal with 404, as {@link #resolveTargetUserId} does.
     *
     * @throws BadRequestException if the request also names an {@code owner}.
     */
    protected boolean mayListAllUsers(final UserService userService, final ObjectId callerUserId, final String owner) {
        if (owner != null && !owner.isBlank()) {
            throw new BadRequestException("Pass owner or all_users, not both.");
        }
        return isCrossUserAccessEnabled() && isAdmin(userService, callerUserId);
    }

    /** The usernames of the given items' owners, looked up in one query. */
    protected <T> Map<ObjectId, String> ownerNames(final UserService userService, final List<T> items,
                                                   final Function<T, ObjectId> owner) {
        final Set<ObjectId> ids = new HashSet<>();
        for (final T item : items) {
            ids.add(owner.apply(item));
        }
        return userService.findUsernamesByIds(ids);
    }

    /** Each item's name and its owner's username, in the order given. */
    protected <T> List<OwnedNameResponse> ownedNames(final UserService userService, final List<T> items,
                                                     final Function<T, String> name, final Function<T, ObjectId> owner) {
        final Map<ObjectId, String> usernames = ownerNames(userService, items, owner);
        final List<OwnedNameResponse> owned = new ArrayList<>(items.size());
        for (final T item : items) {
            owned.add(new OwnedNameResponse(name.apply(item), usernames.get(owner.apply(item))));
        }
        return owned;
    }

    /** Records an administrator listing a resource across every user. */
    protected void auditAllUsersListing(final AuditEventPublisher auditEventPublisher, final String requestId,
                                        final ObjectId callerUserId, final String action) {
        auditEventPublisher.auditEvent(requestId, AuditLogEvent.ADMIN_CROSS_USER_ACCESS, callerUserId, null,
                null, "action: " + action + " across all users");
    }

    /**
     * Whether admins may act on other users' resources via the {@code owner} parameter. Reads the
     * {@code ADMIN_CROSS_USER_ACCESS_ENABLED} kill switch; overridable for tests.
     */
    protected boolean isCrossUserAccessEnabled() {
        return AdminAccessConfig.isCrossUserAccessEnabled();
    }

    /**
     * Whether redaction-ledger deletion is permitted at all in this deployment. Reads the
     * {@code LEDGER_DELETION_ENABLED} kill switch; overridable for tests.
     */
    protected boolean isLedgerDeletionEnabled() {
        return LedgerDeletionConfig.isLedgerDeletionEnabled();
    }

    /** Page size used when a caller supplies none, or a value that is not positive. */
    protected static final int DEFAULT_LIMIT = 25;

    /** Largest page any endpoint returns, matching the cap the data services already apply. */
    protected static final int MAX_LIMIT = 100;

    /**
     * Clamps a caller-supplied offset to zero or greater, so the API defines its own paging rather
     * than passing the value through to MongoDB.
     */
    protected int normalizeOffset(final int offset) {
        return Math.max(0, offset);
    }

    /**
     * Clamps a caller-supplied limit to 1..{@link #MAX_LIMIT}, defaulting when it is not positive.
     * Without this a negative limit reaches MongoDB, where it means "return |n| and close the
     * cursor" and silently yields a short page.
     */
    protected int normalizeLimit(final int limit) {
        return limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
    }

    /** The fields a listing can be sorted by, API name then stored field, in the order they are documented. */
    protected static Map<String, String> sortFields(final String... apiAndStored) {
        final Map<String, String> fields = new java.util.LinkedHashMap<>();
        for (int i = 0; i < apiAndStored.length; i += 2) {
            fields.put(apiAndStored[i], apiAndStored[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(fields);
    }

    /**
     * The order a listing request asks for. {@code sort} names one of the listing's {@code fields} (API
     * name to stored field) and {@code order} is {@code asc} or {@code desc}; either may be left out for
     * the listing's default. An unknown field or order is a 400 naming what is accepted.
     */
    protected static ai.philterd.philter.data.services.Listings.Sort listingSort(
            final String sort, final String order, final Map<String, String> fields,
            final String defaultSort, final boolean defaultDescending) {

        final String apiField = sort == null || sort.isBlank() ? defaultSort : sort.trim();
        final String storedField = fields.get(apiField);
        if (storedField == null) {
            throw new BadRequestException("sort must be one of: " + String.join(", ", fields.keySet()) + ".", "sort");
        }

        final boolean descending;
        if (order == null || order.isBlank()) {
            descending = apiField.equals(defaultSort) ? defaultDescending : false;
        } else if ("asc".equalsIgnoreCase(order.trim())) {
            descending = false;
        } else if ("desc".equalsIgnoreCase(order.trim())) {
            descending = true;
        } else {
            throw new BadRequestException("order must be asc or desc.", "order");
        }

        return new ai.philterd.philter.data.services.Listings.Sort(storedField, descending);
    }

    /** The calling key, or a 401 when the request carries none that is recognized. */
    protected ApiKeyEntity requireApiKey(final String authorizationHeader) {
        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);
        if (apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }
        return apiKeyEntity;
    }

    /** The 403 to send when the calling key's user is not an administrator, or {@code null} when it is. */
    protected ResponseEntity<Object> refuseNonAdmin(final UserService userService, final ApiKeyEntity apiKeyEntity,
                                                    final String operation) {
        final ResponseEntity<GenericResponse> refusal =
                authorizeAdminOnly(userService, apiKeyEntity.getUserId(), operation);
        return refusal == null ? null : ResponseEntity.status(refusal.getStatusCode()).body(refusal.getBody());
    }

    /** Authorizes an admin-only operation that has no kill switch of its own. */
    protected ResponseEntity<GenericResponse> authorizeAdminOnly(final UserService userService,
                                                                 final ObjectId callerUserId,
                                                                 final String operation) {
        return authorizeAdminOnly(userService, callerUserId, true, operation, null);
    }

    /**
     * Authorizes an admin-only operation, returning the refusal to send or {@code null} when allowed.
     *
     * <p>Returns 403, not the 404 {@link #resolveTargetUserId} uses: the caller is acting on their own
     * data, so there is nothing to conceal. The admin check runs first so a non-admin never learns the
     * deployment's configuration from the error message.
     */
    protected ResponseEntity<GenericResponse> authorizeAdminOnly(final UserService userService,
                                                                 final ObjectId callerUserId,
                                                                 final boolean featureEnabled,
                                                                 final String operation,
                                                                 final String disabledMessage) {
        if (!isAdmin(userService, callerUserId)) {
            return new ResponseEntity<>(
                    new GenericResponse(operation + " requires an administrator.", ErrorReasons.ADMIN_REQUIRED), HttpStatus.FORBIDDEN);
        }
        if (!featureEnabled) {
            return new ResponseEntity<>(new GenericResponse(disabledMessage, ErrorReasons.FEATURE_DISABLED), HttpStatus.FORBIDDEN);
        }
        return null;
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

    /**
     * The client address to record for a request. A request from a trusted proxy (see
     * {@link TrustedProxiesConfig}) is attributed to the address its X-Forwarded-For header names: the
     * entries are read from the right, skipping trusted proxies, because each proxy appends the address it
     * received from and only the leftmost entries can be written by the client. Any other request, or an
     * entry that is not an IP address, is attributed to the connection's own address. Ports are removed;
     * nothing is looked up in DNS.
     */
    public static String getClientIpAddress(final HttpServletRequest httpServletRequest) {

        final String remote = httpServletRequest.getRemoteAddr();

        final InetAddress remoteAddress = IpAddresses.parseLiteral(remote);
        if (remoteAddress == null || !TrustedProxiesConfig.isTrusted(remoteAddress)) {
            return remote;
        }

        final List<String> entries = new ArrayList<>();
        for (final String header : Collections.list(httpServletRequest.getHeaders("X-Forwarded-For"))) {
            for (final String entry : header.split(",")) {
                entries.add(entry.trim());
            }
        }

        String client = remote;
        for (int i = entries.size() - 1; i >= 0; i--) {
            final String literal = forwardedLiteral(entries.get(i));
            final InetAddress address = IpAddresses.parseLiteral(literal);
            if (address == null) {
                return remote;
            }
            client = literal;
            if (!TrustedProxiesConfig.isTrusted(address)) {
                return client;
            }
        }

        return client;

    }

    /** An X-Forwarded-For entry with any port removed: {@code 203.0.113.7:51234} or {@code [2001:db8::1]:443}. */
    private static String forwardedLiteral(final String entry) {
        if (entry.startsWith("[")) {
            final int end = entry.indexOf(']');
            if (end < 0 || !entry.substring(end + 1).matches("(:\\d{1,5})?")) {
                return null;
            }
            return entry.substring(1, end);
        }
        final int colon = entry.indexOf(':');
        if (colon > 0 && colon == entry.lastIndexOf(':')) {
            return entry.substring(colon + 1).matches("\\d{1,5}") ? entry.substring(0, colon) : null;
        }
        return entry;
    }

}
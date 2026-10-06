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
import ai.philterd.philter.api.requests.CreateApiKeyRequest;
import ai.philterd.philter.api.requests.SetApiKeyScopesRequest;
import ai.philterd.philter.api.responses.ApiKeyResponse;
import ai.philterd.philter.api.responses.ApiKeyScopesResponse;
import ai.philterd.philter.api.responses.CreatedApiKeyResponse;
import ai.philterd.philter.api.responses.GenericResponse;
import ai.philterd.philter.api.responses.GetApiKeysResponse;
import ai.philterd.philter.api.responses.RevokedSessionKeysResponse;
import ai.philterd.philter.api.security.AnyApiKey;
import ai.philterd.philter.api.security.RequiresScope;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.ApiKeyScope;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.model.Source;
import ai.philterd.philter.services.cache.ApiKeyCache;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.bson.types.ObjectId;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Lists, creates, re-scopes, and revokes API keys. A caller manages its own user's keys; an
 * administrator can also manage any other user's.
 *
 * <p>A key is bounded by the key calling: it cannot grant a scope it does not hold, and it cannot
 * re-scope or revoke a key holding a scope it does not hold. The key making a request cannot revoke
 * itself.
 */
@Tag(name = "API Keys",
        description = "List, create, re-scope, and revoke API keys. A caller manages its own keys; an "
                + "administrator can also manage other users' keys.")
@Controller
public class ApiKeysApiController extends AbstractApiController {

    private final UserService userService;

    public ApiKeysApiController(final ApiKeyDataService apiKeyDataService,
                                final ApiKeyCache apiKeyCache,
                                final UserService userService) {
        super(apiKeyDataService, apiKeyCache);
        this.userService = userService;
    }

    @Operation(
            summary = "List the calling key's user's API keys.",
            description = "Lists the active keys belonging to the calling key's user, oldest first, with the "
                    + "total. Returns each key's ID, prefix, scopes, creation time, and whether it is the "
                    + "bootstrap key, never the key itself. session=true lists only session keys and session=false "
                    + "only long-lived keys; total counts only the keys listed.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "A page of keys.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GetApiKeysResponse.class))),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "403", description = "The key does not hold api-keys:read.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class)))
    })
    @RequiresScope(ApiKeyScope.API_KEYS_READ)
    @RequestMapping(value = "/api/api-keys", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<Object> getApiKeys(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestParam(value = "offset", defaultValue = "0") int offset,
            final @RequestParam(value = "limit", defaultValue = "25") int limit,
            final @Parameter(description = "true lists only session keys, false only long-lived keys; left out, both. "
                    + "total counts only the keys listed.")
            @RequestParam(value = "session", required = false) Boolean session) {

        final ApiKeyEntity caller = requireApiKey(authorizationHeader);

        return ResponseEntity.ok(page(caller.getUserId(), offset, limit, session));

    }

    @Operation(
            summary = "List a user's API keys.",
            description = "Lists the named user's active keys, oldest first, with the total. session=true lists only "
                    + "session keys and session=false only long-lived keys. Requires an administrator as well as the scope.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "A page of keys.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GetApiKeysResponse.class))),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "403", description = "The key does not hold api-keys:read, or the caller is not an administrator.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class))),
            @ApiResponse(responseCode = "404", description = "There is no user with that username.", content = @Content)
    })
    @RequiresScope(ApiKeyScope.API_KEYS_READ)
    @RequestMapping(value = "/api/users/{username}/api-keys", method = RequestMethod.GET,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<Object> getUserApiKeys(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @PathVariable("username") String username,
            final @RequestParam(value = "offset", defaultValue = "0") int offset,
            final @RequestParam(value = "limit", defaultValue = "25") int limit,
            final @Parameter(description = "true lists only session keys, false only long-lived keys; left out, both. "
                    + "total counts only the keys listed.")
            @RequestParam(value = "session", required = false) Boolean session) {

        final ApiKeyEntity caller = requireApiKey(authorizationHeader);

        final ResponseEntity<Object> refusal = refuseNonAdmin(userService, caller, "Listing another user's API keys");
        if (refusal != null) {
            return refusal;
        }

        final UserEntity user = userService.findAnyByUsername(username);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        return ResponseEntity.ok(page(user.getId(), offset, limit, session));

    }

    @Operation(
            summary = "Create an API key for the calling key's user.",
            description = "Mints a key for the calling key's user, so a key can be rotated without an "
                    + "administrator. The scopes must be a subset of those the calling key holds. The key is "
                    + "returned once: only its hash is stored. Recorded as an api_key_created audit event naming "
                    + "the calling user and API key.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "The key was created. The value is returned here and nowhere else.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = CreatedApiKeyResponse.class))),
            @ApiResponse(responseCode = "400", description = "No scopes were given, or one of them is not a scope."),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "403", description = "The key does not hold api-keys:write, is a session key, or a requested scope is not held by the calling key.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class)))
    })
    @RequiresScope(ApiKeyScope.API_KEYS_WRITE)
    @RequestMapping(value = "/api/api-keys", method = RequestMethod.POST,
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<Object> createOwnApiKey(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestAttribute("requestId") String requestId,
            final @RequestBody CreateApiKeyRequest request) {

        final ApiKeyEntity caller = requireApiKey(authorizationHeader);

        final ResponseEntity<Object> sessionRefusal = refuseSessionKey(caller);
        if (sessionRefusal != null) {
            return sessionRefusal;
        }

        final Set<String> scopes = requestedScopes(request.getScopes());
        final ResponseEntity<Object> refusal = refuseScopesNotHeld(caller, scopes);
        if (refusal != null) {
            return refusal;
        }

        final UserEntity user = userService.findOneById(caller.getUserId());

        return mint(requestId, caller, user, scopes);

    }

    @Operation(
            summary = "Create an API key for a user.",
            description = "Mints a key for the named user with the scopes given in the body, and returns its value "
                    + "once: only the hash is stored, so the response is the one chance to capture it. The scopes "
                    + "must be a subset of those the calling key holds, so this cannot be used to widen access "
                    + "beyond the credential making the request. Requires an administrator as well as the scope. "
                    + "Recorded as an api_key_created audit event naming the calling user and API key.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "The key was created. The value is returned here and nowhere else.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = CreatedApiKeyResponse.class))),
            @ApiResponse(responseCode = "400", description = "No scopes were given, or one of them is not a scope."),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "403", description = "The key does not hold api-keys:write, is a session key, the caller is not an administrator, or a requested scope is not held by the calling key.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class))),
            @ApiResponse(responseCode = "404", description = "There is no active user with that username.",
                    content = @Content)
    })
    @RequiresScope(ApiKeyScope.API_KEYS_WRITE)
    @RequestMapping(value = "/api/users/{username}/api-keys", method = RequestMethod.POST,
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<Object> createApiKey(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestAttribute("requestId") String requestId,
            final @PathVariable("username") String username,
            final @RequestBody CreateApiKeyRequest request) {

        final ApiKeyEntity caller = requireApiKey(authorizationHeader);

        final ResponseEntity<Object> sessionRefusal = refuseSessionKey(caller);
        if (sessionRefusal != null) {
            return sessionRefusal;
        }

        final ResponseEntity<Object> adminRefusal = refuseNonAdmin(userService, caller, "Creating an API key");
        if (adminRefusal != null) {
            return adminRefusal;
        }

        // Checked before the user is resolved: a caller who could not grant these scopes anyway must
        // not learn from the status code whether a username exists.
        final Set<String> scopes = requestedScopes(request.getScopes());
        final ResponseEntity<Object> refusal = refuseScopesNotHeld(caller, scopes);
        if (refusal != null) {
            return refusal;
        }

        final UserEntity user = userService.findByUsername(username);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        return mint(requestId, caller, user, scopes);

    }

    @Operation(
            summary = "Replace an API key's scopes.",
            description = "Replaces the scopes on a key. The key value does not change. The new scopes must be a "
                    + "subset of those the calling key holds, and the calling key cannot change a key holding a "
                    + "scope it does not hold. The key making the request cannot change its own scopes. A caller can "
                    + "change its own user's keys; an administrator can change any user's. Recorded as an api_key_scopes_changed audit event with the scopes "
                    + "before and after and the calling user and API key.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The scopes were replaced.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiKeyResponse.class))),
            @ApiResponse(responseCode = "400", description = "No scopes were given, or one of them is not a scope."),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "403", description = "The key does not hold api-keys:write, a requested scope is not held by the calling key, the key being changed holds a scope the calling key does not, or the calling key is a session key and the change adds a scope.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class))),
            @ApiResponse(responseCode = "404", description = "There is no active key with that ID that the caller may manage.",
                    content = @Content),
            @ApiResponse(responseCode = "409", description = "The key is the one making the request.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class)))
    })
    @RequiresScope(ApiKeyScope.API_KEYS_WRITE)
    @RequestMapping(value = "/api/api-keys/{keyId}/scopes", method = RequestMethod.PUT,
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<Object> setApiKeyScopes(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestAttribute("requestId") String requestId,
            final @PathVariable("keyId") String keyId,
            final @RequestBody SetApiKeyScopesRequest request) {

        final ApiKeyEntity caller = requireApiKey(authorizationHeader);

        final Set<String> scopes = requestedScopes(request.getScopes());

        final ApiKeyEntity target = findManageableKey(keyId, caller);
        if (target == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        // Refused as revoking it is: a client narrowing the key it is using would break its own access.
        if (target.getId().equals(caller.getId())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new GenericResponse(
                    "This is the key making the request. Change its scopes with another key."));
        }

        ResponseEntity<Object> refusal = refuseScopesNotHeld(caller, scopes);
        if (refusal == null) {
            refusal = refuseWiderTarget(caller, target, "change");
        }
        if (refusal == null) {
            refusal = refuseWideningFromSession(caller, target, scopes);
        }
        if (refusal != null) {
            return refusal;
        }

        final ServiceResponse response = apiKeyService.updateScopes(requestId, target.getUserId(), target, scopes,
                Source.API.getSource(), actingPrincipal(caller));
        if (!response.isSuccessful()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        return ResponseEntity.ok(new ApiKeyResponse(target));

    }

    @Operation(
            summary = "Revoke an API key.",
            description = "Revokes a key: it stops authenticating at once and cannot be restored. The key making "
                    + "the request cannot revoke itself; revoke it with another key. The calling key cannot "
                    + "revoke a key holding a scope it does not hold. A caller can revoke its own user's keys; an "
                    + "administrator can revoke any user's. Recorded as an api_key_deleted audit event naming the "
                    + "calling user and API key.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "The key was revoked.", content = @Content),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "403", description = "The key does not hold api-keys:write, or the key being revoked holds a scope the calling key does not.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class))),
            @ApiResponse(responseCode = "404", description = "There is no active key with that ID that the caller may manage.",
                    content = @Content),
            @ApiResponse(responseCode = "409", description = "The key is the one making the request.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class)))
    })
    @RequiresScope(ApiKeyScope.API_KEYS_WRITE)
    @RequestMapping(value = "/api/api-keys/{keyId}", method = RequestMethod.DELETE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<Object> revokeApiKey(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestAttribute("requestId") String requestId,
            final @PathVariable("keyId") String keyId) {

        final ApiKeyEntity caller = requireApiKey(authorizationHeader);

        final ApiKeyEntity target = findManageableKey(keyId, caller);
        if (target == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        // Refused rather than allowed: a caller that revoked its own key would learn so only from the
        // next request failing.
        if (target.getId().equals(caller.getId())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new GenericResponse(
                    "This is the key making the request. Revoke it with another key."));
        }

        final ResponseEntity<Object> refusal = refuseWiderTarget(caller, target, "revoke");
        if (refusal != null) {
            return refusal;
        }

        final ServiceResponse response = apiKeyService.deleteByApiKey(requestId, target.getUserId(), target,
                Source.API.getSource(), actingPrincipal(caller));
        if (!response.isSuccessful()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        return ResponseEntity.noContent().build();

    }

    @Operation(
            summary = "List the API key scopes.",
            description = "Returns every scope an API key can carry, with what it allows, in the order Philter declares "
                    + "them, so a client offering scopes to choose from need not hard-code them. Any key may call it, "
                    + "whatever its scopes, since it describes the API rather than any account.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Every scope.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiKeyScopesResponse.class))),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized.")
    })
    @AnyApiKey
    @RequestMapping(value = "/api/api-keys/scopes", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<ApiKeyScopesResponse> getScopes(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader) {

        requireApiKey(authorizationHeader);
        return ResponseEntity.ok(new ApiKeyScopesResponse());

    }

    @Operation(
            summary = "Sign out: revoke the calling session key.",
            description = "Revokes the session key making the request, so a person can sign out without an "
                    + "administrator. Any key may call it, whatever its scopes, because it can only end the "
                    + "caller's own access. A long-lived key cannot revoke itself; revoke it with another key. "
                    + "Recorded as an api_key_deleted audit event with the reason signed out.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "The session key was revoked.", content = @Content),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "409", description = "The calling key is a long-lived key, not a session key.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class)))
    })
    @AnyApiKey
    @RequestMapping(value = "/api/api-keys/current", method = RequestMethod.DELETE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<Object> signOut(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestAttribute("requestId") String requestId) {

        final ApiKeyEntity caller = requireApiKey(authorizationHeader);

        if (!caller.isSession()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new GenericResponse(
                    "Only a session key can revoke itself. Revoke a long-lived key with another key."));
        }

        apiKeyService.deleteByApiKey(requestId, caller.getUserId(), caller, Source.API.getSource(), "reason: signed out");

        return ResponseEntity.noContent().build();

    }

    @Operation(
            summary = "Revoke all of a user's session keys.",
            description = "Revokes every session key the user holds, signing the person out everywhere. Long-lived "
                    + "keys are unaffected. Requires an administrator as well as the scope. Each key is recorded as "
                    + "an api_key_deleted audit event naming the calling administrator and API key.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "How many session keys were revoked.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = RevokedSessionKeysResponse.class))),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "403", description = "The key does not hold api-keys:write, or the caller is not an administrator.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class))),
            @ApiResponse(responseCode = "404", description = "There is no user with that username.", content = @Content)
    })
    @RequiresScope(ApiKeyScope.API_KEYS_WRITE)
    @RequestMapping(value = "/api/users/{username}/session-keys", method = RequestMethod.DELETE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<Object> revokeUserSessionKeys(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestAttribute("requestId") String requestId,
            final @PathVariable("username") String username) {

        final ApiKeyEntity caller = requireApiKey(authorizationHeader);

        final ResponseEntity<Object> refusal = refuseNonAdmin(userService, caller, "Revoking another user's session keys");
        if (refusal != null) {
            return refusal;
        }

        final UserEntity user = userService.findAnyByUsername(username);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        final long revoked = apiKeyService.revokeSessionKeys(requestId, user.getId(), Source.API.getSource(),
                "reason: revoked " + actingPrincipal(caller));

        return ResponseEntity.ok(new RevokedSessionKeysResponse(revoked));

    }

    private GetApiKeysResponse page(final ObjectId userId, final int offset, final int limit, final Boolean session) {
        final List<ApiKeyResponse> keys = new ArrayList<>();
        for (final ApiKeyEntity key : apiKeyService.findAllBySession(userId, normalizeOffset(offset), normalizeLimit(limit), session)) {
            keys.add(new ApiKeyResponse(key));
        }
        return new GetApiKeysResponse(keys, apiKeyService.countBySession(userId, session));
    }

    private ResponseEntity<Object> mint(final String requestId, final ApiKeyEntity caller, final UserEntity user,
                                        final Set<String> scopes) {

        // The principal recorded is the new key and the object is the user it belongs to; who asked for
        // it goes in the details.
        final ServiceResponse response = apiKeyService.createApiKey(requestId, user.getId(),
                Source.API.getSource(), scopes,
                "created " + actingPrincipal(caller) + ", scopes: [" + String.join(", ", scopes) + "]");

        final String apiKey = response.getMessage();
        final ApiKeyEntity created = apiKeyService.findOneByApiKey(apiKey);

        return ResponseEntity.status(HttpStatus.CREATED).body(new CreatedApiKeyResponse(
                created.getId().toHexString(), user.getUsername(), apiKey, new ArrayList<>(scopes)));

    }

    /**
     * The active key with this ID if the caller may manage it: one of its own user's keys, or any key
     * for an administrator. Anything else is {@code null}, so a non-administrator cannot learn whether
     * another user's key exists.
     */
    private ApiKeyEntity findManageableKey(final String keyId, final ApiKeyEntity caller) {

        if (keyId == null || !ObjectId.isValid(keyId)) {
            return null;
        }

        final ApiKeyEntity stored = apiKeyService.findOneById(new ObjectId(keyId));
        if (stored == null) {
            return null;
        }

        if (stored.getUserId().equals(caller.getUserId()) || isAdmin(userService, caller.getUserId())) {
            return stored;
        }

        return null;

    }

    /**
     * A session key cannot create keys: a key made from a session would outlive it, and survive the
     * sign-out, expiry, or password reset meant to end that person's access.
     */
    private static ResponseEntity<Object> refuseSessionKey(final ApiKeyEntity caller) {
        if (!caller.isSession()) {
            return null;
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new GenericResponse(
                "A session key cannot create API keys. Use a long-lived key."));
    }

    /**
     * A session key may narrow a key's scopes but not widen them. It holds every scope, so otherwise a
     * session together with a leaked narrow key could turn that key into a full one, which would outlive
     * the session and a password reset as a newly created key would.
     */
    private static ResponseEntity<Object> refuseWideningFromSession(final ApiKeyEntity caller, final ApiKeyEntity target,
                                                                    final Set<String> scopes) {
        if (!caller.isSession()) {
            return null;
        }
        final List<String> added = new ArrayList<>();
        for (final String scope : scopes) {
            if (!target.getScopes().contains(scope)) {
                added.add(scope);
            }
        }
        if (added.isEmpty()) {
            return null;
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new GenericResponse(
                "A session key can narrow a key's scopes but not widen them. Adding " + String.join(", ", added)
                        + " needs a long-lived key."));
    }

    private static String actingPrincipal(final ApiKeyEntity caller) {
        return "by user: " + caller.getUserId() + ", api_key: " + caller.getId();
    }

    private static List<String> notHeld(final ApiKeyEntity caller, final Collection<String> scopes) {
        final List<String> missing = new ArrayList<>();
        for (final String scope : scopes) {
            if (!caller.hasScope(ApiKeyScope.fromScope(scope))) {
                missing.add(scope);
            }
        }
        return missing;
    }

    private static ResponseEntity<Object> refuseScopesNotHeld(final ApiKeyEntity caller, final Set<String> scopes) {
        final List<String> missing = notHeld(caller, scopes);
        if (missing.isEmpty()) {
            return null;
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new GenericResponse(
                "The calling API key does not hold: " + String.join(", ", missing)
                        + ". A key cannot grant a scope it does not carry."));
    }

    /** A key may not change or revoke one more powerful than itself. */
    private static ResponseEntity<Object> refuseWiderTarget(final ApiKeyEntity caller, final ApiKeyEntity target,
                                                            final String operation) {
        final List<String> missing = notHeld(caller, target.getScopes());
        if (missing.isEmpty()) {
            return null;
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new GenericResponse(
                "The key to " + operation + " holds scopes the calling API key does not: "
                        + String.join(", ", missing) + "."));
    }

    /**
     * The requested scopes, in the order given and without duplicates. An unrecognized name is refused
     * rather than dropped: a key silently narrower than the one that was asked for fails later, in the
     * integration it was minted for, rather than here.
     */
    private static Set<String> requestedScopes(final List<String> requested) {

        if (requested == null || requested.isEmpty()) {
            throw new BadRequestException("scopes is required and must name at least one scope. "
                    + "A key with no scopes can call nothing.");
        }

        final Set<String> scopes = new LinkedHashSet<>();

        for (final String scope : requested) {
            if (scope == null || ApiKeyScope.fromScope(scope.trim()) == null) {
                throw new BadRequestException("'" + scope + "' is not a scope.");
            }
            scopes.add(scope.trim());
        }

        return scopes;

    }

}

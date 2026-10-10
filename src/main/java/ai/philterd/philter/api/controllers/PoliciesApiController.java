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

import ai.philterd.phileas.policy.Policy;
import ai.philterd.philter.api.exceptions.BadRequestException;
import ai.philterd.philter.api.exceptions.UnauthorizedException;
import ai.philterd.philter.api.requests.PolicyDetailsRequest;
import ai.philterd.philter.api.responses.CompilePolicyResponse;
import ai.philterd.philter.api.responses.DeletedPolicySummary;
import ai.philterd.philter.api.responses.GenericResponse;
import ai.philterd.philter.api.responses.GetPoliciesResponse;
import ai.philterd.philter.api.responses.ManagedPolicySummary;
import ai.philterd.philter.api.responses.OwnedNameResponse;
import ai.philterd.philter.api.responses.PolicyConflictResponse;
import ai.philterd.philter.api.responses.PolicyDetailsResponse;
import ai.philterd.philter.api.security.RequiresScope;
import ai.philterd.philter.model.ApiKeyScope;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.entities.PolicyEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.Listings;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.model.Source;
import ai.philterd.philter.services.RequestIdGenerator;
import ai.philterd.philter.services.cache.ApiKeyCache;
import ai.philterd.philter.services.policies.PolicyTemplates;
import ai.philterd.philter.services.policies.PhiSqlCompileService;
import ai.philterd.philter.services.policies.PolicyValidation;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
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

import java.io.IOException;
import java.util.List;

@Tag(name = "Policies", description = "Operations for creating, retrieving, deleting, and compiling redaction policies.")
@Controller
public class PoliciesApiController extends AbstractApiController {

    private final PolicyDataService policyDataService;
    private final UserService userService;
    private final AuditEventPublisher auditEventPublisher;
    private final PhiSqlCompileService phiSqlCompileService;
    private final Gson gson;

    public PoliciesApiController(final PolicyDataService policyDataService, final UserService userService,
                                 final ApiKeyDataService apiKeyDataService,
                                 final AuditEventPublisher auditEventPublisher, final ApiKeyCache apiKeyCache,
                                 final PhiSqlCompileService phiSqlCompileService, final Gson gson) {
        super(apiKeyDataService, apiKeyCache);
        this.policyDataService = policyDataService;
        this.userService = userService;
        this.auditEventPublisher = auditEventPublisher;
        this.phiSqlCompileService = phiSqlCompileService;
        this.gson = gson;
    }

    @Operation(summary = "Get the names of existing policies.",
            description = "Returns the names of the caller's policies, paged. Admins may list another user's "
                    + "policies by passing that user's username as owner, or every user's with all_users=true, which "
                    + "returns each policy's name and owner and requires ADMIN_CROSS_USER_ACCESS_ENABLED. With "
                    + "managed=true, returns the built-in managed policies instead, each with its name and description. "
                    + "With deleted=true, returns the caller's (or owner's) deleted policies whose version history "
                    + "is kept, by name, each with its latest revision and when and by whom it was deleted; read a "
                    + "deleted policy's history with GET /api/policies/{policyName}/versions.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "A page of policies in policies, and the total. Each is a policy's name; with all_users, an object naming each "
                    + "policy and its owner; with managed, objects giving each managed policy's name and description; "
                    + "with deleted, objects describing each deleted policy.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GetPoliciesResponse.class))),
            @ApiResponse(responseCode = "400", description = "Both owner and all_users were given, managed was combined with either, or deleted was combined with all_users or managed."),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "404", description = "The owner does not exist, or the caller may not reach it. The API does not distinguish the two, so an owner value cannot be used to discover accounts.")
    })
    @RequiresScope(ApiKeyScope.POLICIES_READ)
    @RequestMapping(value = "/api/policies", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<Object> getPolicyNames(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestParam(value = "owner", required = false) String owner,
            final @RequestParam(value = "all_users", defaultValue = "false") boolean allUsers,
            final @RequestParam(value = "managed", defaultValue = "false") boolean managed,
            final @RequestParam(value = "deleted", defaultValue = "false") boolean deleted,
            final @RequestParam(value = "q", required = false) String q,
            final @RequestParam(value = "sort", required = false) String sort,
            final @RequestParam(value = "order", required = false) String order,
            final @RequestParam(value = "offset", defaultValue = "0") int offset,
            final @RequestParam(value = "limit", defaultValue = "25") int limit,
            final @RequestAttribute("requestId") String requestId
    ) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);

        if(apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final int pageOffset = normalizeOffset(offset);
        final int pageLimit = normalizeLimit(limit);

        if (deleted) {
            if (allUsers || managed) {
                throw new BadRequestException("deleted cannot be combined with all_users or managed.");
            }
            final boolean descending = listingSort(sort, order, NAME_SORT, "name", false).descending();
            final ObjectId userId = resolveTargetUserId(userService, apiKeyEntity.getUserId(), owner);
            if (userId == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
            auditAdminCrossUserAccess(auditEventPublisher, requestId, apiKeyEntity.getUserId(), userId,
                    "list deleted policies");
            final Listings.Page<PolicyDataService.DeletedPolicy> page =
                    policyDataService.findDeleted(userId, q, descending, pageOffset, pageLimit);
            final java.util.Map<ObjectId, String> deleters = userService.findUsernamesByIds(page.items().stream()
                    .map(PolicyDataService.DeletedPolicy::deletedBy).filter(java.util.Objects::nonNull).distinct().toList());
            return ResponseEntity.ok(new GetPoliciesResponse(page.items().stream()
                    .map(policy -> new DeletedPolicySummary(policy.name(), policy.latestRevision(), policy.deletedAt(),
                            policy.deletedBy() == null ? null : deleters.get(policy.deletedBy())))
                    .toList(), page.total()));
        }

        if (managed) {
            if (allUsers || (owner != null && !owner.isBlank())) {
                throw new BadRequestException("managed cannot be combined with owner or all_users.");
            }
            final Listings.Page<PolicyEntity> page = policyDataService.listManaged(q,
                    listingSort(sort, order, NAME_SORT, "name", false), pageOffset, pageLimit);
            return ResponseEntity.ok(new GetPoliciesResponse(page.items().stream()
                    .map(policy -> new ManagedPolicySummary(policy.getName(), policy.getDescription())).toList(), page.total()));
        }

        final Listings.Sort policySort = listingSort(sort, order, POLICY_SORT, "name", false);

        if (allUsers) {
            if (!mayListAllUsers(userService, apiKeyEntity.getUserId(), owner)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
            final Listings.Page<PolicyEntity> page = policyDataService.listAcrossUsers(q, policySort, pageOffset, pageLimit);
            auditAllUsersListing(auditEventPublisher, requestId, apiKeyEntity.getUserId(), "list policies");
            return ResponseEntity.ok(new GetPoliciesResponse(
                    ownedNames(userService, page.items(), PolicyEntity::getName, PolicyEntity::getUserId), page.total()));
        }

        final ObjectId userId = resolveTargetUserId(userService, apiKeyEntity.getUserId(), owner);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        final Listings.Page<PolicyEntity> page = policyDataService.listOwn(userId, q, policySort, pageOffset, pageLimit);

        return ResponseEntity.ok(new GetPoliciesResponse(page.items().stream().map(PolicyEntity::getName).toList(),
                page.total()));

    }

    /** The order a listing of policies can take. */
    private static final java.util.Map<String, String> POLICY_SORT = sortFields("name", "name", "created", "created_timestamp", "updated", "last_updated_timestamp");

    /** A listing that can only be ordered by name. */
    private static final java.util.Map<String, String> NAME_SORT = sortFields("name", "name");

    @Operation(summary = "Get a policy.",
            description = "Returns the full policy with the given name. A name starting with managed_ returns that "
                    + "built-in managed policy. Admins may retrieve another user's policy by passing that user's username "
                    + "as owner. The policy's description and notes are at /api/policies/{policyName}/details.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The policy JSON."),
            @ApiResponse(responseCode = "400", description = "The policy name is missing."),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "404", description = "A policy with the given name does not exist.")
    })
    @RequiresScope(ApiKeyScope.POLICIES_READ)
    @RequestMapping(value = "/api/policies/{policyName}", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<String> get(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            @PathVariable(name = "policyName") String policyName,
            final @RequestParam(value = "owner", required = false) String owner) throws IOException {

        if (StringUtils.isEmpty(policyName)) {
            throw new BadRequestException("The policy name is missing.");
        }

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);

        if(apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = resolveTargetUserId(userService, apiKeyEntity.getUserId(), owner);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        final PolicyEntity policyEntity = policyDataService.findOneOrManaged(policyName, userId);
        if (policyEntity == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.status(HttpStatus.OK)
                .body(policyEntity.getPolicy());

    }

    @Operation(summary = "Get a policy template.",
            description = "Returns a starting point for a new policy: native policy JSON that Philter accepts for the "
                    + "running policy schema version, to edit and save with POST /api/policies. The template named "
                    + "default is the policy every new user's default policy is created from. A template is not a "
                    + "policy and cannot be used to redact until it is saved as one.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The template's policy JSON."),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "404", description = "There is no template with the given name.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class)))
    })
    @RequiresScope(ApiKeyScope.POLICIES_READ)
    @RequestMapping(value = "/api/policies/templates/{templateName}", method = RequestMethod.GET,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<Object> getTemplate(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            @PathVariable(name = "templateName") String templateName) {

        requireApiKey(authorizationHeader);

        final String template = PolicyTemplates.find(templateName);
        if (template == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new GenericResponse(
                    "There is no template named " + templateName + ". Templates: "
                            + String.join(", ", PolicyTemplates.names()) + "."));
        }
        return ResponseEntity.ok(template);

    }

    @Operation(summary = "Create a policy.",
            description = "Creates a policy from the request body under the given name. A name the owner already uses is "
                    + "refused with 409; replace an existing policy with PUT /api/policies/{policyName}. The policy is "
                    + "validated before it is stored. Set its description and notes afterwards with "
                    + "PUT /api/policies/{policyName}/details. Admins may create a policy in another user's account by "
                    + "passing that user's username as owner.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "The policy was created and is now active. A policy_activated audit event is recorded."),
            @ApiResponse(responseCode = "400", description = "The policy name is missing or invalid, the policy is invalid, or the request has a description or notes parameter."),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "404", description = "The owner does not exist, or the caller may not reach it. The API does not distinguish the two, so an owner value cannot be used to discover accounts.",
                    content = @Content),
            @ApiResponse(responseCode = "409", description = "The policy was not created because the owner already has a "
                    + "policy with this name; reason is policy_exists. Nothing is changed.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PolicyConflictResponse.class)))
    })
    @RequiresScope(ApiKeyScope.POLICIES_WRITE)
    @RequestMapping(value = "/api/policies", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> create(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            @RequestParam("name") final String name,
            final @RequestParam(value = "owner", required = false) String owner,
            @RequestBody String policyJson,
            final HttpServletRequest httpServletRequest) {

        return write(authorizationHeader, name, owner, policyJson, httpServletRequest, false);

    }

    @Operation(summary = "Replace a policy.",
            description = "Replaces an existing policy with the request body, as a new revision. The policy is validated "
                    + "before it is stored. Its description and notes are kept; change them with "
                    + "PUT /api/policies/{policyName}/details. Admins may replace another user's policy by passing "
                    + "that user's username as owner.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The policy was replaced and is now active. A policy_activated audit event is recorded.",
                    content = @Content),
            @ApiResponse(responseCode = "400", description = "The policy is invalid, or the request has a description or notes parameter."),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "404", description = "There is no such policy, with a message. Also returned, with no "
                    + "body, when the owner does not exist or the caller may not reach it, so an owner value cannot be used "
                    + "to discover accounts.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class))),
            @ApiResponse(responseCode = "409", description = "The policy was not replaced, and reason says why: policy_managed "
                    + "(a managed policy, which cannot be replaced) or policy_changed (it changed concurrently; reload "
                    + "it and retry).",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PolicyConflictResponse.class)))
    })
    @RequiresScope(ApiKeyScope.POLICIES_WRITE)
    @RequestMapping(value = "/api/policies/{policyName}", method = RequestMethod.PUT, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> replace(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            @PathVariable("policyName") final String name,
            final @RequestParam(value = "owner", required = false) String owner,
            @RequestBody String policyJson,
            final HttpServletRequest httpServletRequest) {

        return write(authorizationHeader, name, owner, policyJson, httpServletRequest, true);

    }

    /** Creates or replaces a policy; neither falls back to the other, so a create never overwrites. */
    private ResponseEntity<Object> write(final String authorizationHeader, final String name, final String owner,
                                         final String policyJson, final HttpServletRequest httpServletRequest,
                                         final boolean replace) {

        if (StringUtils.isBlank(name)) {
            throw new BadRequestException("The policy name is missing.");
        }

        // Free text in the URL outgrows header limits in some languages and lands in access logs, so the
        // details travel in a body. Refused rather than ignored, so a client sending them is not misled.
        if (httpServletRequest.getParameter("description") != null || httpServletRequest.getParameter("notes") != null) {
            throw new BadRequestException("description and notes are not accepted here. Set them with "
                    + "PUT /api/policies/{policyName}/details, which takes them in a JSON body.");
        }

        // A managed policy has no owner, so the per-user lookup below would answer 404. Creating one is
        // refused by the name rules with 400.
        if (replace && PolicyDataService.isManagedName(name)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                    .body(new PolicyConflictResponse("Managed policies cannot be replaced.", PolicyDataService.REASON_POLICY_MANAGED));
        }

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);

        if(apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = resolveTargetUserId(userService, apiKeyEntity.getUserId(), owner);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        // Validate the policy before persisting it so an invalid policy is rejected at creation rather
        // than failing later at redaction time.
        final PolicyValidation validation = policyDataService.validatePolicy(policyJson);
        if (!validation.isValid()) {
            throw new BadRequestException(validation.getMessage());
        }

        final String requestId = RequestIdGenerator.generate();

        auditAdminCrossUserAccess(auditEventPublisher, requestId, apiKeyEntity.getUserId(), userId,
                (replace ? "replace" : "create") + " policy '" + name + "'");

        // Through create/update rather than save: those enforce the name rules, retain the version
        // snapshot, and evict the redaction cache.
        final ServiceResponse response;
        if (replace) {
            final PolicyEntity existing = policyDataService.findOne(name, userId);
            response = existing == null
                    ? new ServiceResponse("Policy does not exist.", false, 404)
                    : policyDataService.update(requestId, userId, existing.getId(), policyJson, null, null,
                            Source.API.getSource(), apiKeyEntity.getUserId());
        } else {
            response = policyDataService.create(requestId, userId, policyJson, null, null, name, Source.API.getSource(),
                    apiKeyEntity.getUserId());
        }

        if (!response.isSuccessful()) {
            if (response.getStatusCode() == HttpStatus.BAD_REQUEST.value()) {
                throw new BadRequestException(response.getMessage());
            }
            // The content type is set rather than negotiated, so the refusal is sent whatever Accept asked for.
            final ResponseEntity.BodyBuilder refusal = ResponseEntity.status(response.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON);
            if (response.getStatusCode() == HttpStatus.CONFLICT.value()) {
                return refusal.body(new PolicyConflictResponse(response.getMessage(), response.getDetails()));
            }
            return refusal.body(new GenericResponse(response.getMessage()));
        }

        // Policies saved via the API become active immediately. Record the activation so it is
        // attributable to the specific API key even when no approval step exists.
        auditEventPublisher.auditEvent(requestId, AuditLogEvent.POLICY_ACTIVATED,
                apiKeyEntity.getUserId(), null, null, "policy: " + name);

        return ResponseEntity.status(replace ? HttpStatus.OK : HttpStatus.CREATED).build();

    }

    @Operation(summary = "Delete a policy.",
            description = "Deletes the policy with the given name. Admins may delete another user's policy by passing "
                    + "that user's username as owner.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The policy was deleted.", content = @Content),
            @ApiResponse(responseCode = "400", description = "The policy name is missing."),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "404", description = "There is no such policy, with a message. Also returned, with no "
                    + "body, when the owner does not exist or the caller may not reach it, so an owner value cannot be used "
                    + "to discover accounts.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class))),
            @ApiResponse(responseCode = "409", description = "The policy was not deleted, and reason says why: policy_default "
                    + "(the default policy) or policy_managed (a managed policy).",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PolicyConflictResponse.class)))
    })
    @RequiresScope(ApiKeyScope.POLICIES_WRITE)
    @RequestMapping(value = "/api/policies/{policyName}", method = RequestMethod.DELETE)
    public ResponseEntity<Object> delete(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            @PathVariable(name = "policyName") String policyName,
            final @RequestParam(value = "owner", required = false) String owner,
            final HttpServletRequest request) throws IOException {

        if (StringUtils.isEmpty(policyName)) {
            throw new BadRequestException("The policy name is missing.");
        }

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);

        if(apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = resolveTargetUserId(userService, apiKeyEntity.getUserId(), owner);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        final String requestId = RequestIdGenerator.generate();

        auditAdminCrossUserAccess(auditEventPublisher, requestId, apiKeyEntity.getUserId(), userId,
                "delete policy '" + policyName + "'");

        final ServiceResponse response = policyDataService.deleteByName(requestId, policyName, userId, Source.API,
                apiKeyEntity.getUserId(), getClientIpAddress(request));

        if (!response.isSuccessful()) {
            // The content type is set rather than negotiated, so the refusal is sent whatever Accept asked for.
            final ResponseEntity.BodyBuilder refusal = ResponseEntity.status(response.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON);
            if (response.getStatusCode() == HttpStatus.CONFLICT.value()) {
                return refusal.body(new PolicyConflictResponse(response.getMessage(), response.getDetails()));
            }
            return refusal.body(new GenericResponse(response.getMessage()));
        }

        return ResponseEntity.ok().build();

    }

    /**
     * Compiles a policy authored in PhiSQL into the native Phileas policy format. The request body is
     * PhiSQL source; the response carries the policy name and description from the {@code POLICY}
     * declaration and the compiled policy JSON. The caller can then save the returned JSON via
     * {@code POST /api/policies}. A parse/compile error, or a compiled policy that fails validation,
     * returns a 400 with the error message.
     */
    @Operation(summary = "Compile a PhiSQL policy.",
            description = "Compiles a policy authored in PhiSQL into the native Phileas policy format. The request "
                    + "body is PhiSQL source; the response carries the policy name and description and the compiled "
                    + "policy JSON, which can then be saved via POST /api/policies.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The policy compiled successfully."),
            @ApiResponse(responseCode = "400", description = "The PhiSQL failed to parse/compile, or the compiled policy failed validation."),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized.")
    })
    // Compiling persists nothing, so it needs only read access: a pipeline that validates PhiSQL
    // should not need permission to save policies.
    @RequiresScope(ApiKeyScope.POLICIES_READ)
    @RequestMapping(value = "/api/policies/compile", method = RequestMethod.POST,
            consumes = MediaType.TEXT_PLAIN_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<String> compile(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestBody String phiSql) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);

        if(apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final PhiSqlCompileService.Result result = phiSqlCompileService.compile(phiSql);

        if(!result.isSuccess()) {
            return ResponseEntity.badRequest().body(gson.toJson(new GenericResponse(result.getError())));
        }

        // The compiler targets the native Phileas schema, but validate the output before returning it so
        // the caller never receives a policy that Philter's policy API would reject.
        final PolicyValidation validation = policyDataService.validatePolicy(result.getPolicyJson());
        if(!validation.isValid()) {
            return ResponseEntity.badRequest().body(gson.toJson(new GenericResponse(validation.getMessage())));
        }

        final CompilePolicyResponse response = new CompilePolicyResponse(
                result.getName(), result.getDescription(), gson.fromJson(result.getPolicyJson(), JsonElement.class));

        return ResponseEntity.ok(gson.toJson(response));

    }

    @Operation(summary = "Get a policy's details.",
            description = "Returns a policy's name, description, notes, current revision, whether it is a built-in "
                    + "managed policy, and when it was created and last updated: everything except the policy itself. "
                    + "A name starting with managed_ returns that managed policy's details. Admins may read another "
                    + "user's by passing that user's username as owner.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The policy's details.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PolicyDetailsResponse.class))),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "404", description = "There is no such policy, or the owner does not exist or the caller may not reach it.")
    })
    @RequiresScope(ApiKeyScope.POLICIES_READ)
    @RequestMapping(value = "/api/policies/{policyName}/details", method = RequestMethod.GET,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<Object> getDetails(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @PathVariable("policyName") String policyName,
            final @RequestParam(value = "owner", required = false) String owner) {

        final ApiKeyEntity apiKeyEntity = requireApiKey(authorizationHeader);

        final ObjectId userId = resolveTargetUserId(userService, apiKeyEntity.getUserId(), owner);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        final PolicyEntity policyEntity = policyDataService.findOneOrManaged(policyName, userId);
        if (policyEntity == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        return ResponseEntity.ok(new PolicyDetailsResponse(policyEntity));

    }

    @Operation(summary = "Set a policy's description and notes.",
            description = "Sets the description (up to " + PolicyDataService.POLICY_DESCRIPTION_MAX_LENGTH
                    + " characters) and notes (up to " + PolicyDataService.POLICY_NOTES_MAX_LENGTH + "). A field "
                    + "left out is left as it is; an empty value clears it. They are not part of the policy, so this "
                    + "does not create a new version. Managed policies cannot be changed. Admins may change another "
                    + "user's by passing that user's username as owner.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The policy's details after the change.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PolicyDetailsResponse.class))),
            @ApiResponse(responseCode = "400", description = "The description or notes are too long."),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "404", description = "There is no such policy, or the owner does not exist or the caller may not reach it."),
            @ApiResponse(responseCode = "409", description = "The policy is a managed policy; reason is policy_managed.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PolicyConflictResponse.class)))
    })
    @RequiresScope(ApiKeyScope.POLICIES_WRITE)
    @RequestMapping(value = "/api/policies/{policyName}/details", method = RequestMethod.PUT,
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<Object> setDetails(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @PathVariable("policyName") String policyName,
            final @RequestParam(value = "owner", required = false) String owner,
            final @RequestBody PolicyDetailsRequest request) {

        final ApiKeyEntity apiKeyEntity = requireApiKey(authorizationHeader);

        if (PolicyDataService.isManagedName(policyName)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new PolicyConflictResponse(
                    "Managed policies cannot be changed.", PolicyDataService.REASON_POLICY_MANAGED));
        }
        requireDetailLengths(request.getDescription(), request.getNotes());

        final ObjectId userId = resolveTargetUserId(userService, apiKeyEntity.getUserId(), owner);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        final String requestId = RequestIdGenerator.generate();
        auditAdminCrossUserAccess(auditEventPublisher, requestId, apiKeyEntity.getUserId(), userId,
                "set details of policy '" + policyName + "'");

        final ServiceResponse response = policyDataService.updateDetails(requestId, userId, policyName,
                request.getDescription(), request.getNotes(), Source.API.getSource());
        if (!response.isSuccessful()) {
            return ResponseEntity.status(response.getStatusCode()).body(new GenericResponse(response.getMessage()));
        }

        return ResponseEntity.ok(new PolicyDetailsResponse(policyDataService.findOne(policyName, userId)));

    }

    @Operation(summary = "Copy a policy.",
            description = "Creates a new policy named by name from one of the caller's policies or, for a name "
                    + "starting with managed_, from a built-in managed policy. The copy has the source's policy and "
                    + "description; a copy of a managed policy notes which one it came from, and a copy of the caller's "
                    + "own policy keeps its notes. The copy starts its own version history and is active at once. "
                    + "Admins may copy within another user's account by passing that user's username as owner.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "The copy's details.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PolicyDetailsResponse.class))),
            @ApiResponse(responseCode = "400", description = "The new name is missing or invalid."),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "404", description = "There is no such policy to copy, or the owner does not exist or the caller may not reach it."),
            @ApiResponse(responseCode = "409", description = "A policy with the new name already exists; reason is policy_exists.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = PolicyConflictResponse.class)))
    })
    @RequiresScope(ApiKeyScope.POLICIES_WRITE)
    @RequestMapping(value = "/api/policies/{policyName}/copy", method = RequestMethod.POST,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<Object> copy(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @PathVariable("policyName") String policyName,
            final @RequestParam("name") String name,
            final @RequestParam(value = "owner", required = false) String owner) {

        final ApiKeyEntity apiKeyEntity = requireApiKey(authorizationHeader);

        final ObjectId userId = resolveTargetUserId(userService, apiKeyEntity.getUserId(), owner);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        final PolicyEntity source = policyDataService.findOneOrManaged(policyName, userId);
        if (source == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        final String requestId = RequestIdGenerator.generate();
        auditAdminCrossUserAccess(auditEventPublisher, requestId, apiKeyEntity.getUserId(), userId,
                "copy policy '" + policyName + "' to '" + name + "'");

        // Through create, so the copy gets the name rules, a fresh version history,
        // and an unmanaged record of its own, rather than the source's revision and timestamps.
        final ServiceResponse response = policyDataService.create(requestId, userId, source.getPolicy(),
                source.getDescription(),
                source.isManaged() ? "Created from managed policy " + source.getName() : source.getNotes(),
                name, Source.API.getSource(), apiKeyEntity.getUserId());
        if (!response.isSuccessful()) {
            if (response.getStatusCode() == HttpStatus.CONFLICT.value()) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(new PolicyConflictResponse(response.getMessage(), response.getDetails()));
            }
            return ResponseEntity.status(response.getStatusCode()).body(new GenericResponse(response.getMessage()));
        }

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.POLICY_ACTIVATED,
                apiKeyEntity.getUserId(), null, null, "policy: " + name + ", copied from: " + policyName);

        return ResponseEntity.status(HttpStatus.CREATED).body(new PolicyDetailsResponse(policyDataService.findOne(name, userId)));

    }

    /** Refuses a description or notes longer than the service would keep, rather than truncating them. */
    private static void requireDetailLengths(final String description, final String notes) {
        if (description != null && description.length() > PolicyDataService.POLICY_DESCRIPTION_MAX_LENGTH) {
            throw new BadRequestException("The description cannot be longer than "
                    + PolicyDataService.POLICY_DESCRIPTION_MAX_LENGTH + " characters.");
        }
        if (notes != null && notes.length() > PolicyDataService.POLICY_NOTES_MAX_LENGTH) {
            throw new BadRequestException("The notes cannot be longer than "
                    + PolicyDataService.POLICY_NOTES_MAX_LENGTH + " characters.");
        }
    }

}

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
import ai.philterd.philter.api.responses.CompilePolicyResponse;
import ai.philterd.philter.api.responses.GenericResponse;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.entities.PolicyEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.Source;
import ai.philterd.philter.services.RequestIdGenerator;
import ai.philterd.philter.services.cache.ApiKeyCache;
import ai.philterd.philter.services.policies.PhiSqlCompileService;
import ai.philterd.philter.services.policies.PolicyValidation;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import io.swagger.v3.oas.annotations.Operation;
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
                                 final AuditEventPublisher auditEventPublisher, final ApiKeyCache apiKeyCache, final Gson gson) {
        super(apiKeyDataService, apiKeyCache);
        this.policyDataService = policyDataService;
        this.userService = userService;
        this.auditEventPublisher = auditEventPublisher;
        this.phiSqlCompileService = new PhiSqlCompileService();
        this.gson = gson;
    }

    @Operation(summary = "Get the names of existing policies.",
            description = "Returns the names of the caller's policies, paged. Admins may list another user's "
                    + "policies by passing that user's email as owner.")
    @ApiResponses(value = {@ApiResponse(responseCode = "200"), @ApiResponse(responseCode = "401"), @ApiResponse(responseCode = "404")})
    @RequestMapping(value = "/api/policies", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<List<String>> getPolicyNames(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestParam(value = "owner", required = false) String owner,
            final @RequestParam(value = "offset", defaultValue = "0") int offset,
            final @RequestParam(value = "limit", defaultValue = "25") int limit
    ) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);

        if(apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = resolveTargetUserId(userService, apiKeyEntity.getUserId(), owner);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        final List<PolicyEntity> policies = policyDataService.findAll(userId, offset, limit, false);
        final List<String> policyNames = policies.stream().map(PolicyEntity::getName).toList();

        return ResponseEntity.status(HttpStatus.OK)
                .body(policyNames);

    }

    @Operation(summary = "Get a policy.",
            description = "Returns the full policy with the given name. Admins may retrieve another user's policy "
                    + "by passing that user's email as owner.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200"),
            @ApiResponse(responseCode = "400", description = "The policy name is missing."),
            @ApiResponse(responseCode = "401"),
            @ApiResponse(responseCode = "404", description = "A policy with the given name does not exist.")
    })
    @RequestMapping(value = "/api/policies/{policyName}", method = RequestMethod.GET)
    public @ResponseBody ResponseEntity<Policy> get(
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

        final PolicyEntity policyEntity = policyDataService.findOne(policyName, userId);
        if (policyEntity == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        final Policy policy = gson.fromJson(policyEntity.getPolicy(), Policy.class);

        return ResponseEntity.status(HttpStatus.OK)
                .body(policy);

    }

    @Operation(summary = "Create or update a policy.",
            description = "Saves the policy supplied in the request body under the given name, overwriting any "
                    + "existing policy of the same name. The policy is validated before it is stored. Admins may save "
                    + "into another user's account by passing that user's email as owner.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "The policy was saved."),
            @ApiResponse(responseCode = "400", description = "The policy name is missing or the policy is invalid."),
            @ApiResponse(responseCode = "401"),
            @ApiResponse(responseCode = "404")
    })
    @RequestMapping(value = "/api/policies", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> save(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            @RequestParam("name") final String name,
            final @RequestParam(value = "owner", required = false) String owner,
            @RequestBody Policy policy) throws IOException {

        if (StringUtils.isBlank(name)) {
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

        // Validate the policy before persisting it so an invalid policy is rejected at creation rather
        // than failing later at redaction time.
        final String policyJson = gson.toJson(policy);
        final PolicyValidation validation = policyDataService.validatePolicy(policyJson);
        if (!validation.isValid()) {
            throw new BadRequestException(validation.getMessage());
        }

        auditAdminCrossUserAccess(auditEventPublisher, RequestIdGenerator.generate(), apiKeyEntity.getUserId(), userId,
                "create policy '" + name + "'");

        final PolicyEntity policyEntity = new PolicyEntity();
        policyEntity.setUserId(userId);
        policyEntity.setPolicy(policyJson);
        policyEntity.setName(name);

        policyDataService.save(policyEntity);

        return ResponseEntity.status(HttpStatus.CREATED).build();

    }

    @Operation(summary = "Delete a policy.",
            description = "Deletes the policy with the given name. Admins may delete another user's policy by passing "
                    + "that user's email as owner.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The policy was deleted."),
            @ApiResponse(responseCode = "400", description = "The policy name is missing."),
            @ApiResponse(responseCode = "401"),
            @ApiResponse(responseCode = "404")
    })
    @RequestMapping(value = "/api/policies/{policyName}", method = RequestMethod.DELETE)
    public ResponseEntity<Void> delete(
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

        policyDataService.deleteByName(requestId, policyName, userId, Source.API);

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
            @ApiResponse(responseCode = "401")
    })
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

}
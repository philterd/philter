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
import ai.philterd.philter.api.responses.PolicyRollbackResponse;
import ai.philterd.philter.api.responses.PolicyVersionSummary;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.entities.PolicyEntity;
import ai.philterd.philter.data.entities.PolicyVersionEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.PolicyVersionDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.services.RequestIdGenerator;
import ai.philterd.philter.services.cache.ApiKeyCache;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.ArrayList;
import java.util.List;

@Tag(name = "Policy Versions", description = "Operations for browsing policy version history, diffing revisions, and rolling back to a prior revision.")
@Controller
public class PolicyVersionsApiController extends AbstractApiController {

    private final PolicyDataService policyDataService;
    private final PolicyVersionDataService policyVersionDataService;
    private final UserService userService;
    private final AuditEventPublisher auditEventPublisher;
    private final Gson gson;

    public PolicyVersionsApiController(final PolicyDataService policyDataService,
                                        final PolicyVersionDataService policyVersionDataService,
                                        final UserService userService,
                                        final ApiKeyDataService apiKeyDataService,
                                        final AuditEventPublisher auditEventPublisher,
                                        final ApiKeyCache apiKeyCache,
                                        final Gson gson) {
        super(apiKeyDataService, apiKeyCache);
        this.policyDataService = policyDataService;
        this.policyVersionDataService = policyVersionDataService;
        this.userService = userService;
        this.auditEventPublisher = auditEventPublisher;
        this.gson = gson;
    }

    @Operation(summary = "List retained versions of a policy.",
            description = "Returns a summary of each retained snapshot for the named policy, ordered by "
                    + "revision descending (most recent first). Each entry carries the revision number, "
                    + "the timestamp the snapshot was captured, and its content hash. Admins may browse "
                    + "another user's history via the owner parameter.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Array of version summaries, most recent first."),
            @ApiResponse(responseCode = "400", description = "The policy name is missing."),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "404", description = "The named policy does not exist, or a non-admin caller named another user as owner.")
    })
    @RequestMapping(value = "/api/policies/{policyName}/versions", method = RequestMethod.GET,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<List<PolicyVersionSummary>> listVersions(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            @PathVariable("policyName") final String policyName,
            final @RequestParam(value = "owner", required = false) String owner,
            final @RequestParam(value = "offset", defaultValue = "0") int offset,
            final @RequestParam(value = "limit", defaultValue = "25") int limit) {

        if (policyName == null || policyName.isBlank()) {
            throw new BadRequestException("The policy name is missing.");
        }

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);
        if (apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = resolveTargetUserId(userService, apiKeyEntity.getUserId(), owner);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        auditAdminCrossUserAccess(auditEventPublisher, RequestIdGenerator.generate(),
                apiKeyEntity.getUserId(), userId, "list versions of policy '" + policyName + "'");

        final List<PolicyVersionEntity> versions =
                policyVersionDataService.findAllByName(policyName, userId, offset, Math.min(limit, 100));

        final List<PolicyVersionSummary> summaries = versions.stream()
                .map(v -> new PolicyVersionSummary(v.getRevision(), v.getCapturedTimestamp(), v.getContentHash()))
                .toList();

        auditEventPublisher.auditEvent(RequestIdGenerator.generate(),
                AuditLogEvent.POLICY_VERSION_HISTORY_RETRIEVED, null, null,
                "policy: " + policyName + ", versions returned: " + summaries.size(), null);

        return ResponseEntity.ok(summaries);
    }

    @Operation(summary = "Fetch a specific revision of a policy.",
            description = "Returns the full policy JSON as it existed at the given revision. The response "
                    + "shape is identical to GET /api/policies/{policyName}. Admins may fetch another "
                    + "user's revision via the owner parameter.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Full policy JSON at the requested revision."),
            @ApiResponse(responseCode = "400", description = "The policy name is missing."),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "404", description = "The policy or the requested revision does not exist.")
    })
    @RequestMapping(value = "/api/policies/{policyName}/versions/{revision}", method = RequestMethod.GET,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<String> getVersion(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            @PathVariable("policyName") final String policyName,
            @PathVariable("revision") final int revision,
            final @RequestParam(value = "owner", required = false) String owner) {

        if (policyName == null || policyName.isBlank()) {
            throw new BadRequestException("The policy name is missing.");
        }

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);
        if (apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = resolveTargetUserId(userService, apiKeyEntity.getUserId(), owner);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        auditAdminCrossUserAccess(auditEventPublisher, RequestIdGenerator.generate(),
                apiKeyEntity.getUserId(), userId,
                "fetch revision " + revision + " of policy '" + policyName + "'");

        final PolicyVersionEntity version =
                policyVersionDataService.findByNameAndRevision(policyName, userId, revision);
        if (version == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(version.getPolicy());
    }

    @Operation(summary = "Diff two revisions of a policy.",
            description = "Returns an RFC 6902 JSON Patch array describing what changed between the "
                    + "'from' revision and the 'to' revision. If from and to are omitted, the two most "
                    + "recent retained revisions are compared. Object-level changes (adds, removes, "
                    + "replaces) are reported per field path; array values that differ are reported as a "
                    + "single replace at the array path. Admins may diff another user's policy via owner.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Diff envelope containing the compared revision numbers and an RFC 6902 changes array."),
            @ApiResponse(responseCode = "400", description = "The policy name is missing, fewer than two retained revisions exist for a default diff, or only one of from/to was supplied."),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "404", description = "The policy or a requested revision does not exist.")
    })
    @RequestMapping(value = "/api/policies/{policyName}/diff", method = RequestMethod.GET,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<String> diff(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            @PathVariable("policyName") final String policyName,
            final @RequestParam(value = "from", required = false) Integer fromRevision,
            final @RequestParam(value = "to", required = false) Integer toRevision,
            final @RequestParam(value = "owner", required = false) String owner) {

        if (policyName == null || policyName.isBlank()) {
            throw new BadRequestException("The policy name is missing.");
        }

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);
        if (apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = resolveTargetUserId(userService, apiKeyEntity.getUserId(), owner);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        auditAdminCrossUserAccess(auditEventPublisher, RequestIdGenerator.generate(),
                apiKeyEntity.getUserId(), userId, "diff policy '" + policyName + "'");

        final PolicyVersionEntity fromVersion;
        final PolicyVersionEntity toVersion;

        if (fromRevision == null && toRevision == null) {
            final List<PolicyVersionEntity> recent = policyVersionDataService.findTwoMostRecent(policyName, userId);
            if (recent.size() < 2) {
                throw new BadRequestException("At least two retained revisions are required to produce a diff.");
            }
            // findTwoMostRecent returns descending: [newest, second-newest]
            toVersion = recent.get(0);
            fromVersion = recent.get(1);
        } else if (fromRevision != null && toRevision != null) {
            fromVersion = policyVersionDataService.findByNameAndRevision(policyName, userId, fromRevision);
            toVersion = policyVersionDataService.findByNameAndRevision(policyName, userId, toRevision);
            if (fromVersion == null || toVersion == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
        } else {
            throw new BadRequestException("Supply both 'from' and 'to' revision parameters, or omit both to diff the two most recent revisions.");
        }

        final JsonObject fromPolicy = gson.fromJson(fromVersion.getPolicy(), JsonObject.class);
        final JsonObject toPolicy = gson.fromJson(toVersion.getPolicy(), JsonObject.class);

        final List<JsonObject> ops = new ArrayList<>();
        computeDiff(fromPolicy, toPolicy, "", ops);

        final JsonObject envelope = new JsonObject();
        envelope.addProperty("from", fromVersion.getRevision());
        envelope.addProperty("to", toVersion.getRevision());
        final JsonArray changes = new JsonArray();
        ops.forEach(changes::add);
        envelope.add("changes", changes);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(gson.toJson(envelope));
    }

    @Operation(summary = "Roll back a policy to a prior revision.",
            description = "Restores the content of the specified revision as a new head revision, "
                    + "preserving the full history. The live policy is updated with the prior content "
                    + "and its revision counter is incremented. The rollback is audited. Admins may roll "
                    + "back another user's policy via the owner parameter.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Rollback succeeded. Body contains the new revision number."),
            @ApiResponse(responseCode = "400", description = "The policy name or target revision is missing."),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "404", description = "The policy or the target revision does not exist."),
            @ApiResponse(responseCode = "409", description = "Managed policies cannot be rolled back.")
    })
    @RequestMapping(value = "/api/policies/{policyName}/rollback", method = RequestMethod.POST,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<PolicyRollbackResponse> rollback(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            @PathVariable("policyName") final String policyName,
            @RequestParam("revision") final int targetRevision,
            final @RequestParam(value = "owner", required = false) String owner) {

        if (policyName == null || policyName.isBlank()) {
            throw new BadRequestException("The policy name is missing.");
        }

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);
        if (apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = resolveTargetUserId(userService, apiKeyEntity.getUserId(), owner);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        auditAdminCrossUserAccess(auditEventPublisher, RequestIdGenerator.generate(),
                apiKeyEntity.getUserId(), userId,
                "rollback policy '" + policyName + "' to revision " + targetRevision);

        final ServiceResponse response = policyDataService.rollback(
                RequestIdGenerator.generate(), policyName, userId, targetRevision);

        if (response.getStatusCode() == 404) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        if (response.getStatusCode() == 409) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        if (!response.isSuccessful()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        // Fetch the live policy to return the authoritative new revision number.
        final PolicyEntity live = policyDataService.findOne(policyName, userId);
        final int newRevision = live != null ? live.getRevision() : targetRevision + 1;
        return ResponseEntity.status(HttpStatus.CREATED).body(new PolicyRollbackResponse(newRevision));
    }

    /**
     * Recursively computes RFC 6902 JSON Patch operations between {@code from} and {@code to}.
     * Object properties are diffed field-by-field; arrays and primitives that differ produce a single
     * {@code replace} operation at their path.
     */
    private static void computeDiff(final JsonElement from, final JsonElement to,
                                     final String path, final List<JsonObject> ops) {
        if (from.equals(to)) {
            return;
        }

        if (from.isJsonObject() && to.isJsonObject()) {
            final JsonObject fromObj = from.getAsJsonObject();
            final JsonObject toObj = to.getAsJsonObject();

            for (final String key : fromObj.keySet()) {
                final String childPath = path + "/" + escapePointer(key);
                if (!toObj.has(key)) {
                    ops.add(op("remove", childPath, null));
                } else {
                    computeDiff(fromObj.get(key), toObj.get(key), childPath, ops);
                }
            }
            for (final String key : toObj.keySet()) {
                if (!fromObj.has(key)) {
                    ops.add(op("add", path + "/" + escapePointer(key), toObj.get(key)));
                }
            }
        } else {
            ops.add(op("replace", path, to));
        }
    }

    private static JsonObject op(final String opType, final String path, final JsonElement value) {
        final JsonObject o = new JsonObject();
        o.addProperty("op", opType);
        o.addProperty("path", path);
        if (value != null) {
            o.add("value", value);
        }
        return o;
    }

    /** Escapes JSON Pointer special characters per RFC 6901. */
    private static String escapePointer(final String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }

}

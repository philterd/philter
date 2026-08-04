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

import ai.philterd.philter.api.exceptions.UnauthorizedException;
import ai.philterd.philter.api.responses.GenericResponse;
import ai.philterd.philter.api.responses.GetLedgerResponse;
import ai.philterd.philter.api.responses.LedgerChainResponse;
import ai.philterd.philter.api.responses.LedgerEntryView;
import ai.philterd.philter.api.responses.LedgerExport;
import ai.philterd.philter.api.security.RequiresScope;
import ai.philterd.philter.model.ApiKeyScope;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.entities.LedgerEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.LedgerDataService;
import ai.philterd.philter.data.services.SigningKeyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.model.Source;
import ai.philterd.philter.services.cache.ApiKeyCache;
import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

@Tag(name = "Redaction Ledger", description = "Operations for viewing, exporting, verifying, and deleting redaction-ledger chains.")
@Controller
public class LedgerApiController extends AbstractApiController {

    private static final Logger LOGGER = LoggerFactory.getLogger(LedgerApiController.class);

    private final LedgerDataService ledgerService;
    private final UserService userService;
    private final AuditEventPublisher auditEventPublisher;
    private final SigningKeyDataService signingKeyDataService;
    private final Gson gson;

    public LedgerApiController(final LedgerDataService ledgerService,
                              final UserService userService,
                              final ApiKeyDataService apiKeyDataService,
                              final AuditEventPublisher auditEventPublisher,
                              final ApiKeyCache apiKeyCache, final SigningKeyDataService signingKeyDataService,
                              final Gson gson) {
        super(apiKeyDataService, apiKeyCache);
        this.signingKeyDataService = signingKeyDataService;
        this.ledgerService = ledgerService;
        this.userService = userService;
        this.auditEventPublisher = auditEventPublisher;
        this.gson = gson;
    }

    /** Maps a stored ledger entry to its API view, including its signature. */
    private static LedgerEntryView toView(final LedgerEntity entry) {
        final LedgerEntryView view = buildView(entry);
        view.setSignature(entry.getSignature());
        view.setSigningKeyId(entry.getSigningKeyId());
        return view;
    }

    private static LedgerEntryView buildView(final LedgerEntity entry) {
        return new LedgerEntryView(
                entry.getDocumentId(),
                entry.getFilename(),
                entry.getType(),
                entry.getToken(),
                entry.getReplacement(),
                entry.getStartPosition(),
                entry.getDocumentHash(),
                entry.getPreviousHash(),
                entry.getHash(),
                entry.getTimestamp(),
                entry.getPolicyName(),
                entry.getPolicyVersion(),
                entry.getPolicyContentHash());
    }

    @Operation(summary = "List redaction-ledger chains.",
            description = "Returns the head (genesis entry) of each redacted document's ledger chain, most recent "
                    + "first. Pass q to filter by document id or filename. Admins may list another user's chains by "
                    + "passing that user's email as owner.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The matching ledger chains."),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "404", description = "The owner does not exist, or the caller is not an admin.")
    })
    @RequiresScope(ApiKeyScope.LEDGER_READ)
    @RequestMapping(value = "/api/ledger", method = RequestMethod.GET)
    public ResponseEntity<String> getLedger(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestParam(value = "q", required = false) String query,
            final @RequestParam(value = "owner", required = false) String owner,
            final @RequestParam(value = "offset", defaultValue = "0") int offset,
            final @RequestParam(value = "limit", defaultValue = "25") int limit,
            final @RequestAttribute("requestId") String requestId) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);
        if (apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = resolveTargetUserId(userService, apiKeyEntity.getUserId(), owner);
        if (userId == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        final int pageOffset = normalizeOffset(offset);
        final int pageLimit = normalizeLimit(limit);

        // A search is paged and counted the same way an unfiltered listing is, so `total` always
        // describes the set the returned chains were taken from.
        final boolean searching = query != null && !query.isBlank();

        final List<LedgerEntity> chains = searching
                ? ledgerService.searchChainsByUserId(requestId, userId, query, pageOffset, pageLimit, Source.API.getSource())
                : ledgerService.findChainsByUserId(requestId, userId, pageOffset, pageLimit, Source.API.getSource());

        final List<LedgerEntryView> views = new ArrayList<>(chains.size());
        for (final LedgerEntity chain : chains) {
            views.add(toView(chain));
        }

        final int total = searching
                ? ledgerService.countChainsByUserIdMatching(userId, query)
                : ledgerService.countChainsByUserId(userId);

        return new ResponseEntity<>(gson.toJson(new GetLedgerResponse(views, total)), HttpStatus.OK);

    }

    @Operation(summary = "Get a document's ledger chain.",
            description = "Returns the full ordered chain of ledger entries for a document, along with whether the "
                    + "hash chain currently verifies.")
    @ApiResponses(value = {@ApiResponse(responseCode = "200"), @ApiResponse(responseCode = "404")})
    @RequiresScope(ApiKeyScope.LEDGER_READ)
    @RequestMapping(value = "/api/ledger/{documentId}", method = RequestMethod.GET)
    public ResponseEntity<String> getChain(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @PathVariable("documentId") String documentId,
            final @RequestParam(value = "owner", required = false) String owner,
            final @RequestAttribute("requestId") String requestId,
            final HttpServletRequest httpServletRequest) throws Exception {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);
        if (apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = resolveTargetUserId(userService, apiKeyEntity.getUserId(), owner);
        if (userId == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        final List<LedgerEntity> chain = ledgerService.getChain(userId, documentId);
        if (chain.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        final LedgerDataService.ChainValidation chainValidation = ledgerService.validateChain(userId, documentId);

        final List<LedgerEntryView> entries = new ArrayList<>(chain.size());
        for (final LedgerEntity entry : chain) {
            entries.add(toView(entry));
        }

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.REDACTION_LEDGER_QUERY, apiKeyEntity.getUserId(), null,
                getClientIpAddress(httpServletRequest), "owner: " + userId + ", documentId: " + documentId);

        return new ResponseEntity<>(gson.toJson(new LedgerChainResponse(documentId, chainValidation.valid(),
                chainValidation.hashChainValid(), chainValidation.signaturesValid(),
                chainValidation.signedEntries(), chainValidation.unsignedEntries(), entries)), HttpStatus.OK);

    }

    @Operation(summary = "Verify a document's ledger chain.",
            description = "Returns whether the hash chain for the document's ledger verifies (no entry has been "
                    + "altered and every link is intact).")
    @ApiResponses(value = {@ApiResponse(responseCode = "200"), @ApiResponse(responseCode = "404")})
    @RequiresScope(ApiKeyScope.LEDGER_READ)
    @RequestMapping(value = "/api/ledger/{documentId}/valid", method = RequestMethod.GET)
    public ResponseEntity<String> validateChain(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @PathVariable("documentId") String documentId,
            final @RequestParam(value = "owner", required = false) String owner) throws Exception {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);
        if (apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = resolveTargetUserId(userService, apiKeyEntity.getUserId(), owner);
        if (userId == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        if (ledgerService.getChain(userId, documentId).isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        final LedgerDataService.ChainValidation validation = ledgerService.validateChain(userId, documentId);

        return new ResponseEntity<>(gson.toJson(new LedgerChainResponse(documentId, validation.valid(),
                validation.hashChainValid(), validation.signaturesValid(),
                validation.signedEntries(), validation.unsignedEntries(), null)), HttpStatus.OK);

    }

    @Operation(summary = "Export a document's ledger chain.",
            description = "Returns the full ledger chain for a document as a portable JSON document that can be archived "
                    + "and later re-verified. The export contains the decrypted token and replacement values, so treat it "
                    + "as sensitive.")
    @ApiResponses(value = {@ApiResponse(responseCode = "200"), @ApiResponse(responseCode = "404")})
    @RequiresScope(ApiKeyScope.LEDGER_EXPORT)
    @RequestMapping(value = "/api/ledger/{documentId}/export", method = RequestMethod.GET)
    public ResponseEntity<String> exportChain(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @PathVariable("documentId") String documentId,
            final @RequestParam(value = "owner", required = false) String owner,
            final @RequestAttribute("requestId") String requestId,
            final HttpServletRequest httpServletRequest) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);
        if (apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = resolveTargetUserId(userService, apiKeyEntity.getUserId(), owner);
        if (userId == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        final List<LedgerEntity> chain = ledgerService.getChain(userId, documentId);
        if (chain.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        final List<LedgerEntryView> entries = new ArrayList<>(chain.size());
        for (final LedgerEntity entry : chain) {
            entries.add(toView(entry));
        }

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.REDACTION_LEDGER_EXPORTED, apiKeyEntity.getUserId(), null,
                getClientIpAddress(httpServletRequest), "owner: " + userId + ", documentId: " + documentId + ", count: " + entries.size());

        final HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ledger-" + documentId + "-export.json\"");

        // Collect the public keys the entries were signed with so the export verifies standalone.
        final Map<String, String> signingKeys = new LinkedHashMap<>();
        for (final LedgerEntryView entry : entries) {
            final String keyId = entry.getSigningKeyId();
            if (keyId != null && !signingKeys.containsKey(keyId)) {
                final String pem = signingKeyDataService.getPublicKeyPem(keyId);
                if (pem != null) {
                    signingKeys.put(keyId, pem);
                }
            }
        }

        return new ResponseEntity<>(gson.toJson(new LedgerExport(documentId, entries, signingKeys)), headers, HttpStatus.OK);

    }

    private static final String DELETION_DISABLED =
            "Ledger deletion is disabled. Set LEDGER_DELETION_ENABLED=true to enable it.";

    @Operation(summary = "Delete a document's ledger chain.",
            description = "Permanently deletes every ledger entry for the given document. Requires an administrator "
                    + "and LEDGER_DELETION_ENABLED=true. Admins may delete another user's chain by passing that "
                    + "user's username as owner, which additionally requires ADMIN_CROSS_USER_ACCESS_ENABLED=true.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The document's ledger chain was deleted."),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "403", description = "The caller is not an administrator, or ledger deletion is disabled for this deployment."),
            @ApiResponse(responseCode = "404", description = "No ledger chain for that document exists for this user."),
            @ApiResponse(responseCode = "423", description = "The chain is protected by an active legal hold. Release the hold before deleting.")
    })
    @RequiresScope(ApiKeyScope.LEDGER_DELETE)
    @RequestMapping(value = "/api/ledger/{documentId}", method = RequestMethod.DELETE)
    public ResponseEntity<GenericResponse> deleteChain(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @PathVariable("documentId") String documentId,
            final @RequestParam(value = "owner", required = false) String owner,
            final @RequestAttribute("requestId") String requestId,
            final HttpServletRequest httpServletRequest) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);
        if (apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ResponseEntity<GenericResponse> refusal = authorizeAdminOnly(userService, apiKeyEntity.getUserId(),
                isLedgerDeletionEnabled(), "Deleting a ledger chain", DELETION_DISABLED);
        if (refusal != null) {
            return refusal;
        }

        final ObjectId userId = resolveTargetUserId(userService, apiKeyEntity.getUserId(), owner);
        if (userId == null) {
            return new ResponseEntity<>(new GenericResponse("Not found."), HttpStatus.NOT_FOUND);
        }

        auditAdminCrossUserAccess(auditEventPublisher, requestId, apiKeyEntity.getUserId(), userId,
                "delete ledger chain " + documentId);

        final ServiceResponse deleteResponse = ledgerService.deleteByDocumentId(
                requestId, userId, documentId, getClientIpAddress(httpServletRequest));

        if (!deleteResponse.isSuccessful()) {
            final HttpStatus status = deleteResponse.getStatusCode() == 423
                    ? HttpStatus.LOCKED : HttpStatus.BAD_REQUEST;
            return new ResponseEntity<>(new GenericResponse(deleteResponse.getMessage()), status);
        }

        return new ResponseEntity<>(new GenericResponse("Ledger chain deleted."), HttpStatus.OK);

    }

    @Operation(summary = "Purge old ledger entries.",
            description = "Deletes ledger entries older than the given number of days. The ledger is kept "
                    + "indefinitely by default, so this is how stale entries are pruned on demand. Requires an "
                    + "administrator and LEDGER_DELETION_ENABLED=true. Admins may purge another user's entries by "
                    + "passing that user's username as owner, which additionally requires "
                    + "ADMIN_CROSS_USER_ACCESS_ENABLED=true.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The matching entries were purged."),
            @ApiResponse(responseCode = "400", description = "older_than_days is missing or negative."),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "403", description = "The caller is not an administrator, or ledger deletion is disabled for this deployment."),
            @ApiResponse(responseCode = "404"),
            @ApiResponse(responseCode = "423", description = "One or more active legal holds protect entries in this user's ledger. Release all holds before purging.")
    })
    @RequiresScope(ApiKeyScope.LEDGER_DELETE)
    @RequestMapping(value = "/api/ledger", method = RequestMethod.DELETE)
    public ResponseEntity<GenericResponse> purge(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestParam("older_than_days") int olderThanDays,
            final @RequestParam(value = "owner", required = false) String owner,
            final @RequestAttribute("requestId") String requestId) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);
        if (apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ResponseEntity<GenericResponse> refusal = authorizeAdminOnly(userService, apiKeyEntity.getUserId(),
                isLedgerDeletionEnabled(), "Purging ledger entries", DELETION_DISABLED);
        if (refusal != null) {
            return refusal;
        }

        if (olderThanDays < 0) {
            return new ResponseEntity<>(new GenericResponse("older_than_days must be zero or greater."), HttpStatus.BAD_REQUEST);
        }

        final ObjectId userId = resolveTargetUserId(userService, apiKeyEntity.getUserId(), owner);
        if (userId == null) {
            return new ResponseEntity<>(new GenericResponse("Not found."), HttpStatus.NOT_FOUND);
        }

        auditAdminCrossUserAccess(auditEventPublisher, requestId, apiKeyEntity.getUserId(), userId,
                "purge ledger entries older than " + olderThanDays + " days");

        final ServiceResponse purgeResponse =
                ledgerService.deleteChainsByUserIdAndOlderThan(requestId, userId, olderThanDays);

        if (!purgeResponse.isSuccessful()) {
            final HttpStatus status = purgeResponse.getStatusCode() == 423
                    ? HttpStatus.LOCKED : HttpStatus.BAD_REQUEST;
            return new ResponseEntity<>(new GenericResponse(purgeResponse.getMessage()), status);
        }

        return new ResponseEntity<>(new GenericResponse(purgeResponse.getMessage()), HttpStatus.OK);

    }

}

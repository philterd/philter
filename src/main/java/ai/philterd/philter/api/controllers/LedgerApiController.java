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
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.entities.LedgerEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.LedgerDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.AuditLogEvent;
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
import java.util.List;

@Tag(name = "Redaction Ledger", description = "Operations for viewing, exporting, verifying, and deleting redaction-ledger chains.")
@Controller
public class LedgerApiController extends AbstractApiController {

    private static final Logger LOGGER = LoggerFactory.getLogger(LedgerApiController.class);

    private final LedgerDataService ledgerService;
    private final UserService userService;
    private final AuditEventPublisher auditEventPublisher;
    private final Gson gson;

    public LedgerApiController(final LedgerDataService ledgerService,
                              final UserService userService,
                              final ApiKeyDataService apiKeyDataService,
                              final AuditEventPublisher auditEventPublisher,
                              final ApiKeyCache apiKeyCache, final Gson gson) {
        super(apiKeyDataService, apiKeyCache);
        this.ledgerService = ledgerService;
        this.userService = userService;
        this.auditEventPublisher = auditEventPublisher;
        this.gson = gson;
    }

    /** Maps a stored ledger entry to its API view. */
    private static LedgerEntryView toView(final LedgerEntity entry) {
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
    @ApiResponses(value = {@ApiResponse(responseCode = "200"), @ApiResponse(responseCode = "401"), @ApiResponse(responseCode = "404")})
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

        final List<LedgerEntity> chains;
        if (query != null && !query.isBlank()) {
            chains = ledgerService.searchChainsByUserId(requestId, userId, query, Source.API.getSource());
        } else {
            chains = ledgerService.findChainsByUserId(requestId, userId, offset, limit, Source.API.getSource());
        }

        final List<LedgerEntryView> views = new ArrayList<>(chains.size());
        for (final LedgerEntity chain : chains) {
            views.add(toView(chain));
        }

        final int total = ledgerService.countChainsByUserId(userId);

        return new ResponseEntity<>(gson.toJson(new GetLedgerResponse(views, total)), HttpStatus.OK);

    }

    @Operation(summary = "Get a document's ledger chain.",
            description = "Returns the full ordered chain of ledger entries for a document, along with whether the "
                    + "hash chain currently verifies.")
    @ApiResponses(value = {@ApiResponse(responseCode = "200"), @ApiResponse(responseCode = "404")})
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

        final boolean valid = ledgerService.isChainValid(userId, documentId);

        final List<LedgerEntryView> entries = new ArrayList<>(chain.size());
        for (final LedgerEntity entry : chain) {
            entries.add(toView(entry));
        }

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.REDACTION_LEDGER_QUERY, apiKeyEntity.getUserId(), null,
                getClientIpAddress(httpServletRequest), "owner: " + userId + ", documentId: " + documentId);

        return new ResponseEntity<>(gson.toJson(new LedgerChainResponse(documentId, valid, entries)), HttpStatus.OK);

    }

    @Operation(summary = "Verify a document's ledger chain.",
            description = "Returns whether the hash chain for the document's ledger verifies (no entry has been "
                    + "altered and every link is intact).")
    @ApiResponses(value = {@ApiResponse(responseCode = "200"), @ApiResponse(responseCode = "404")})
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

        final boolean valid = ledgerService.isChainValid(userId, documentId);

        return new ResponseEntity<>(gson.toJson(new LedgerChainResponse(documentId, valid, null)), HttpStatus.OK);

    }

    @Operation(summary = "Export a document's ledger chain.",
            description = "Returns the full ledger chain for a document as a portable JSON document that can be archived "
                    + "and later re-verified. The export contains the decrypted token and replacement values, so treat it "
                    + "as sensitive.")
    @ApiResponses(value = {@ApiResponse(responseCode = "200"), @ApiResponse(responseCode = "404")})
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

        return new ResponseEntity<>(gson.toJson(new LedgerExport(documentId, entries)), headers, HttpStatus.OK);

    }

    @Operation(summary = "Delete a document's ledger chain.",
            description = "Permanently deletes every ledger entry for the given document. Admins may delete another "
                    + "user's chain by passing that user's email as owner.")
    @ApiResponses(value = {@ApiResponse(responseCode = "200"), @ApiResponse(responseCode = "401"), @ApiResponse(responseCode = "404")})
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

        final ObjectId userId = resolveTargetUserId(userService, apiKeyEntity.getUserId(), owner);
        if (userId == null) {
            return new ResponseEntity<>(new GenericResponse("Not found."), HttpStatus.NOT_FOUND);
        }

        auditAdminCrossUserAccess(auditEventPublisher, requestId, apiKeyEntity.getUserId(), userId,
                "delete ledger chain " + documentId);

        ledgerService.deleteByDocumentId(requestId, userId, documentId, getClientIpAddress(httpServletRequest));

        return new ResponseEntity<>(new GenericResponse("Ledger chain deleted."), HttpStatus.OK);

    }

    @Operation(summary = "Purge old ledger entries.",
            description = "Manually deletes ledger entries older than the given number of days. The ledger is kept "
                    + "indefinitely by default, so this is how stale entries are pruned on demand. Admins may purge "
                    + "another user's entries by passing that user's email as owner.")
    @ApiResponses(value = {@ApiResponse(responseCode = "200"), @ApiResponse(responseCode = "400"), @ApiResponse(responseCode = "401"), @ApiResponse(responseCode = "404")})
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

        if (olderThanDays < 0) {
            return new ResponseEntity<>(new GenericResponse("older_than_days must be zero or greater."), HttpStatus.BAD_REQUEST);
        }

        final ObjectId userId = resolveTargetUserId(userService, apiKeyEntity.getUserId(), owner);
        if (userId == null) {
            return new ResponseEntity<>(new GenericResponse("Not found."), HttpStatus.NOT_FOUND);
        }

        auditAdminCrossUserAccess(auditEventPublisher, requestId, apiKeyEntity.getUserId(), userId,
                "purge ledger entries older than " + olderThanDays + " days");

        final long deleted = ledgerService.deleteChainsByUserIdAndOlderThan(requestId, userId, olderThanDays);

        return new ResponseEntity<>(new GenericResponse("Deleted " + deleted + " ledger entries older than " + olderThanDays + " days."), HttpStatus.OK);

    }

}

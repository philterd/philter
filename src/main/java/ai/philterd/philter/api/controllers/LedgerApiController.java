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
import ai.philterd.philter.api.responses.GenericResponse;
import ai.philterd.philter.api.responses.GetLedgerResponse;
import ai.philterd.philter.api.responses.LedgerChainResponse;
import ai.philterd.philter.api.responses.LedgerEntryView;
import ai.philterd.philter.data.services.UnreadableLedgerEntryException;
import ai.philterd.philter.api.responses.LedgerRefusalResponse;
import ai.philterd.philter.api.responses.LedgerExport;
import ai.philterd.philter.api.responses.OwnedLedgerEntryView;
import ai.philterd.philter.api.security.RequiresScope;
import ai.philterd.philter.model.ApiKeyScope;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.entities.LedgerEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.LedgerDataService;
import ai.philterd.philter.data.services.Listings;
import ai.philterd.philter.data.services.SigningKeyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.model.Source;
import ai.philterd.philter.services.cache.ApiKeyCache;
import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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

    /**
     * Read view. The token is the original PII, so it is carried only by the export, which has its own
     * scope; reading a chain proves what happened without handing back what was redacted.
     */
    private static LedgerEntryView toView(final LedgerEntity entry) {
        return signed(buildView(entry, null), entry);
    }

    /** Export view, which carries the token. Reached only with {@code ledger:export}. */
    private static LedgerEntryView toExportView(final LedgerEntity entry) {
        return signed(buildView(entry, entry.getToken()), entry);
    }

    private static LedgerEntryView signed(final LedgerEntryView view, final LedgerEntity entry) {
        view.setSignature(entry.getSignature());
        view.setSigningKeyId(entry.getSigningKeyId());
        return view;
    }

    private static LedgerEntryView buildView(final LedgerEntity entry, final String token) {
        return buildView(entry, token, null);
    }

    /** With an owner, the view names it, as a listing across users does. */
    private static LedgerEntryView buildView(final LedgerEntity entry, final String token, final String owner) {
        final LedgerEntryView view = owner == null ? new LedgerEntryView(
                entry.getDocumentId(),
                entry.getFilename(),
                entry.getType(),
                token,
                entry.getReplacement(),
                entry.getStartPosition(),
                entry.getDocumentHash(),
                entry.getPreviousHash(),
                entry.getHash(),
                entry.getTimestamp(),
                entry.getPolicyName(),
                entry.getPolicyVersion(),
                entry.getPolicyContentHash()) : new OwnedLedgerEntryView(
                owner,
                entry.getDocumentId(),
                entry.getFilename(),
                entry.getType(),
                token,
                entry.getReplacement(),
                entry.getStartPosition(),
                entry.getDocumentHash(),
                entry.getPreviousHash(),
                entry.getHash(),
                entry.getTimestamp(),
                entry.getPolicyName(),
                entry.getPolicyVersion(),
                entry.getPolicyContentHash());
        view.setEffectiveHash(entry.getEffectiveHash());
        if (entry.isUnreadable()) {
            view.setReadError(ENTRY_UNREADABLE);
        }
        return view;
    }

    /** Said of an entry that could not be read. Names no cause, which is in the log. */
    private static final String ENTRY_UNREADABLE = "This entry could not be read, so its replacement is not shown.";

    @Operation(summary = "List redaction-ledger chains.",
            description = "Returns the head (genesis entry) of each redacted document's ledger chain, most recent "
                    + "first. Pass q to filter by document id or filename. Admins may list another user's chains by "
                    + "passing that user's username as owner, or every user's with all_users=true, which adds each "
                    + "chain's owner, cannot be combined with q, and requires ADMIN_CROSS_USER_ACCESS_ENABLED.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The matching ledger chains. With all_users, each entry also has an owner field. "
                    + "A chain whose head entry cannot be read is still listed, with a readError and without its replacement."),
            @ApiResponse(responseCode = "400", description = "all_users was combined with owner or q."),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "404", description = "The owner does not exist, or the caller may not reach it. The API does not distinguish the two, so an owner value cannot be used to discover accounts.")
    })
    @RequiresScope(ApiKeyScope.LEDGER_READ)
    @RequestMapping(value = "/api/ledger", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getLedger(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestParam(value = "q", required = false) String query,
            final @RequestParam(value = "owner", required = false) String owner,
            final @RequestParam(value = "all_users", defaultValue = "false") boolean allUsers,
            final @RequestParam(value = "sort", required = false) String sort,
            final @RequestParam(value = "order", required = false) String order,
            final @RequestParam(value = "offset", defaultValue = "0") int offset,
            final @RequestParam(value = "limit", defaultValue = "25") int limit,
            final @RequestAttribute("requestId") String requestId) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);
        if (apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final Listings.Sort chainSort = listingSort(sort, order, CHAIN_SORT, "created", true);

        if (allUsers) {
            if (!mayListAllUsers(userService, apiKeyEntity.getUserId(), owner)) {
                return new ResponseEntity<>(HttpStatus.NOT_FOUND);
            }
            final Listings.Page<LedgerEntity> page = ledgerService.listChains(requestId, null, query, chainSort,
                    normalizeOffset(offset), normalizeLimit(limit), Source.API.getSource());
            final List<LedgerEntity> chains = page.items();
            final Map<ObjectId, String> owners = ownerNames(userService, chains, LedgerEntity::getUserId);
            final List<LedgerEntryView> views = new ArrayList<>(chains.size());
            for (final LedgerEntity chain : chains) {
                views.add(signed(buildView(chain, null, owners.get(chain.getUserId())), chain));
            }
            auditAllUsersListing(auditEventPublisher, requestId, apiKeyEntity.getUserId(), "list ledger chains");
            return new ResponseEntity<>(gson.toJson(new GetLedgerResponse(views, (int) page.total())), HttpStatus.OK);
        }

        final ObjectId userId = resolveTargetUserId(userService, apiKeyEntity.getUserId(), owner);
        if (userId == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        final int pageOffset = normalizeOffset(offset);
        final int pageLimit = normalizeLimit(limit);

        // A search is paged and counted the same way an unfiltered listing is, so `total` always
        // describes the set the returned chains were taken from.
        final Listings.Page<LedgerEntity> page = ledgerService.listChains(requestId, userId, query, chainSort,
                pageOffset, pageLimit, Source.API.getSource());
        final List<LedgerEntity> chains = page.items();

        final List<LedgerEntryView> views = new ArrayList<>(chains.size());
        for (final LedgerEntity chain : chains) {
            views.add(toView(chain));
        }

        return new ResponseEntity<>(gson.toJson(new GetLedgerResponse(views, (int) page.total())), HttpStatus.OK);

    }

    /** The order a listing of ledger chains can take. */
    private static final Map<String, String> CHAIN_SORT = sortFields("created", "timestamp", "filename", "filename");

    @Operation(summary = "Get a document's ledger chain.",
            description = "Returns the full ordered chain of ledger entries for a document, along with whether the "
                    + "hash chain currently verifies. A chain that cannot be validated, such as one with an entry that "
                    + "can no longer be read, returns valid false with a validationError and no entries.")
    @ApiResponses(value = {@ApiResponse(responseCode = "200"), @ApiResponse(responseCode = "404")})
    @RequiresScope(ApiKeyScope.LEDGER_READ)
    @RequestMapping(value = "/api/ledger/{documentId}", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
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

        // Checked without reading an entry, so a damaged one is reported by validation rather than thrown here.
        if (!ledgerService.chainExists(userId, documentId)) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        final LedgerDataService.ChainValidation chainValidation = ledgerService.validateChain(userId, documentId);

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.REDACTION_LEDGER_QUERY, apiKeyEntity.getUserId(), null,
                getClientIpAddress(httpServletRequest), "owner: " + userId + ", documentId: " + documentId);

        // The entries could not be read or checked, so none are returned.
        if (chainValidation.error() != null) {
            return new ResponseEntity<>(gson.toJson(LedgerChainResponse.unverifiable(documentId, chainValidation.error())),
                    HttpStatus.OK);
        }

        final List<LedgerEntity> chain = ledgerService.getChain(userId, documentId);
        final List<LedgerEntryView> entries = new ArrayList<>(chain.size());
        for (final LedgerEntity entry : chain) {
            entries.add(toView(entry));
        }

        return new ResponseEntity<>(gson.toJson(new LedgerChainResponse(documentId, chainValidation.valid(),
                chainValidation.hashChainValid(), chainValidation.signaturesValid(),
                chainValidation.signedEntries(), chainValidation.unsignedEntries(), entries)), HttpStatus.OK);

    }

    @Operation(summary = "Verify a document's ledger chain.",
            description = "Returns whether the hash chain for the document's ledger verifies (no entry has been "
                    + "altered and every link is intact). A chain that cannot be validated returns valid false with a "
                    + "validationError.")
    @ApiResponses(value = {@ApiResponse(responseCode = "200"), @ApiResponse(responseCode = "404")})
    @RequiresScope(ApiKeyScope.LEDGER_READ)
    @RequestMapping(value = "/api/ledger/{documentId}/valid", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
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

        if (!ledgerService.chainExists(userId, documentId)) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        final LedgerDataService.ChainValidation validation = ledgerService.validateChain(userId, documentId);
        if (validation.error() != null) {
            return new ResponseEntity<>(gson.toJson(LedgerChainResponse.unverifiable(documentId, validation.error())),
                    HttpStatus.OK);
        }

        return new ResponseEntity<>(gson.toJson(new LedgerChainResponse(documentId, validation.valid(),
                validation.hashChainValid(), validation.signaturesValid(),
                validation.signedEntries(), validation.unsignedEntries(), null)), HttpStatus.OK);

    }

    @Operation(summary = "Export a document's ledger chain.",
            description = "Returns the full ledger chain for a document as a portable JSON document that can be archived "
                    + "and later re-verified. The export contains the decrypted token and replacement values, so treat it "
                    + "as sensitive. A chain with an entry that cannot be read is not exported, even in part.")
    @ApiResponses(value = {@ApiResponse(responseCode = "200"), @ApiResponse(responseCode = "404"),
            @ApiResponse(responseCode = "422", description = "An entry in the chain could not be read, so the chain is "
                    + "not exported; reason is entry_unreadable. The attempt is audited as redaction_ledger_exported "
                    + "with refused in its details.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = LedgerRefusalResponse.class)))})
    @RequiresScope(ApiKeyScope.LEDGER_EXPORT)
    @RequestMapping(value = "/api/ledger/{documentId}/export", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
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

        final List<LedgerEntity> chain;
        try {
            chain = ledgerService.getChain(userId, documentId);
        } catch (final UnreadableLedgerEntryException e) {
            // No partial export: an export is evidence meant to be re-verified, and a chain with an entry left
            // out or blanked would not verify while looking complete. Audited as an export attempt.
            LOGGER.warn("Refused to export the ledger chain for document {}: an entry could not be read.", documentId, e);
            auditEventPublisher.auditEvent(requestId, AuditLogEvent.REDACTION_LEDGER_EXPORTED, apiKeyEntity.getUserId(), null,
                    getClientIpAddress(httpServletRequest), "owner: " + userId + ", documentId: " + documentId
                            + ", count: 0, refused: " + LedgerRefusalResponse.REASON_ENTRY_UNREADABLE);
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).contentType(MediaType.APPLICATION_JSON)
                    .body(gson.toJson(new LedgerRefusalResponse("An entry in this chain could not be read, so the chain "
                            + "cannot be exported.", LedgerRefusalResponse.REASON_ENTRY_UNREADABLE)));
        }
        if (chain.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        final List<LedgerEntryView> entries = new ArrayList<>(chain.size());
        for (final LedgerEntity entry : chain) {
            entries.add(toExportView(entry));
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
            description = "Permanently deletes every ledger entry for a completed document chain. Active or failed publication returns 409. Requires an administrator "
                    + "and LEDGER_DELETION_ENABLED=true. Admins may delete another user's chain by passing that "
                    + "user's username as owner, which additionally requires ADMIN_CROSS_USER_ACCESS_ENABLED=true.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The document's ledger chain was deleted."),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "403", description = "The caller is not an administrator, or ledger deletion is disabled for this deployment."),
            @ApiResponse(responseCode = "404", description = "No ledger chain for that document exists for this user."),
            @ApiResponse(responseCode = "409", description = "An evidence or hold operation is active or requires recovery."),
            @ApiResponse(responseCode = "423", description = "The chain is protected by an active legal hold. Release the hold before deleting.")
    })
    @RequiresScope(ApiKeyScope.LEDGER_DELETE)
    @RequestMapping(value = "/api/ledger/{documentId}", method = RequestMethod.DELETE, produces = MediaType.APPLICATION_JSON_VALUE)
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
            final HttpStatus status = HttpStatus.valueOf(deleteResponse.getStatusCode());
            return new ResponseEntity<>(new GenericResponse(deleteResponse.getMessage()), status);
        }

        return new ResponseEntity<>(new GenericResponse("Ledger chain deleted."), HttpStatus.OK);

    }

    @Operation(summary = "Purge old ledger entries.",
            description = "Deletes whole completed chains whose completion and newest entry are older than the given number of days. The ledger is kept "
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
            @ApiResponse(responseCode = "409", description = "An evidence or hold operation is active or requires recovery."),
            @ApiResponse(responseCode = "423", description = "One or more active legal holds protect entries in this user's ledger. Release all holds before purging.")
    })
    @RequiresScope(ApiKeyScope.LEDGER_DELETE)
    @RequestMapping(value = "/api/ledger", method = RequestMethod.DELETE, produces = MediaType.APPLICATION_JSON_VALUE)
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
            final HttpStatus status = HttpStatus.valueOf(purgeResponse.getStatusCode());
            return new ResponseEntity<>(new GenericResponse(purgeResponse.getMessage()), status);
        }

        return new ResponseEntity<>(new GenericResponse(purgeResponse.getMessage()), HttpStatus.OK);

    }

}

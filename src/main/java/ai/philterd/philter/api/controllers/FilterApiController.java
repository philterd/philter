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

import ai.philterd.philter.audit.AuditDetails;
import ai.philterd.philter.data.entities.PolicyEntity;
import ai.philterd.philter.data.services.PolicyVersionDataService;
import ai.philterd.philter.services.filtering.AppliedPolicy;
import ai.philterd.philter.services.policies.PolicyNotFoundException;
import ai.philterd.philter.services.filtering.RedactionOutcome;
import ai.philterd.philter.services.filtering.ResultHeaders;
import ai.philterd.phileas.services.filters.filtering.FilterOptions;
import ai.philterd.philter.services.signing.SigningService;
import ai.philterd.phileas.model.filtering.BinaryDocumentFilterResult;
import ai.philterd.phileas.model.filtering.MimeType;
import ai.philterd.phileas.model.filtering.TextFilterResult;
import ai.philterd.philter.api.exceptions.BadRequestException;
import ai.philterd.philter.api.exceptions.UnauthorizedException;
import ai.philterd.philter.api.security.RequiresScope;
import ai.philterd.philter.model.ApiKeyScope;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.audit.ClientAddress;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.entities.PendingDocumentEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.FairnessKeys;
import ai.philterd.philter.data.services.PendingDocumentDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.services.cache.ApiKeyCache;
import ai.philterd.philter.services.filtering.RedactionService;
import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import ai.philterd.philter.services.filtering.AppliedPagePolicy;
import ai.philterd.philter.services.filtering.PagePolicyRequest;
import ai.philterd.philter.services.filtering.RedactionService.PagePolicyCapture;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "Filter", description = "Redact text, PDF, Word, Excel, and RTF documents, and email messages.")
@Controller
public class FilterApiController extends AbstractApiController {

    private static final Logger LOGGER = LogManager.getLogger(FilterApiController.class);

    /** Response headers reporting the applied policy name, version, content hash, and document ID on every text /api/filter response. */
    public static final String POLICY_NAME_HEADER = "X-Philter-Policy-Name";
    public static final String POLICY_VERSION_HEADER = "X-Philter-Policy-Version";
    public static final String POLICY_HASH_HEADER = "X-Philter-Policy-Hash";
    public static final String DOCUMENT_ID_HEADER = "X-Document-Id";
    /** The policy and version that redacted each page set named with page_policy: {@code pages=policy@version; ...}. */
    public static final String PAGE_POLICIES_HEADER = "X-Philter-Page-Policies";
    /**
     * The reasons a request is flagged for review, separated by ", ": {@value #REVIEW_SAMPLED} when the policy's
     * config.review.samplingRate selected it, and leftovers when its leftover check found something. A queued document
     * is drawn for when it is accepted, so the 202 carries sampled; leftovers is on its status and download responses.
     */
    public static final String REVIEW_HEADER = "X-Philter-Review";
    public static final String REVIEW_SAMPLED = "sampled";

    /** Documented in ApiDocumentationConfig, since the parameter is read from the request rather than bound. */
    public static final String PAGE_POLICY_PARAMETER =
            "Optional, repeatable. Redacts a set of pages with a different policy, as <pages>:<policy>, such as "
                    + "2-5,8:records or 12-:exhibits. Pages are numbered from 1, and a page set is single pages, ranges, "
                    + "and open-ended ranges separated by commas. Pages not assigned are redacted with the policy named by "
                    + "p, whose config.pdf settings render the whole document. One context spans the document. Page sets "
                    + "may not overlap or extend beyond the last page.";

    private final RedactionService redactionService;
    private final PolicyDataService policyDataService;
    private final PolicyVersionDataService policyVersionDataService;
    private final AuditEventPublisher auditEventPublisher;
    private final PendingDocumentDataService pendingDocumentDataService;
    private final Gson gson;
    /** Shared with ExplainApiController: the same parameter, documented the same way. */
    public static final String SIGN_PARAMETER =
            "Pass sign=true to request a signature when the deployment has not enabled signing. The "
                    + "admin setting is a floor: with it on every response is signed and sign=false "
                    + "does not change that.";

    /** Shared with ExplainApiController and documented the same way. */
    public static final String LEFTOVER_CHECK_PARAMETER =
            "Optional, defaults to false. Requests the policy's leftover check (config.review.leftoverCheck) when its "
                    + "mode is onRequest. With mode always every request is checked and leftover_check=false does not "
                    + "change that; with mode off, leftover_check=true is refused with 400. The result is in "
                    + "X-Philter-Leftovers, or for a queued document on its status and download responses.";

    private static final String FAIRNESS_KEY_PARAMETER =
            "Optional, asynchronous requests only. Documents queued by one account with different fairness keys take "
                    + "turns, as different accounts do. The key is opaque to Philter: use an internal identifier, such as "
                    + "a user or group ID, and never personal data. 1 to 64 characters of letters, digits, '.', '_', ':' "
                    + "and '-'.";

    private final SigningService signingService;

    @Autowired
    public FilterApiController(final RedactionService redactionService, final PolicyDataService policyDataService, final ApiKeyDataService apiKeyDataService,
                               final AuditEventPublisher auditEventPublisher, final ApiKeyCache apiKeyCache,
                               final PendingDocumentDataService pendingDocumentDataService, final Gson gson,
                               final PolicyVersionDataService policyVersionDataService,
                               final SigningService signingService) {
        super(apiKeyDataService, apiKeyCache);
        this.redactionService = redactionService;
        this.policyDataService = policyDataService;
        this.policyVersionDataService = policyVersionDataService;
        this.auditEventPublisher = auditEventPublisher;
        this.pendingDocumentDataService = pendingDocumentDataService;
        this.gson = gson;
        this.signingService = signingService;
    }

    @Operation(
            summary = "Filter a PDF and receive a ZIP archive in return.",
            description = "Asynchronous by default. Returns 202 Accepted with `{\"documentId\":...}` and a `Location: /api/documents/{documentId}` header. " +
                    "Append `?async=false` to receive the redacted ZIP bytes inline (200 OK)."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Redacted ZIP bytes (synchronous path)."),
            @ApiResponse(responseCode = "202", description = "Job accepted; poll /api/documents/{documentId}/status and download from /api/documents/{documentId}."),
            @ApiResponse(responseCode = "404", description = "The named policy does not exist."),
            @ApiResponse(responseCode = "401", description = "Unauthorized.")
    })
    @RequiresScope(ApiKeyScope.REDACT)
    @RequestMapping(value = "/api/filter", method = RequestMethod.POST, produces = "application/zip", consumes = MediaType.APPLICATION_PDF_VALUE)
    public @ResponseBody ResponseEntity<byte[]> filterApplicationPdfAsApplicationZip(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            @RequestParam(value = "c", defaultValue = "") String context,
            @RequestParam(value = "p", defaultValue = "default") String policyName,
            @RequestParam(value = "async", defaultValue = "true") boolean async,
            @RequestParam(value = "filename", required = false) String filename,
            @RequestParam(value = "sign", defaultValue = "false") boolean sign,
            @Parameter(description = LEFTOVER_CHECK_PARAMETER)
            @RequestParam(value = "leftover_check", defaultValue = "false") boolean leftoverCheck,
            @Parameter(description = FAIRNESS_KEY_PARAMETER)
            @RequestParam(value = "fairness_key", required = false) String fairnessKey,
            final HttpServletRequest request,
            @RequestBody byte[] body) throws Exception {

        LOGGER.info("Received uploaded binary PDF file to be returned as ZIP. async={}", async);

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);

        if(apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        rejectSigningRequest(sign);
        rejectFairnessKey(async, fairnessKey);

        final String userId = apiKeyEntity.getUserId();

        // Read from the request rather than bound, since binding a single value to a list splits it at commas.
        final List<PagePolicyRequest> pagePolicies = PagePolicyRequest.parse(request.getParameterValues(PagePolicyRequest.PARAMETER));

        if (async) {
            return enqueueBinary(userId, body, MimeType.APPLICATION_PDF, "application/zip", policyName, context, filename,
                    fairnessKey, pagePolicies, leftoverCheck);
        }

        final RedactionOutcome outcome = pagePolicies.isEmpty()
                ? redactionService.filter(policyName, userId, context, body, MimeType.APPLICATION_PDF, filename, options(leftoverCheck))
                : redactionService.filter(policyName, userId, context, body, MimeType.APPLICATION_PDF, filename, pagePolicies,
                        options(leftoverCheck));
        final BinaryDocumentFilterResult binaryDocumentFilterResult = (BinaryDocumentFilterResult) outcome.result();

        return ResponseEntity.status(HttpStatus.OK)
                .headers(policyHeaders(outcome))
                .header(DOCUMENT_ID_HEADER, outcome.documentId())
                .body(ai.philterd.philter.services.filtering.BinaryOutput.encode(binaryDocumentFilterResult.getDocument(), "application/zip"));

    }

    @Operation(
            summary = "Filter a PDF and receive a redacted PDF in return.",
            description = "Asynchronous by default. Returns 202 Accepted with `{\"documentId\":...}` and a `Location: /api/documents/{documentId}` header. " +
                    "Append `?async=false` to receive the redacted PDF bytes inline (200 OK)."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Redacted PDF bytes (synchronous path)."),
            @ApiResponse(responseCode = "202", description = "Job accepted; poll /api/documents/{documentId}/status and download from /api/documents/{documentId}."),
            @ApiResponse(responseCode = "404", description = "The named policy does not exist."),
            @ApiResponse(responseCode = "401", description = "Unauthorized.")
    })
    @RequiresScope(ApiKeyScope.REDACT)
    @RequestMapping(value = "/api/filter", method = RequestMethod.POST, produces = MediaType.APPLICATION_PDF_VALUE, consumes = MediaType.APPLICATION_PDF_VALUE)
    public @ResponseBody ResponseEntity<byte[]> filterApplicationPdfAsApplicationPdf(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            @RequestParam(value = "c", defaultValue = "") String context,
            @RequestParam(value = "p", defaultValue = "default") String policyName,
            @RequestParam(value = "async", defaultValue = "true") boolean async,
            @RequestParam(value = "filename", required = false) String filename,
            @RequestParam(value = "sign", defaultValue = "false") boolean sign,
            @Parameter(description = LEFTOVER_CHECK_PARAMETER)
            @RequestParam(value = "leftover_check", defaultValue = "false") boolean leftoverCheck,
            @Parameter(description = FAIRNESS_KEY_PARAMETER)
            @RequestParam(value = "fairness_key", required = false) String fairnessKey,
            final HttpServletRequest request,
            @RequestBody byte[] body) throws Exception {

        LOGGER.info("Received uploaded binary PDF file to be returned as PDF. async={}", async);

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);

        if(apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        rejectSigningRequest(sign);
        rejectFairnessKey(async, fairnessKey);

        final String userId = apiKeyEntity.getUserId();

        // Read from the request rather than bound, since binding a single value to a list splits it at commas.
        final List<PagePolicyRequest> pagePolicies = PagePolicyRequest.parse(request.getParameterValues(PagePolicyRequest.PARAMETER));

        if (async) {
            return enqueueBinary(userId, body, MimeType.APPLICATION_PDF, MediaType.APPLICATION_PDF_VALUE, policyName, context, filename,
                    fairnessKey, pagePolicies, leftoverCheck);
        }

        final RedactionOutcome outcome = pagePolicies.isEmpty()
                ? redactionService.filter(policyName, userId, context, body, MimeType.APPLICATION_PDF, filename, options(leftoverCheck))
                : redactionService.filter(policyName, userId, context, body, MimeType.APPLICATION_PDF, filename, pagePolicies,
                        options(leftoverCheck));
        final BinaryDocumentFilterResult binaryDocumentFilterResult = (BinaryDocumentFilterResult) outcome.result();

        return ResponseEntity.status(HttpStatus.OK)
                .headers(policyHeaders(outcome))
                .header(DOCUMENT_ID_HEADER, outcome.documentId())
                .body(binaryDocumentFilterResult.getDocument());

    }

    /** Word documents. Spring's MediaType has no constant for it. */
    public static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    /** Excel workbooks. */
    public static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Operation(
            summary = "Filter a Word document and receive a redacted Word document in return.",
            description = "Asynchronous by default. Returns 202 Accepted with `{\"documentId\":...}` and a `Location: /api/documents/{documentId}` header. " +
                    "Append `?async=false` to receive the redacted document inline (200 OK)."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Redacted Word document (synchronous path)."),
            @ApiResponse(responseCode = "202", description = "Job accepted; poll /api/documents/{documentId}/status and download from /api/documents/{documentId}."),
            @ApiResponse(responseCode = "404", description = "The named policy does not exist."),
            @ApiResponse(responseCode = "401", description = "Unauthorized.")
    })
    @RequiresScope(ApiKeyScope.REDACT)
    @RequestMapping(value = "/api/filter", method = RequestMethod.POST, produces = DOCX, consumes = DOCX)
    public @ResponseBody ResponseEntity<byte[]> filterDocxAsDocx(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            @RequestParam(value = "c", defaultValue = "") String context,
            @RequestParam(value = "p", defaultValue = "default") String policyName,
            @RequestParam(value = "async", defaultValue = "true") boolean async,
            @RequestParam(value = "filename", required = false) String filename,
            @RequestParam(value = "sign", defaultValue = "false") boolean sign,
            @Parameter(description = LEFTOVER_CHECK_PARAMETER)
            @RequestParam(value = "leftover_check", defaultValue = "false") boolean leftoverCheck,
            @Parameter(description = FAIRNESS_KEY_PARAMETER)
            @RequestParam(value = "fairness_key", required = false) String fairnessKey,
            final HttpServletRequest request,
            @RequestBody byte[] body) throws Exception {

        LOGGER.info("Received uploaded Word document. async={}", async);
        return filterOffice(authorizationHeader, context, policyName, async, filename, sign, fairnessKey, request, body, MimeType.APPLICATION_DOCX, leftoverCheck);

    }

    @Operation(
            summary = "Filter an Excel workbook and receive a redacted Excel workbook in return.",
            description = "Asynchronous by default. Returns 202 Accepted with `{\"documentId\":...}` and a `Location: /api/documents/{documentId}` header. " +
                    "Append `?async=false` to receive the redacted workbook inline (200 OK)."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Redacted Excel workbook (synchronous path)."),
            @ApiResponse(responseCode = "202", description = "Job accepted; poll /api/documents/{documentId}/status and download from /api/documents/{documentId}."),
            @ApiResponse(responseCode = "404", description = "The named policy does not exist."),
            @ApiResponse(responseCode = "401", description = "Unauthorized.")
    })
    @RequiresScope(ApiKeyScope.REDACT)
    @RequestMapping(value = "/api/filter", method = RequestMethod.POST, produces = XLSX, consumes = XLSX)
    public @ResponseBody ResponseEntity<byte[]> filterXlsxAsXlsx(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            @RequestParam(value = "c", defaultValue = "") String context,
            @RequestParam(value = "p", defaultValue = "default") String policyName,
            @RequestParam(value = "async", defaultValue = "true") boolean async,
            @RequestParam(value = "filename", required = false) String filename,
            @RequestParam(value = "sign", defaultValue = "false") boolean sign,
            @Parameter(description = LEFTOVER_CHECK_PARAMETER)
            @RequestParam(value = "leftover_check", defaultValue = "false") boolean leftoverCheck,
            @Parameter(description = FAIRNESS_KEY_PARAMETER)
            @RequestParam(value = "fairness_key", required = false) String fairnessKey,
            final HttpServletRequest request,
            @RequestBody byte[] body) throws Exception {

        LOGGER.info("Received uploaded Excel workbook. async={}", async);
        return filterOffice(authorizationHeader, context, policyName, async, filename, sign, fairnessKey, request, body, MimeType.APPLICATION_XLSX, leftoverCheck);

    }

    /** MIME email messages (.eml), and the type every redacted email message is returned as. */
    public static final String EML = "message/rfc822";

    /** Outlook messages (.msg). */
    public static final String MSG = "application/vnd.ms-outlook";

    /** RTF documents, and the type every redacted RTF document is returned as. */
    public static final String RTF = "application/rtf";

    /** RTF documents, as some clients label them. */
    public static final String TEXT_RTF = "text/rtf";

    @Operation(
            summary = "Filter an RTF document and receive a redacted RTF document in return.",
            description = "Accepts application/rtf or text/rtf and returns application/rtf. The policy's config.word settings "
                    + "apply where RTF has the part. Asynchronous by default. Returns 202 Accepted with `{\"documentId\":...}` "
                    + "and a `Location: /api/documents/{documentId}` header. Append `?async=false` to receive the redacted "
                    + "document inline (200 OK)."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Redacted RTF document (synchronous path)."),
            @ApiResponse(responseCode = "202", description = "Job accepted; poll /api/documents/{documentId}/status and download from /api/documents/{documentId}."),
            @ApiResponse(responseCode = "404", description = "The named policy does not exist."),
            @ApiResponse(responseCode = "401", description = "Unauthorized.")
    })
    @RequiresScope(ApiKeyScope.REDACT)
    @RequestMapping(value = "/api/filter", method = RequestMethod.POST, produces = RTF, consumes = {RTF, TEXT_RTF})
    public @ResponseBody ResponseEntity<byte[]> filterRtfAsRtf(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            @RequestParam(value = "c", defaultValue = "") String context,
            @RequestParam(value = "p", defaultValue = "default") String policyName,
            @RequestParam(value = "async", defaultValue = "true") boolean async,
            @RequestParam(value = "filename", required = false) String filename,
            @RequestParam(value = "sign", defaultValue = "false") boolean sign,
            @Parameter(description = LEFTOVER_CHECK_PARAMETER)
            @RequestParam(value = "leftover_check", defaultValue = "false") boolean leftoverCheck,
            @Parameter(description = FAIRNESS_KEY_PARAMETER)
            @RequestParam(value = "fairness_key", required = false) String fairnessKey,
            final HttpServletRequest request,
            @RequestBody byte[] body) throws Exception {

        LOGGER.info("Received uploaded RTF document. async={}", async);
        return filterDocument(authorizationHeader, context, policyName, async, filename, sign, fairnessKey, request, body,
                MimeType.APPLICATION_RTF, RTF, leftoverCheck);

    }

    @Operation(
            summary = "Filter an email message (.eml) and receive a redacted email message in return.",
            description = "Asynchronous by default. Returns 202 Accepted with `{\"documentId\":...}` and a `Location: /api/documents/{documentId}` header. " +
                    "Append `?async=false` to receive the redacted message inline (200 OK)."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Redacted email message (synchronous path)."),
            @ApiResponse(responseCode = "202", description = "Job accepted; poll /api/documents/{documentId}/status and download from /api/documents/{documentId}."),
            @ApiResponse(responseCode = "404", description = "The named policy does not exist."),
            @ApiResponse(responseCode = "401", description = "Unauthorized.")
    })
    @RequiresScope(ApiKeyScope.REDACT)
    @RequestMapping(value = "/api/filter", method = RequestMethod.POST, produces = EML, consumes = EML)
    public @ResponseBody ResponseEntity<byte[]> filterEmlAsEml(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            @RequestParam(value = "c", defaultValue = "") String context,
            @RequestParam(value = "p", defaultValue = "default") String policyName,
            @RequestParam(value = "async", defaultValue = "true") boolean async,
            @RequestParam(value = "filename", required = false) String filename,
            @RequestParam(value = "sign", defaultValue = "false") boolean sign,
            @Parameter(description = LEFTOVER_CHECK_PARAMETER)
            @RequestParam(value = "leftover_check", defaultValue = "false") boolean leftoverCheck,
            @Parameter(description = FAIRNESS_KEY_PARAMETER)
            @RequestParam(value = "fairness_key", required = false) String fairnessKey,
            final HttpServletRequest request,
            @RequestBody byte[] body) throws Exception {

        LOGGER.info("Received uploaded email message. async={}", async);
        return filterDocument(authorizationHeader, context, policyName, async, filename, sign, fairnessKey, request, body,
                MimeType.MESSAGE_RFC822, EML, leftoverCheck);

    }

    @Operation(
            summary = "Filter an Outlook message (.msg) and receive a redacted email message (.eml) in return.",
            description = "An Outlook message cannot be written back in its own format, so it is returned as message/rfc822. "
                    + "Asynchronous by default. Returns 202 Accepted with `{\"documentId\":...}` and a `Location: /api/documents/{documentId}` header. " +
                    "Append `?async=false` to receive the redacted message inline (200 OK)."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Redacted email message, as message/rfc822 (synchronous path)."),
            @ApiResponse(responseCode = "202", description = "Job accepted; poll /api/documents/{documentId}/status and download from /api/documents/{documentId}."),
            @ApiResponse(responseCode = "404", description = "The named policy does not exist."),
            @ApiResponse(responseCode = "401", description = "Unauthorized.")
    })
    @RequiresScope(ApiKeyScope.REDACT)
    @RequestMapping(value = "/api/filter", method = RequestMethod.POST, produces = EML, consumes = MSG)
    public @ResponseBody ResponseEntity<byte[]> filterMsgAsEml(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            @RequestParam(value = "c", defaultValue = "") String context,
            @RequestParam(value = "p", defaultValue = "default") String policyName,
            @RequestParam(value = "async", defaultValue = "true") boolean async,
            @RequestParam(value = "filename", required = false) String filename,
            @RequestParam(value = "sign", defaultValue = "false") boolean sign,
            @Parameter(description = LEFTOVER_CHECK_PARAMETER)
            @RequestParam(value = "leftover_check", defaultValue = "false") boolean leftoverCheck,
            @Parameter(description = FAIRNESS_KEY_PARAMETER)
            @RequestParam(value = "fairness_key", required = false) String fairnessKey,
            final HttpServletRequest request,
            @RequestBody byte[] body) throws Exception {

        LOGGER.info("Received uploaded Outlook message. async={}", async);
        return filterDocument(authorizationHeader, context, policyName, async, filename, sign, fairnessKey, request, body,
                MimeType.APPLICATION_VND_MS_OUTLOOK, EML, leftoverCheck);

    }

    private ResponseEntity<byte[]> filterOffice(final String authorizationHeader, final String context, final String policyName,
                                                final boolean async, final String filename, final boolean sign,
                                                final String fairnessKey, final HttpServletRequest request,
                                                final byte[] body, final MimeType mimeType,
                                                final boolean leftoverCheck) throws Exception {
        return filterDocument(authorizationHeader, context, policyName, async, filename, sign, fairnessKey, request, body,
                mimeType, mimeType.getValue(), leftoverCheck);
    }

    /** A Word, Excel, or email document, returned as {@code outputMimeType}. */
    private ResponseEntity<byte[]> filterDocument(final String authorizationHeader, final String context, final String policyName,
                                                  final boolean async, final String filename, final boolean sign,
                                                  final String fairnessKey, final HttpServletRequest request,
                                                  final byte[] body, final MimeType mimeType,
                                                  final String outputMimeType, final boolean leftoverCheck) throws Exception {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);

        if(apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        rejectPagePolicies(request);

        rejectSigningRequest(sign);
        rejectFairnessKey(async, fairnessKey);

        final String userId = apiKeyEntity.getUserId();

        if (async) {
            return enqueueBinary(userId, body, mimeType, outputMimeType, policyName, context, filename, fairnessKey, List.of(),
                    leftoverCheck);
        }

        final RedactionOutcome outcome = redactionService.filter(policyName, userId, context, body, mimeType, filename,
                options(leftoverCheck));
        final BinaryDocumentFilterResult binaryDocumentFilterResult = (BinaryDocumentFilterResult) outcome.result();

        return ResponseEntity.status(HttpStatus.OK)
                .headers(policyHeaders(outcome))
                .header(DOCUMENT_ID_HEADER, outcome.documentId())
                .contentType(MediaType.parseMediaType(outputMimeType))
                .body(binaryDocumentFilterResult.getDocument());

    }

    @Operation(
            summary = "Filter plain text and receive redacted plain text inline.",
            description = "Synchronous. The `async` parameter does not apply to the text endpoint. "
                    + "When output signing is enabled in Admin Settings, the response includes an "
                    + "`X-Philter-Signature` header containing an ES256 JWT that binds the SHA-256 "
                    + "hash of the response body, the applied policy name and version, a per-response "
                    + "UUID, and an issue timestamp. Verify the signature using the public key from "
                    + "`GET /api/signing-key`. " + SIGN_PARAMETER
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Redacted plain text. Includes `X-Philter-Signature` JWT header when output signing is enabled or the request asked for it with sign=true."),
            @ApiResponse(responseCode = "401", description = "Unauthorized."),
            @ApiResponse(responseCode = "404", description = "The named policy does not exist."),
            @ApiResponse(responseCode = "500", description = "Signing is enabled but the signing operation failed.")
    })
    @RequiresScope(ApiKeyScope.REDACT)
    @RequestMapping(value = "/api/filter", method = RequestMethod.POST, produces = MediaType.TEXT_PLAIN_VALUE, consumes = MediaType.TEXT_PLAIN_VALUE)
    public @ResponseBody ResponseEntity<String> filterTextPlainAsTextPlain(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            @RequestParam(value = "c", defaultValue = "") String context,
            @RequestParam(value = "p", defaultValue = "default") String policyName,
            @RequestParam(value = "filename", required = false) String filename,
            @RequestParam(value = "sign", defaultValue = "false") boolean sign,
            @Parameter(description = LEFTOVER_CHECK_PARAMETER)
            @RequestParam(value = "leftover_check", defaultValue = "false") boolean leftoverCheck,
            final HttpServletRequest request,
            @RequestBody String body) throws Exception {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);

        if(apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        rejectPagePolicies(request);

        final String userId = apiKeyEntity.getUserId();

        final RedactionOutcome outcome = redactionService.filter(policyName, userId, context, body.getBytes(StandardCharsets.UTF_8),
                MimeType.TEXT_PLAIN, filename, options(leftoverCheck));
        final TextFilterResult textFilterResult = (TextFilterResult) outcome.result();

        // The id it was recorded under, so GET /api/ledger/{documentId} resolves it.
        final String documentId = outcome.documentId();
        final HttpHeaders headers = policyHeaders(outcome);
        headers.set(DOCUMENT_ID_HEADER, documentId);
        if (signingService.shouldSign(sign)) {
            headers.set(SigningService.SIGNATURE_HEADER, signingService.sign(
                    textFilterResult.getFilteredText(),
                    outcome.appliedPolicy().name(),
                    outcome.appliedPolicy().version(),
                    documentId));
        }

        return ResponseEntity.status(HttpStatus.OK)
                .headers(headers)
                .body(textFilterResult.getFilteredText());

    }

    /**
     * Document responses are not signed yet, so a request asking for a signature is
     * refused rather than answered with an unsigned response the caller believes is signed.
     */
    private static void rejectSigningRequest(final boolean sign) {
        if (sign) {
            throw new BadRequestException(
                    "PDF, Word, Excel, RTF, and email responses cannot be signed. Omit sign, or use the text/plain endpoint.");
        }
    }

    /** Page sets apply only to a PDF, so naming them for another format is refused rather than ignored. */
    private static void rejectPagePolicies(final HttpServletRequest request) {
        if (request.getParameterValues(PagePolicyRequest.PARAMETER) != null) {
            throw new BadRequestException(PagePolicyRequest.PARAMETER + " applies only to PDF documents.");
        }
    }

    /**
     * A fairness key orders queued documents, so one on a synchronous request, or one that is not a valid key, is
     * refused rather than ignored.
     */
    private static void rejectFairnessKey(final boolean async, final String fairnessKey) {
        if (fairnessKey == null) {
            return;
        }
        if (!async) {
            throw new BadRequestException("fairness_key applies only to asynchronous requests. Omit it, or omit async=false.");
        }
        if (!FairnessKeys.isValid(fairnessKey)) {
            throw new BadRequestException("fairness_key must be 1 to " + FairnessKeys.MAX_LENGTH
                    + " characters of letters, digits, '.', '_', ':' and '-'.");
        }
    }

    private ResponseEntity<byte[]> enqueueBinary(final String userId, final byte[] body, final MimeType inputMimeType,
                                                 final String outputMimeType, final String policyName, final String contextName,
                                                 final String filename, final String fairnessKey,
                                                 final List<PagePolicyRequest> pagePolicies,
                                                 final boolean leftoverCheck) throws PolicyNotFoundException {

        if (inputMimeType == MimeType.APPLICATION_PDF) {
            // Page sets are checked against the document here, so a mistake is refused rather than failing the job.
            PagePolicyRequest.checkPageCount(pagePolicies,
                    ai.philterd.philter.services.filtering.PdfInputValidator.validate(body));
        } else if (ai.philterd.philter.services.filtering.RtfInputValidator.isRtf(inputMimeType)) {
            ai.philterd.philter.services.filtering.RtfInputValidator.validate(body);
        } else if (ai.philterd.philter.services.filtering.EmailInputValidator.declaredFormat(inputMimeType) != null) {
            ai.philterd.philter.services.filtering.EmailInputValidator.validate(body,
                    ai.philterd.philter.services.filtering.EmailInputValidator.declaredFormat(inputMimeType));
        } else {
            final var officeType = ai.philterd.philter.services.filtering.OfficeInputValidator.declaredType(inputMimeType);
            ai.philterd.philter.services.filtering.OfficeInputValidator.validate(body, officeType);
        }

        final String documentId = UUID.randomUUID().toString();

        final PendingDocumentEntity entity = new PendingDocumentEntity();
        entity.setUserId(userId);
        entity.setDocumentId(documentId);
        entity.setFileName(filename);
        entity.setInputMimeType(inputMimeType.name());
        entity.setOutputMimeType(outputMimeType);
        entity.setPolicyName(policyName);
        entity.setContextName(contextName);
        entity.setFairnessKey(fairnessKey);
        entity.setClientIpAddress(ClientAddress.current());
        entity.setStatus(PendingDocumentEntity.STATUS_PENDING);
        entity.setInput(body);
        entity.setSubmittedAt(new Date());

        // Pin the policy version in force when the request is accepted, so the deferred redaction is
        // governed by the version the caller submitted against rather than whatever is current when the
        // worker later runs. We retain a snapshot of that content so the worker can redact with it.
        final PolicyEntity policyEntity = policyDataService.findOneOrManaged(policyName, userId);

        // As the synchronous path does, rather than failing later in the worker.
        if (policyEntity == null) {
            throw new PolicyNotFoundException("The policy '" + policyName + "' does not exist.");
        }

        final int policyVersion = policyEntity.getRevision();
        final String policyContentHash = policyVersionDataService.snapshot(policyEntity);
        entity.setPolicyVersion(policyVersion);
        entity.setPolicyContentHash(policyContentHash);

        // Each page set's policy is pinned the same way.
        final List<PagePolicyCapture> pageCaptures = new ArrayList<>();
        final List<AppliedPagePolicy> appliedPages = new ArrayList<>();
        for (final PagePolicyRequest pagePolicy : pagePolicies) {
            final PolicyEntity pageEntity = policyDataService.findOneOrManaged(pagePolicy.policyName(), userId);
            if (pageEntity == null) {
                throw new PolicyNotFoundException("The policy '" + pagePolicy.policyName() + "' does not exist.");
            }
            final String pageHash = policyVersionDataService.snapshot(pageEntity);
            pageCaptures.add(new PagePolicyCapture(pagePolicy.pages(), pageEntity, pageHash));
            appliedPages.add(new AppliedPagePolicy(pagePolicy.pages(),
                    new AppliedPolicy(pageEntity.getName(), pageEntity.getRevision(), pageHash)));
        }

        final String effectiveJson = redactionService.captureEffectiveConfiguration(userId, policyEntity, contextName, pageCaptures);
        entity.setEffectiveJson(effectiveJson);
        entity.setEffectiveHash(PolicyVersionDataService.contentHash(effectiveJson));
        // Refused now, against the captured policy, rather than failing the job later.
        redactionService.refuseLeftoverCheckIfOff(effectiveJson, options(leftoverCheck));
        entity.setLeftoverCheckRequested(leftoverCheck);
        // Drawn now, with the captured policy, so this response can report it; the worker's later draw is not used.
        final boolean reviewSampled = redactionService.selectForReview(effectiveJson);
        pendingDocumentDataService.save(entity);

        // Audit that a document was submitted for asynchronous redaction. The documentId is the
        // correlation id used by the /api/documents endpoints.
        auditEventPublisher.auditEvent(documentId, AuditLogEvent.DOCUMENT_REDACTION_INITIATED, userId, null, null,
                "inputMimeType: " + inputMimeType.name() + ", outputMimeType: " + outputMimeType
                        + ", policy: " + AuditDetails.quote(policyName) + ", policyVersion: " + policyVersion
                        + (appliedPages.isEmpty() ? "" : ", pagePolicies: " + AuditDetails.quote(PagePolicyRequest.describe(appliedPages))));
        if (reviewSampled) {
            redactionService.auditReviewSampled(userId, documentId, new AppliedPolicy(policyName, policyVersion, policyContentHash));
        }

        final String json = gson.toJson(Map.of("documentId", documentId));

        final HttpHeaders pageHeaders = new HttpHeaders();
        if (!appliedPages.isEmpty()) {
            pageHeaders.add(PAGE_POLICIES_HEADER, PagePolicyRequest.describe(appliedPages));
        }
        if (reviewSampled) {
            pageHeaders.set(REVIEW_HEADER, REVIEW_SAMPLED);
        }

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .headers(pageHeaders)
                .location(URI.create("/api/documents/" + documentId))
                .contentType(MediaType.APPLICATION_JSON)
                .header(POLICY_NAME_HEADER, policyName)
                .header("X-Effective-Configuration-SHA256", entity.getEffectiveHash())
                .header(POLICY_VERSION_HEADER, Integer.toString(policyVersion))
                .header(POLICY_HASH_HEADER, policyContentHash)
                .body(json.getBytes(StandardCharsets.UTF_8));

    }

    /**
     * As {@link #policyHeaders(AppliedPolicy)}, with the policy of each page set named with page_policy, and
     * {@link #REVIEW_HEADER} when the request was selected for review.
     */
    private static HttpHeaders policyHeaders(final RedactionOutcome outcome) {
        final HttpHeaders headers = policyHeaders(outcome.appliedPolicy());
        if (outcome.pagePolicies() != null && !outcome.pagePolicies().isEmpty()) {
            headers.add(PAGE_POLICIES_HEADER, PagePolicyRequest.describe(outcome.pagePolicies()));
        }
        addResultHeaders(headers, outcome);
        return headers;
    }

    /**
     * Adds what the redaction found beyond its output: {@link ResultHeaders#LEFTOVERS} when the leftover check ran,
     * {@link ResultHeaders#EMAIL_REPORT} for an email message, and {@link #REVIEW_HEADER} with each reason that
     * applies, such as {@code sampled, leftovers}.
     */
    static void addResultHeaders(final HttpHeaders headers, final RedactionOutcome outcome) {
        if (outcome.result() == null) {
            return;
        }
        final String leftovers = ResultHeaders.leftovers(outcome.result());
        if (leftovers != null) {
            headers.set(ResultHeaders.LEFTOVERS, leftovers);
        }
        final String emailReport = ResultHeaders.emailReport(outcome.result());
        if (emailReport != null) {
            headers.set(ResultHeaders.EMAIL_REPORT, emailReport);
        }
        final String review = ResultHeaders.review(outcome.result().isReviewSampled(), ResultHeaders.found(leftovers));
        if (review != null) {
            headers.set(REVIEW_HEADER, review);
        }
    }

    private static FilterOptions options(final boolean leftoverCheck) {
        return FilterOptions.defaults().withLeftoverCheck(leftoverCheck);
    }

    /** Builds the response headers that report the applied policy name, version, and content hash. */
    private static HttpHeaders policyHeaders(final AppliedPolicy appliedPolicy) {
        final HttpHeaders headers = new HttpHeaders();
        headers.add(POLICY_NAME_HEADER, appliedPolicy.name());
        headers.add(POLICY_VERSION_HEADER, Integer.toString(appliedPolicy.version()));
        if (appliedPolicy.contentHash() != null) {
            headers.add(POLICY_HASH_HEADER, appliedPolicy.contentHash());
        }
        return headers;
    }

}

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
import ai.philterd.philter.api.responses.AuditEventView;
import ai.philterd.philter.api.responses.GenericResponse;
import ai.philterd.philter.api.responses.GetAuditResponse;
import ai.philterd.philter.api.security.RequiresScope;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.audit.AuditLogService;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.ApiKeyScope;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.services.cache.ApiKeyCache;
import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Read access to the audit log over HTTP, so it can reach a SIEM or an auditor without a database
 * connection. The log spans the deployment, so reading it needs an administrator as well as
 * {@code audit:read}; {@code owner} is a filter, not a boundary.
 */
@Tag(name = "Audit Log", description = "Read-only access to the audit log.")
@Controller
public class AuditApiController extends AbstractApiController {

    private final AuditLogService auditLogService;
    private final UserService userService;
    private final AuditEventPublisher auditEventPublisher;
    private final Gson gson;

    public AuditApiController(final AuditLogService auditLogService,
                              final UserService userService,
                              final ApiKeyDataService apiKeyDataService,
                              final AuditEventPublisher auditEventPublisher,
                              final ApiKeyCache apiKeyCache,
                              final Gson gson) {
        super(apiKeyDataService, apiKeyCache);
        this.auditLogService = auditLogService;
        this.userService = userService;
        this.auditEventPublisher = auditEventPublisher;
        this.gson = gson;
    }

    @Operation(summary = "List audit events.",
            description = "Returns audit events, most recent first, paginated. Filter by event type with event, "
                    + "by time with from and to (ISO-8601 instants; from is inclusive, to is exclusive), and by "
                    + "acting principal with owner. Reading the audit log is itself audited. The log covers the "
                    + "whole deployment, so this requires an administrator as well as the audit:read scope.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "A page of audit events and the total matching the filters."),
            @ApiResponse(responseCode = "400", description = "A timestamp could not be parsed, the range is reversed, or the event type is not one Philter emits."),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "403", description = "The key does not hold audit:read, or the caller is not an administrator."),
            @ApiResponse(responseCode = "404", description = "The owner does not exist, or the caller may not reach it. The API does not distinguish the two, so an owner value cannot be used to discover accounts.")
    })
    @RequiresScope(ApiKeyScope.AUDIT_READ)
    @RequestMapping(value = "/api/audit", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getAuditLog(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestParam(value = "event", required = false) String event,
            final @RequestParam(value = "from", required = false) String from,
            final @RequestParam(value = "to", required = false) String to,
            final @RequestParam(value = "owner", required = false) String owner,
            final @RequestParam(value = "offset", defaultValue = "0") int offset,
            final @RequestParam(value = "limit", defaultValue = "25") int limit,
            final @RequestAttribute("requestId") String requestId,
            final HttpServletRequest httpServletRequest) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);
        if (apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ResponseEntity<GenericResponse> refusal =
                authorizeAdminOnly(userService, apiKeyEntity.getUserId(), "Reading the audit log");
        if (refusal != null) {
            return new ResponseEntity<>(gson.toJson(refusal.getBody()), refusal.getStatusCode());
        }

        // Resolved by the usual rules, so an unreachable owner is a 404, not an empty page.
        ObjectId principalId = null;
        if (owner != null && !owner.isBlank()) {
            principalId = resolveTargetUserId(userService, apiKeyEntity.getUserId(), owner);
            if (principalId == null) {
                return new ResponseEntity<>(HttpStatus.NOT_FOUND);
            }
        }

        final String eventFilter = normalizeEvent(event);
        final Date fromInclusive = parseTimestamp(from, "from");
        final Date toExclusive = parseTimestamp(to, "to");

        if (fromInclusive != null && toExclusive != null && fromInclusive.after(toExclusive)) {
            throw new BadRequestException("The from timestamp must be before the to timestamp.");
        }

        final int pageOffset = normalizeOffset(offset);
        final int pageLimit = normalizeLimit(limit);

        final List<Document> documents =
                auditLogService.find(principalId, eventFilter, fromInclusive, toExclusive, pageOffset, pageLimit);

        final List<AuditEventView> events = new ArrayList<>(documents.size());
        for (final Document document : documents) {
            events.add(toView(document));
        }

        final long total = auditLogService.count(principalId, eventFilter, fromInclusive, toExclusive);

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.AUDIT_LOG_RETRIEVED, apiKeyEntity.getUserId(), null,
                getClientIpAddress(httpServletRequest),
                "event: " + (eventFilter == null ? "any" : eventFilter)
                        + ", owner: " + (principalId == null ? "any" : principalId)
                        + ", returned: " + events.size() + ", total: " + total);

        return new ResponseEntity<>(gson.toJson(new GetAuditResponse(events, total)), HttpStatus.OK);

    }

    /** Rejects an unknown event name: an empty page would read as "this never happened". */
    private static String normalizeEvent(final String event) {

        if (event == null || event.isBlank()) {
            return null;
        }

        for (final AuditLogEvent value : AuditLogEvent.values()) {
            if (value.getAuditLogEvent().equalsIgnoreCase(event.trim())) {
                return value.getAuditLogEvent();
            }
        }

        throw new BadRequestException("Not an audit event type: " + event);

    }

    private static Date parseTimestamp(final String value, final String parameter) {

        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return Date.from(Instant.parse(value.trim()));
        } catch (final DateTimeException ex) {
            throw new BadRequestException("The " + parameter
                    + " parameter must be an ISO-8601 instant, such as 2026-09-01T00:00:00Z.");
        }

    }

    private static AuditEventView toView(final Document document) {
        return new AuditEventView(
                document.getDate("timestamp"),
                document.getString("event"),
                document.getString("request_id"),
                asString(document.get("api_key_id")),
                asString(document.get("associated_object")),
                document.getString("client_ip_address"),
                document.getString("details"));
    }

    private static String asString(final Object value) {
        return value == null ? null : value.toString();
    }

}

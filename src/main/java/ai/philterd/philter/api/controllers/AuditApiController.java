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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
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

        // Named, not echoed, matching how a bad parameter value is reported elsewhere.
        throw new BadRequestException("The event parameter is not an audit event type.");

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

    /** Response header saying how many events the export holds. */
    public static final String EXPORT_ROWS_HEADER = "X-Philter-Export-Rows";

    /** Response header saying whether the row cap cut the export short. */
    public static final String EXPORT_TRUNCATED_HEADER = "X-Philter-Export-Truncated";

    /** Events per export page when the caller gives no limit. */
    public static final int EXPORT_DEFAULT_LIMIT = 100;

    /** The most events one export page may hold. */
    public static final int EXPORT_MAX_LIMIT = 1000;

    /** Response header giving the offset of the next page, present only when the export was truncated. */
    public static final String EXPORT_NEXT_OFFSET_HEADER = "X-Philter-Export-Next-Offset";

    /** Response header naming the time zone the from and to dates were read in. */
    public static final String EXPORT_TIME_ZONE_HEADER = "X-Philter-Export-Time-Zone";

    @Operation(summary = "Export the audit log as CSV.",
            description = "Returns the audit log for a range of whole days as CSV, most recent first. from and to are dates (YYYY-MM-DD), both inclusive, read in "
                    + "zone (an IANA time zone such as UTC or America/New_York), or in the server's time zone when "
                    + "zone is omitted. to may be at most " + AuditLogService.MAX_EXPORT_WINDOW_DAYS
                    + " days after from. Returns up to limit events (default " + EXPORT_DEFAULT_LIMIT + ", at most "
                    + EXPORT_MAX_LIMIT + "; a larger value is treated as the maximum, and zero or less as the default), starting "
                    + "offset events into the range; the " + EXPORT_TRUNCATED_HEADER + " header is true when more remain, and "
                    + EXPORT_NEXT_OFFSET_HEADER + " gives the offset to request next. Events are ordered by timestamp "
                    + "and then id, so paging a range that is no longer receiving events returns each exactly once; a "
                    + "range that includes the current day can repeat events across pages. Timestamps in the CSV are UTC. "
                    + "The export is itself audited. Requires an administrator as well as the audit:read scope.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The CSV. Headers give the row count, whether it was truncated, and the time zone used."),
            @ApiResponse(responseCode = "400", description = "A date is missing or not YYYY-MM-DD, the range is reversed or longer than the maximum, the zone is not a time zone, offset is negative or not a number, or limit is not a number. The body says which."),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "403", description = "The key does not hold audit:read, or the caller is not an administrator.")
    })
    @RequiresScope(ApiKeyScope.AUDIT_READ)
    @RequestMapping(value = "/api/audit/export", method = RequestMethod.GET, produces = "text/csv")
    public ResponseEntity<Object> exportAuditLog(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestParam(value = "from", required = false) String from,
            final @RequestParam(value = "to", required = false) String to,
            final @RequestParam(value = "zone", required = false) String zone,
            final @RequestParam(value = "offset", defaultValue = "0") int offset,
            final @RequestParam(value = "limit", defaultValue = "" + EXPORT_DEFAULT_LIMIT) int limit,
            final @RequestAttribute("requestId") String requestId,
            final HttpServletRequest httpServletRequest) {

        final ApiKeyEntity apiKeyEntity = requireApiKey(authorizationHeader);

        final ResponseEntity<GenericResponse> refusal =
                authorizeAdminOnly(userService, apiKeyEntity.getUserId(), "Exporting the audit log");
        if (refusal != null) {
            return ResponseEntity.status(refusal.getStatusCode()).body(refusal.getBody().getMessage());
        }

        final LocalDate fromDate = parseDate(from, "from");
        final LocalDate toDate = parseDate(to, "to");

        final AuditLogService.CsvExport export;
        try {
            export = auditLogService.export(fromDate, toDate, parseZone(zone), offset, exportLimit(limit));
        } catch (final IllegalArgumentException ex) {
            throw new BadRequestException(ex.getMessage());
        }

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.AUDIT_LOG_EXPORTED, apiKeyEntity.getUserId(), null,
                getClientIpAddress(httpServletRequest),
                "from: " + fromDate + ", to: " + toDate + ", zone: " + export.zone().getId()
                        + ", offset: " + export.offset() + ", limit: " + exportLimit(limit) + ", rows: " + export.rows() + ", truncated: " + export.truncated()
                        + ", api_key: " + apiKeyEntity.getId());

        final ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (export.truncated()) {
            response.header(EXPORT_NEXT_OFFSET_HEADER, String.valueOf(export.nextOffset()));
        }

        return response
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit-" + fromDate + "-to-" + toDate + ".csv\"")
                .header(EXPORT_ROWS_HEADER, String.valueOf(export.rows()))
                .header(EXPORT_TRUNCATED_HEADER, String.valueOf(export.truncated()))
                .header(EXPORT_TIME_ZONE_HEADER, export.zone().getId())
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(export.csv());

    }

    /** Clamps a page size as the other listings do: the default when not positive, the maximum above it. */
    private static int exportLimit(final int limit) {
        return limit <= 0 ? EXPORT_DEFAULT_LIMIT : Math.min(limit, EXPORT_MAX_LIMIT);
    }

    private static LocalDate parseDate(final String value, final String name) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(name + " is required, as a date (YYYY-MM-DD).");
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (final DateTimeParseException ex) {
            throw new BadRequestException(name + " must be a date (YYYY-MM-DD): '" + value + "'.");
        }
    }

    /** The named zone, or {@code null} for the server's. */
    private static ZoneId parseZone(final String zone) {
        if (zone == null || zone.isBlank()) {
            return null;
        }
        try {
            return ZoneId.of(zone.trim());
        } catch (final DateTimeException ex) {
            throw new BadRequestException("zone must be a time zone, such as UTC or America/New_York: '" + zone + "'.");
        }
    }

}

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
import ai.philterd.philter.api.responses.GetPiiCountsResponse;
import ai.philterd.philter.api.responses.PiiCountView;
import ai.philterd.philter.api.security.RequiresScope;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.AdminSettingsEntity;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.services.AdminSettingsDataService;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.Listings;
import ai.philterd.philter.data.services.PiiCountDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.ApiKeyScope;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.services.cache.ApiKeyCache;
import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
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

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

@Tag(name = "PII Counts", description = "Read the PII type counts Philter records for Philter Diffuse. Requires an administrator.")
@Controller
public class PiiCountsApiController extends AbstractApiController {

    private final PiiCountDataService piiCountDataService;
    private final AdminSettingsDataService adminSettingsDataService;
    private final UserService userService;
    private final AuditEventPublisher auditEventPublisher;
    private final Gson gson;

    public PiiCountsApiController(final PiiCountDataService piiCountDataService,
                                  final AdminSettingsDataService adminSettingsDataService,
                                  final UserService userService,
                                  final ApiKeyDataService apiKeyDataService,
                                  final AuditEventPublisher auditEventPublisher,
                                  final ApiKeyCache apiKeyCache,
                                  final Gson gson) {
        super(apiKeyDataService, apiKeyCache);
        this.piiCountDataService = piiCountDataService;
        this.adminSettingsDataService = adminSettingsDataService;
        this.userService = userService;
        this.auditEventPublisher = auditEventPublisher;
        this.gson = gson;
    }

    @Operation(summary = "List the recorded PII counts.",
            description = "Returns the PII type counts Philter records when the diffuseCountsEnabled setting is on, "
                    + "for Philter Diffuse to privatize: one entry per context and UTC day, giving for each PII type "
                    + "how many redactions found at least one of it, and how many found any PII. Only counts, never "
                    + "any PII. Most recent day first, then by context, paginated. Narrow by day with from and to "
                    + "(YYYY-MM-DD, UTC, both inclusive) and by context with context. recordingEnabled says whether "
                    + "counts are being recorded now, so an empty listing can be told apart from recording being "
                    + "off; counts recorded before it was turned off are still returned. Each read is audited. The "
                    + "counts cover the whole deployment, so this requires an administrator as well as the "
                    + "pii-counts:read scope.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "A page of counts, the total matching the filters, and whether counts are being recorded.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GetPiiCountsResponse.class))),
            @ApiResponse(responseCode = "400", description = "A date is not YYYY-MM-DD, or from is after to.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class))),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "403", description = "The key does not hold pii-counts:read, or the caller is not an administrator.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class)))
    })
    @RequiresScope(ApiKeyScope.PII_COUNTS_READ)
    @RequestMapping(value = "/api/pii-counts", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getPiiCounts(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestParam(value = "from", required = false) String from,
            final @RequestParam(value = "to", required = false) String to,
            final @RequestParam(value = "context", required = false) String context,
            final @RequestParam(value = "offset", defaultValue = "0") int offset,
            final @RequestParam(value = "limit", defaultValue = "25") int limit,
            final @RequestAttribute("requestId") String requestId,
            final HttpServletRequest httpServletRequest) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);
        if (apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ResponseEntity<GenericResponse> refusal =
                authorizeAdminOnly(userService, apiKeyEntity.getUserId(), "Reading the PII counts");
        if (refusal != null) {
            return new ResponseEntity<>(gson.toJson(refusal.getBody()), refusal.getStatusCode());
        }

        final LocalDate fromDay = parseDay(from, "from");
        final LocalDate toDay = parseDay(to, "to");
        if (fromDay != null && toDay != null && fromDay.isAfter(toDay)) {
            throw new BadRequestException("The from date must not be after the to date.");
        }
        final String contextFilter = context == null || context.isBlank() ? null : context.trim();

        final Listings.Page<PiiCountDataService.Aggregate> page = piiCountDataService.list(fromDay, toDay,
                contextFilter, normalizeOffset(offset), normalizeLimit(limit));

        final List<PiiCountView> counts = new ArrayList<>(page.items().size());
        for (final PiiCountDataService.Aggregate aggregate : page.items()) {
            counts.add(new PiiCountView(aggregate.context(), aggregate.day().toString(), aggregate.counts(),
                    aggregate.totalDocuments(), aggregate.updatedAt()));
        }

        final AdminSettingsEntity settings = adminSettingsDataService.findAdminSettings();
        final boolean recordingEnabled = settings != null && settings.isDiffuseCountsEnabled();

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.PII_COUNTS_RETRIEVED, apiKeyEntity.getUserId(), null,
                getClientIpAddress(httpServletRequest),
                "from: " + (fromDay == null ? "any" : fromDay) + ", to: " + (toDay == null ? "any" : toDay)
                        + ", context: " + (contextFilter == null ? "any" : contextFilter)
                        + ", returned: " + counts.size() + ", total: " + page.total());

        return new ResponseEntity<>(gson.toJson(new GetPiiCountsResponse(recordingEnabled, counts, page.total())),
                HttpStatus.OK);

    }

    private static LocalDate parseDay(final String value, final String parameter) {

        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return LocalDate.parse(value.trim());
        } catch (final DateTimeParseException ex) {
            throw new BadRequestException("The " + parameter + " parameter must be a date (YYYY-MM-DD), such as 2026-09-01.");
        }

    }

}

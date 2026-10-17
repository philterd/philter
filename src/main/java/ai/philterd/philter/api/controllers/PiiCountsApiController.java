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
import ai.philterd.philter.api.exceptions.BadRequestException;
import ai.philterd.philter.api.exceptions.NotFoundException;
import ai.philterd.philter.api.exceptions.UnauthorizedException;
import ai.philterd.philter.api.responses.GenericResponse;
import ai.philterd.philter.api.responses.GetPiiCountsResponse;
import ai.philterd.philter.api.responses.PiiCountView;
import ai.philterd.philter.api.security.RequiresScope;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.AdminSettingsEntity;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.services.AdminSettingsDataService;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.Listings;
import ai.philterd.philter.data.services.PiiCountDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.ApiKeyScope;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.services.cache.ApiKeyCache;
import ai.philterd.philter.services.encryption.EncryptionService;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /** sort: day (default, most recent first), context, or documents (how many redactions found any PII). */
    private static final Map<String, String> COUNT_SORT = sortFields("day", "bucket_start", "context", "context",
            "documents", "total_documents");

    @Operation(summary = "List the recorded PII counts.",
            description = "Returns the PII type counts Philter records when the diffuseCountsEnabled setting is on, "
                    + "for Philter Diffuse to privatize: one entry per user's context and UTC day, with a stable id, giving "
                    + "for each PII type how many redactions found at least one of it, and how many found any PII. Only "
                    + "counts, never any PII. Sorted with sort (day, the default, context, or documents) and order (asc or desc); "
                    + "the default is the most recent day first. Paginated. Another user's context name and username "
                    + "are returned only with ADMIN_CROSS_USER_ACCESS_ENABLED; their counts always are. Narrow by day "
                    + "with from and to (YYYY-MM-DD, UTC, both inclusive), by context name with context (only the "
                    + "caller's own without cross-user access), and by username with owner. recordingEnabled says whether "
                    + "counts are being recorded now, so an empty listing can be told apart from recording being "
                    + "off; counts recorded before it was turned off are still returned. Each read is audited. The "
                    + "counts cover the whole deployment, so this requires an administrator as well as the "
                    + "pii-counts:read scope.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "A page of counts, the total matching the filters, and whether counts are being recorded.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GetPiiCountsResponse.class))),
            @ApiResponse(responseCode = "400", description = "A date is not YYYY-MM-DD or is outside the years 1 to 9999, or from is after to.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class))),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "403", description = "The key does not hold pii-counts:read, or the caller is not an administrator.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class))),
            @ApiResponse(responseCode = "404", description = "owner names no user, or another user without ADMIN_CROSS_USER_ACCESS_ENABLED.",
                    content = @Content)
    })
    @RequiresScope(ApiKeyScope.PII_COUNTS_READ)
    @RequestMapping(value = "/api/pii-counts", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getPiiCounts(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestParam(value = "from", required = false) String from,
            final @RequestParam(value = "to", required = false) String to,
            final @RequestParam(value = "context", required = false) String context,
            final @RequestParam(value = "owner", required = false) String owner,
            final @RequestParam(value = "sort", required = false) String sort,
            final @RequestParam(value = "order", required = false) String order,
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
        final Listings.Sort countSort = listingSort(sort, order, COUNT_SORT, "day", true);

        // Every user's counts are listed, for the deployment-wide totals Philter Diffuse needs, but another user's
        // context names and username only with cross-user access. Without it, naming a context or another user
        // narrows to the caller's own, so the listing cannot be used to learn which contexts other users have.
        final String callerId = apiKeyEntity.getUserId();
        final boolean crossUser = isCrossUserAccessEnabled();
        String userFilter = null;
        if (owner != null && !owner.isBlank()) {
            userFilter = resolveTargetUserId(userService, callerId, owner);
            if (userFilter == null) {
                throw new NotFoundException();
            }
        } else if (contextFilter != null && !crossUser) {
            userFilter = callerId;
        }

        final Listings.Page<PiiCountDataService.Aggregate> page = piiCountDataService.list(fromDay, toDay,
                userFilter, contextFilter, countSort, normalizeOffset(offset), normalizeLimit(limit));

        final Map<String, String> usernames = new HashMap<>();
        boolean namedOtherUsers = false;
        final List<PiiCountView> counts = new ArrayList<>(page.items().size());
        for (final PiiCountDataService.Aggregate aggregate : page.items()) {
            final boolean own = callerId.equals(aggregate.userId());
            final boolean named = own || crossUser;
            namedOtherUsers |= named && !own;
            final String username = !named ? null : usernames.computeIfAbsent(aggregate.userId(), id -> {
                final UserEntity user = userService.findOneById(id);
                return user == null ? null : user.getUsername();
            });
            counts.add(new PiiCountView(bucketId(aggregate), username, named ? aggregate.context() : null,
                    aggregate.day().toString(), aggregate.counts(), aggregate.totalDocuments(), aggregate.updatedAt()));
        }
        if (namedOtherUsers) {
            auditAllUsersListing(auditEventPublisher, requestId, callerId, "list PII counts");
        }

        final AdminSettingsEntity settings = adminSettingsDataService.findAdminSettings();
        final boolean recordingEnabled = settings != null && settings.isDiffuseCountsEnabled();

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.PII_COUNTS_RETRIEVED, apiKeyEntity.getUserId(), null,
                getClientIpAddress(httpServletRequest),
                "from: " + (fromDay == null ? "any" : fromDay) + ", to: " + (toDay == null ? "any" : toDay)
                        // Left out when not filtered: a context can be named "any", so that cannot mean every context.
                        + (contextFilter == null ? "" : ", context: " + AuditDetails.quote(contextFilter))
                        + ", owner: " + (userFilter == null ? "any" : userFilter)
                        + ", returned: " + counts.size() + ", total: " + page.total());

        return new ResponseEntity<>(gson.toJson(new GetPiiCountsResponse(recordingEnabled, counts, page.total())),
                HttpStatus.OK);

    }

    /**
     * A stable identifier for one user's context and day, which tells buckets apart, such as two users' contexts of one
     * name, without naming either. A hash, so it reveals neither the user nor the context.
     */
    static String bucketId(final PiiCountDataService.Aggregate aggregate) {
        return EncryptionService.hashSha256(aggregate.userId() + "\n" + aggregate.context() + "\n" + aggregate.day())
                .substring(0, 32);
    }

    private static LocalDate parseDay(final String value, final String parameter) {

        if (value == null || value.isBlank()) {
            return null;
        }

        final LocalDate day;
        try {
            day = LocalDate.parse(value.trim());
        } catch (final DateTimeParseException ex) {
            throw new BadRequestException("The " + parameter + " parameter must be a date (YYYY-MM-DD), such as 2026-09-01.");
        }
        // The parser also takes signed years far outside what storage can hold, such as +999999999-12-31.
        if (day.getYear() < 1 || day.getYear() > 9999) {
            throw new BadRequestException("The " + parameter + " parameter must be a date (YYYY-MM-DD) from year 1 "
                    + "to 9999, such as 2026-09-01.");
        }
        return day;

    }

}

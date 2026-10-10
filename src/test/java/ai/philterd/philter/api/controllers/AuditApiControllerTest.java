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

import ai.philterd.philter.api.exceptions.RestApiExceptions;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.audit.AuditLogService;
import ai.philterd.philter.config.AdminAccessConfig;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.services.cache.ApiKeyCache;
import ai.philterd.philter.services.encryption.EncryptionService;
import com.google.gson.Gson;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AuditApiControllerTest {

    private static final String API_KEY = "sk_abcdefghijklmnopqrstuvwxyz012345";
    private static final String API_KEY_HASH = EncryptionService.hashSha256(API_KEY);
    private static final String AUTH_HEADER = "Bearer " + API_KEY;

    @Mock private AuditLogService auditLogService;
    @Mock private UserService userService;
    @Mock private ApiKeyDataService apiKeyDataService;
    @Mock private AuditEventPublisher auditEventPublisher;
    @Mock private ApiKeyCache apiKeyCache;

    private ObjectId userId;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        userId = new ObjectId();
        final ApiKeyEntity apiKeyEntity = new ApiKeyEntity();
        apiKeyEntity.setUserId(userId);
        apiKeyEntity.setId(new ObjectId());

        // Keyed by the hash, as production keys it. See LedgerApiControllerTest.
        lenient().when(apiKeyCache.containsApiKey(API_KEY_HASH)).thenReturn(true);
        lenient().when(apiKeyCache.get(API_KEY_HASH)).thenReturn(apiKeyEntity);

        AdminAccessConfig.setOverrideForTesting(true);

        final AuditApiController controller = new AuditApiController(
                auditLogService, userService, apiKeyDataService, auditEventPublisher, apiKeyCache, new Gson());

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new RestApiExceptions())
                .build();
    }

    @AfterEach
    void clearAdminAccessOverride() {
        AdminAccessConfig.setOverrideForTesting(null);
    }

    // ----- helpers -----

    private void makeCallerAdmin() {
        final UserEntity admin = new UserEntity();
        admin.setId(userId);
        admin.setRole("admin");
        when(userService.findOneById(userId)).thenReturn(admin);
    }

    private void makeCallerRegularUser() {
        final UserEntity user = new UserEntity();
        user.setId(userId);
        user.setRole("user");
        when(userService.findOneById(userId)).thenReturn(user);
    }

    private void makeOwnerLookup(final String email, final ObjectId ownerId) {
        final UserEntity owner = new UserEntity();
        owner.setId(ownerId);
        owner.setEmail(email);
        when(userService.findAnyByUsername(email)).thenReturn(owner);
    }

    private static Document auditEvent(final String event) {
        return new Document("timestamp", new Date())
                .append("event", event)
                .append("request_id", "req-1")
                .append("api_key_id", new ObjectId())
                .append("associated_object", new ObjectId())
                .append("client_ip_address", "203.0.113.7")
                .append("details", "policy: p");
    }

    private org.springframework.test.web.servlet.ResultActions perform(final String url) throws Exception {
        return mockMvc.perform(get(url).header("Authorization", AUTH_HEADER).requestAttr("requestId", "req-1"));
    }

    // ----- authentication and authorization -----

    @Test
    void returns401WithNoAuthHeader() throws Exception {
        mockMvc.perform(get("/api/audit").requestAttr("requestId", "req-1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returns403ForANonAdministrator() throws Exception {
        makeCallerRegularUser();

        final String body = perform("/api/audit")
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("administrator"), "the refusal should say what is required: " + body);
        verify(auditLogService, never()).find(any(), any(), any(), any(), anyInt(), anyInt(), anyBoolean());
    }

    @Test
    void aNonAdministratorReadsNothingEvenWhenNamingAnOwner() throws Exception {
        makeCallerRegularUser();

        perform("/api/audit?owner=someone@example.com").andExpect(status().isForbidden());

        verify(auditLogService, never()).find(any(), any(), any(), any(), anyInt(), anyInt(), anyBoolean());
    }

    // ----- listing -----

    @Test
    void returnsEventsAndTheTotal() throws Exception {
        makeCallerAdmin();
        when(auditLogService.find(isNull(), isNull(), isNull(), isNull(), eq(0), eq(25), anyBoolean()))
                .thenReturn(List.of(auditEvent("policy_deleted")));
        when(auditLogService.count(isNull(), isNull(), isNull(), isNull())).thenReturn(7L);

        final String body = perform("/api/audit")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("\"event\":\"policy_deleted\""), body);
        assertTrue(body.contains("\"total\":7"), body);
        assertTrue(body.contains("\"clientIpAddress\":\"203.0.113.7\""), body);
    }

    @Test
    void pagesWithOffsetAndLimitAndClampsThem() throws Exception {
        makeCallerAdmin();
        when(auditLogService.find(any(), any(), any(), any(), eq(0), eq(100), anyBoolean())).thenReturn(List.of());

        perform("/api/audit?offset=-5&limit=5000").andExpect(status().isOk());

        verify(auditLogService).find(isNull(), isNull(), isNull(), isNull(), eq(0), eq(100), eq(true));
    }

    @Test
    void filtersByEventType() throws Exception {
        makeCallerAdmin();
        when(auditLogService.find(any(), eq("policy_deleted"), any(), any(), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(List.of(auditEvent("policy_deleted")));

        perform("/api/audit?event=policy_deleted").andExpect(status().isOk());

        verify(auditLogService).find(isNull(), eq("policy_deleted"), isNull(), isNull(), eq(0), eq(25), eq(true));
    }

    @Test
    void filtersByTimeRangeWithAnExclusiveUpperBound() throws Exception {
        makeCallerAdmin();
        when(auditLogService.find(any(), any(), any(), any(), anyInt(), anyInt(), anyBoolean())).thenReturn(List.of());

        perform("/api/audit?from=2026-09-01T00:00:00Z&to=2026-09-02T00:00:00Z").andExpect(status().isOk());

        final ArgumentCaptor<Date> from = ArgumentCaptor.forClass(Date.class);
        final ArgumentCaptor<Date> to = ArgumentCaptor.forClass(Date.class);
        verify(auditLogService).find(isNull(), isNull(), from.capture(), to.capture(), anyInt(), anyInt(), eq(true));

        assertEquals("2026-09-01T00:00:00Z", from.getValue().toInstant().toString());
        assertEquals("2026-09-02T00:00:00Z", to.getValue().toInstant().toString());
    }

    @Test
    void filtersByOwner() throws Exception {
        makeCallerAdmin();
        final ObjectId otherUser = new ObjectId();
        makeOwnerLookup("other@example.com", otherUser);
        when(auditLogService.find(eq(otherUser), any(), any(), any(), anyInt(), anyInt(), anyBoolean())).thenReturn(List.of());

        perform("/api/audit?owner=other@example.com").andExpect(status().isOk());

        verify(auditLogService).find(eq(otherUser), isNull(), isNull(), isNull(), eq(0), eq(25), eq(true));
    }

    // ----- rejected input -----

    @Test
    void returns404ForAnOwnerThatDoesNotExist() throws Exception {
        makeCallerAdmin();
        when(userService.findAnyByUsername("nobody@example.com")).thenReturn(null);

        perform("/api/audit?owner=nobody@example.com").andExpect(status().isNotFound());

        verify(auditLogService, never()).find(any(), any(), any(), any(), anyInt(), anyInt(), anyBoolean());
    }

    @Test
    void returns400ForAnEventTypePhilterDoesNotEmit() throws Exception {
        makeCallerAdmin();

        final String body = perform("/api/audit?event=not_an_event")
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("event parameter"), "the error must say which parameter: " + body);
        verify(auditLogService, never()).find(any(), any(), any(), any(), anyInt(), anyInt(), anyBoolean());
    }

    @Test
    void returns400ForATimestampThatIsNotAnInstant() throws Exception {
        makeCallerAdmin();

        perform("/api/audit?from=2026-09-01").andExpect(status().isBadRequest());

        verify(auditLogService, never()).find(any(), any(), any(), any(), anyInt(), anyInt(), anyBoolean());
    }

    @Test
    void returns400WhenTheRangeIsReversed() throws Exception {
        makeCallerAdmin();

        perform("/api/audit?from=2026-09-02T00:00:00Z&to=2026-09-01T00:00:00Z")
                .andExpect(status().isBadRequest());

        verify(auditLogService, never()).find(any(), any(), any(), any(), anyInt(), anyInt(), anyBoolean());
    }

    // ----- reading the log is itself audited -----

    @Test
    void readingTheLogRecordsAnAuditEventNamingTheReader() throws Exception {
        makeCallerAdmin();
        when(auditLogService.find(any(), any(), any(), any(), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(List.of(auditEvent("policy_deleted")));
        when(auditLogService.count(any(), any(), any(), any())).thenReturn(1L);

        perform("/api/audit?event=policy_deleted").andExpect(status().isOk());

        verify(auditEventPublisher).auditEvent(
                eq("req-1"), eq(AuditLogEvent.AUDIT_LOG_RETRIEVED), eq(userId), isNull(),
                anyString(), contains("event: policy_deleted"));
    }

    @Test
    void aRefusedReadIsNotAudited() throws Exception {
        makeCallerRegularUser();

        perform("/api/audit").andExpect(status().isForbidden());

        verify(auditEventPublisher, never()).auditEvent(
                anyString(), any(), any(), any(), anyString(), anyString());
    }

    @Test
    void totalIsReportedAsALong() throws Exception {
        makeCallerAdmin();
        when(auditLogService.find(any(), any(), any(), any(), anyInt(), anyInt(), anyBoolean())).thenReturn(List.of());
        when(auditLogService.count(any(), any(), any(), any())).thenReturn(5_000_000_000L);

        final String body = perform("/api/audit")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("\"total\":5000000000"), body);
    }

    // ----- CSV export -----

    private static AuditLogService.CsvExport csv(final int rows, final boolean truncated, final String zone) {
        return new AuditLogService.CsvExport("timestamp,event\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                rows, truncated, java.time.ZoneId.of(zone), 0);
    }

    @Test
    @DisplayName("An administrator exports CSV, with the row count, truncation, and zone in headers, and it is audited")
    void exportsCsv() throws Exception {
        makeCallerAdmin();
        when(auditLogService.export(java.time.LocalDate.parse("2026-10-01"), java.time.LocalDate.parse("2026-10-05"),
                java.time.ZoneId.of("America/New_York"), 0, 100)).thenReturn(csv(42, true, "America/New_York"));

        final var response = perform("/api/audit/export?from=2026-10-01&to=2026-10-05&zone=America/New_York")
                .andExpect(status().isOk())
                .andReturn().getResponse();

        assertTrue(response.getContentType().startsWith("text/csv"), response.getContentType());
        assertEquals("timestamp,event\n", response.getContentAsString());
        assertEquals("42", response.getHeader(AuditApiController.EXPORT_ROWS_HEADER));
        assertEquals("true", response.getHeader(AuditApiController.EXPORT_TRUNCATED_HEADER));
        assertEquals("America/New_York", response.getHeader(AuditApiController.EXPORT_TIME_ZONE_HEADER));
        assertTrue(response.getHeader("Content-Disposition").contains("audit-2026-10-01-to-2026-10-05.csv"));

        verify(auditEventPublisher).auditEvent(eq("req-1"), eq(AuditLogEvent.AUDIT_LOG_EXPORTED), eq(userId), isNull(),
                any(), contains("rows: 42, truncated: true"));
    }

    @Test
    @DisplayName("Without a zone the service is asked for the server's, and the header names it")
    void exportDefaultsTheZone() throws Exception {
        makeCallerAdmin();
        when(auditLogService.export(any(), any(), isNull(), eq(0), eq(100))).thenReturn(csv(0, false, "UTC"));

        final var response = perform("/api/audit/export?from=2026-10-01&to=2026-10-01")
                .andExpect(status().isOk()).andReturn().getResponse();

        assertEquals("false", response.getHeader(AuditApiController.EXPORT_TRUNCATED_HEADER));
        assertEquals("UTC", response.getHeader(AuditApiController.EXPORT_TIME_ZONE_HEADER));
    }

    @Test
    @DisplayName("A non-administrator cannot export, and nothing is read or audited")
    void exportRefusesANonAdministrator() throws Exception {
        makeCallerRegularUser();

        perform("/api/audit/export?from=2026-10-01&to=2026-10-01").andExpect(status().isForbidden());

        verify(auditLogService, never()).export(any(), any(), any(), anyInt(), anyInt());
        verify(auditEventPublisher, never()).auditEvent(any(), eq(AuditLogEvent.AUDIT_LOG_EXPORTED), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Missing or malformed dates and unknown zones are a 400 with the reason")
    void exportRejectsBadParameters() throws Exception {
        makeCallerAdmin();

        assertTrue(perform("/api/audit/export?to=2026-10-01").andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString().contains("from is required"));
        assertTrue(perform("/api/audit/export?from=10/01/2026&to=2026-10-01").andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString().contains("YYYY-MM-DD"));
        assertTrue(perform("/api/audit/export?from=2026-10-01&to=2026-10-01&zone=Mars/Olympus").andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString().contains("time zone"));

        verify(auditLogService, never()).export(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("The service's own range validation comes back as a 400 with its message")
    void exportReportsTheServicesRangeValidation() throws Exception {
        makeCallerAdmin();
        when(auditLogService.export(any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new IllegalArgumentException("The date range cannot exceed 30 days."));

        final String body = perform("/api/audit/export?from=2026-01-01&to=2026-10-01")
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("cannot exceed 30 days"), body);
        verify(auditEventPublisher, never()).auditEvent(any(), eq(AuditLogEvent.AUDIT_LOG_EXPORTED), any(), any(), any(), any());
    }

    @Test
    @DisplayName("A truncated export says where the next page starts; a complete one does not")
    void exportGivesTheNextOffset() throws Exception {
        makeCallerAdmin();
        when(auditLogService.export(any(), any(), any(), eq(200), anyInt())).thenReturn(new AuditLogService.CsvExport(
                "timestamp,event\n".getBytes(java.nio.charset.StandardCharsets.UTF_8), 100, true, java.time.ZoneId.of("UTC"), 200));
        when(auditLogService.export(any(), any(), any(), eq(300), anyInt())).thenReturn(new AuditLogService.CsvExport(
                "timestamp,event\n".getBytes(java.nio.charset.StandardCharsets.UTF_8), 7, false, java.time.ZoneId.of("UTC"), 300));

        final var truncated = perform("/api/audit/export?from=2026-10-01&to=2026-10-01&offset=200")
                .andExpect(status().isOk()).andReturn().getResponse();
        assertEquals("300", truncated.getHeader(AuditApiController.EXPORT_NEXT_OFFSET_HEADER));
        verify(auditEventPublisher).auditEvent(eq("req-1"), eq(AuditLogEvent.AUDIT_LOG_EXPORTED), eq(userId), isNull(),
                any(), contains("offset: 200, limit: 100, rows: 100, truncated: true"));

        final var last = perform("/api/audit/export?from=2026-10-01&to=2026-10-01&offset=300")
                .andExpect(status().isOk()).andReturn().getResponse();
        assertNull(last.getHeader(AuditApiController.EXPORT_NEXT_OFFSET_HEADER));
    }

    @Test
    @DisplayName("A page read by cursor continues from it and gives the next cursor, not an offset")
    void exportPagesByCursor() throws Exception {
        makeCallerAdmin();
        final AuditLogService.ExportCursor cursor =
                new AuditLogService.ExportCursor(new java.util.Date(1_791_000_000_000L), new org.bson.types.ObjectId());
        when(auditLogService.export(any(), any(), any(), eq(cursor), eq(100))).thenReturn(new AuditLogService.CsvExport(
                "timestamp,event\n".getBytes(java.nio.charset.StandardCharsets.UTF_8), 100, true, java.time.ZoneId.of("UTC"), 0,
                "next-cursor"));

        final var response = perform("/api/audit/export?from=2026-10-01&to=2026-10-01&cursor=" + cursor.encode())
                .andExpect(status().isOk()).andReturn().getResponse();

        assertEquals("next-cursor", response.getHeader(AuditApiController.EXPORT_NEXT_CURSOR_HEADER));
        assertNull(response.getHeader(AuditApiController.EXPORT_NEXT_OFFSET_HEADER));
        verify(auditLogService, never()).export(any(), any(), any(), anyInt(), anyInt());
        verify(auditEventPublisher).auditEvent(eq("req-1"), eq(AuditLogEvent.AUDIT_LOG_EXPORTED), eq(userId), isNull(),
                any(), contains("cursor: " + cursor.encode() + ", limit: 100, rows: 100, truncated: true"));
    }

    @Test
    @DisplayName("A page read by offset gives both the next offset and the next cursor")
    void exportByOffsetAlsoGivesTheNextCursor() throws Exception {
        makeCallerAdmin();
        when(auditLogService.export(any(), any(), any(), eq(0), anyInt())).thenReturn(new AuditLogService.CsvExport(
                "timestamp,event\n".getBytes(java.nio.charset.StandardCharsets.UTF_8), 100, true, java.time.ZoneId.of("UTC"), 0,
                "next-cursor"));

        final var response = perform("/api/audit/export?from=2026-10-01&to=2026-10-01")
                .andExpect(status().isOk()).andReturn().getResponse();

        assertEquals("next-cursor", response.getHeader(AuditApiController.EXPORT_NEXT_CURSOR_HEADER));
        assertEquals("100", response.getHeader(AuditApiController.EXPORT_NEXT_OFFSET_HEADER));
    }

    @Test
    @DisplayName("A cursor that is not one, or a cursor with an offset, is a 400 naming the cursor")
    void exportRejectsABadCursor() throws Exception {
        makeCallerAdmin();
        final String cursor = new AuditLogService.ExportCursor(new java.util.Date(), new org.bson.types.ObjectId()).encode();

        for (final String query : new String[]{"&cursor=not-a-cursor", "&cursor=" + cursor + "&offset=0"}) {
            final String body = perform("/api/audit/export?from=2026-10-01&to=2026-10-01" + query)
                    .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
            assertTrue(body.contains("\"field\":\"cursor\""), body);
        }

        verify(auditLogService, never()).export(any(), any(), any(), anyInt(), anyInt());
        verify(auditLogService, never()).export(any(), any(), any(), any(AuditLogService.ExportCursor.class), anyInt());
    }

    @Test
    @DisplayName("A negative or non-numeric offset is a 400")
    void exportRejectsABadOffset() throws Exception {
        makeCallerAdmin();
        when(auditLogService.export(any(), any(), any(), eq(-1), anyInt()))
                .thenThrow(new IllegalArgumentException("offset must be zero or greater."));

        assertTrue(perform("/api/audit/export?from=2026-10-01&to=2026-10-01&offset=-1").andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString().contains("offset must be zero or greater"));
        assertTrue(perform("/api/audit/export?from=2026-10-01&to=2026-10-01&offset=ten").andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString().contains("'offset' has an invalid value"));
    }

    @Test
    @DisplayName("limit defaults to 100, is capped at 1000, and a non-positive value means the default")
    void exportClampsTheLimit() throws Exception {
        makeCallerAdmin();
        when(auditLogService.export(any(), any(), any(), anyInt(), anyInt())).thenReturn(csv(0, false, "UTC"));

        for (final String[] call : new String[][]{{"", "100"}, {"&limit=250", "250"}, {"&limit=5000", "1000"},
                {"&limit=0", "100"}, {"&limit=-3", "100"}}) {
            perform("/api/audit/export?from=2026-10-01&to=2026-10-01" + call[0]).andExpect(status().isOk());
            verify(auditLogService).export(any(), any(), any(), eq(0), eq(Integer.parseInt(call[1])));
            org.mockito.Mockito.clearInvocations(auditLogService);
        }

        assertTrue(perform("/api/audit/export?from=2026-10-01&to=2026-10-01&limit=many").andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString().contains("'limit' has an invalid value"));
    }

}

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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
        when(userService.findByUsername(email)).thenReturn(owner);
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
        verify(auditLogService, never()).find(any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void aNonAdministratorReadsNothingEvenWhenNamingAnOwner() throws Exception {
        makeCallerRegularUser();

        perform("/api/audit?owner=someone@example.com").andExpect(status().isForbidden());

        verify(auditLogService, never()).find(any(), any(), any(), any(), anyInt(), anyInt());
    }

    // ----- listing -----

    @Test
    void returnsEventsAndTheTotal() throws Exception {
        makeCallerAdmin();
        when(auditLogService.find(isNull(), isNull(), isNull(), isNull(), eq(0), eq(25)))
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
        when(auditLogService.find(any(), any(), any(), any(), eq(0), eq(100))).thenReturn(List.of());

        perform("/api/audit?offset=-5&limit=5000").andExpect(status().isOk());

        verify(auditLogService).find(isNull(), isNull(), isNull(), isNull(), eq(0), eq(100));
    }

    @Test
    void filtersByEventType() throws Exception {
        makeCallerAdmin();
        when(auditLogService.find(any(), eq("policy_deleted"), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(auditEvent("policy_deleted")));

        perform("/api/audit?event=policy_deleted").andExpect(status().isOk());

        verify(auditLogService).find(isNull(), eq("policy_deleted"), isNull(), isNull(), eq(0), eq(25));
    }

    @Test
    void filtersByTimeRangeWithAnExclusiveUpperBound() throws Exception {
        makeCallerAdmin();
        when(auditLogService.find(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(List.of());

        perform("/api/audit?from=2026-09-01T00:00:00Z&to=2026-09-02T00:00:00Z").andExpect(status().isOk());

        final ArgumentCaptor<Date> from = ArgumentCaptor.forClass(Date.class);
        final ArgumentCaptor<Date> to = ArgumentCaptor.forClass(Date.class);
        verify(auditLogService).find(isNull(), isNull(), from.capture(), to.capture(), anyInt(), anyInt());

        assertEquals("2026-09-01T00:00:00Z", from.getValue().toInstant().toString());
        assertEquals("2026-09-02T00:00:00Z", to.getValue().toInstant().toString());
    }

    @Test
    void filtersByOwner() throws Exception {
        makeCallerAdmin();
        final ObjectId otherUser = new ObjectId();
        makeOwnerLookup("other@example.com", otherUser);
        when(auditLogService.find(eq(otherUser), any(), any(), any(), anyInt(), anyInt())).thenReturn(List.of());

        perform("/api/audit?owner=other@example.com").andExpect(status().isOk());

        verify(auditLogService).find(eq(otherUser), isNull(), isNull(), isNull(), eq(0), eq(25));
    }

    // ----- rejected input -----

    @Test
    void returns404ForAnOwnerThatDoesNotExist() throws Exception {
        makeCallerAdmin();
        when(userService.findByUsername("nobody@example.com")).thenReturn(null);

        perform("/api/audit?owner=nobody@example.com").andExpect(status().isNotFound());

        verify(auditLogService, never()).find(any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void returns400ForAnEventTypePhilterDoesNotEmit() throws Exception {
        makeCallerAdmin();

        final String body = perform("/api/audit?event=not_an_event")
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("event parameter"), "the error must say which parameter: " + body);
        verify(auditLogService, never()).find(any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void returns400ForATimestampThatIsNotAnInstant() throws Exception {
        makeCallerAdmin();

        perform("/api/audit?from=2026-09-01").andExpect(status().isBadRequest());

        verify(auditLogService, never()).find(any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void returns400WhenTheRangeIsReversed() throws Exception {
        makeCallerAdmin();

        perform("/api/audit?from=2026-09-02T00:00:00Z&to=2026-09-01T00:00:00Z")
                .andExpect(status().isBadRequest());

        verify(auditLogService, never()).find(any(), any(), any(), any(), anyInt(), anyInt());
    }

    // ----- reading the log is itself audited -----

    @Test
    void readingTheLogRecordsAnAuditEventNamingTheReader() throws Exception {
        makeCallerAdmin();
        when(auditLogService.find(any(), any(), any(), any(), anyInt(), anyInt()))
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
        when(auditLogService.find(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(List.of());
        when(auditLogService.count(any(), any(), any(), any())).thenReturn(5_000_000_000L);

        final String body = perform("/api/audit")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("\"total\":5000000000"), body);
    }

    // ----- CSV export -----

    private static AuditLogService.CsvExport csv(final int rows, final boolean truncated, final String zone) {
        return new AuditLogService.CsvExport("timestamp,event\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                rows, truncated, java.time.ZoneId.of(zone));
    }

    @Test
    @DisplayName("An administrator exports CSV, with the row count, truncation, and zone in headers, and it is audited")
    void exportsCsv() throws Exception {
        makeCallerAdmin();
        when(auditLogService.export(java.time.LocalDate.parse("2026-10-01"), java.time.LocalDate.parse("2026-10-05"),
                java.time.ZoneId.of("America/New_York"))).thenReturn(csv(42, true, "America/New_York"));

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
        when(auditLogService.export(any(), any(), isNull())).thenReturn(csv(0, false, "UTC"));

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

        verify(auditLogService, never()).export(any(), any(), any());
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

        verify(auditLogService, never()).export(any(), any(), any());
    }

    @Test
    @DisplayName("The service's own range validation comes back as a 400 with its message")
    void exportReportsTheServicesRangeValidation() throws Exception {
        makeCallerAdmin();
        when(auditLogService.export(any(), any(), any()))
                .thenThrow(new IllegalArgumentException("The date range cannot exceed 30 days."));

        final String body = perform("/api/audit/export?from=2026-01-01&to=2026-10-01")
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("cannot exceed 30 days"), body);
        verify(auditEventPublisher, never()).auditEvent(any(), eq(AuditLogEvent.AUDIT_LOG_EXPORTED), any(), any(), any(), any());
    }

}

package ai.philterd.philter.data.services;

import ai.philterd.philter.api.security.DashboardPrincipal;
import ai.philterd.philter.api.security.DashboardSessionFilter;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.model.Source;
import ai.philterd.philter.testutil.AbstractMongoIT;
import ai.philterd.philter.testutil.TestEncryptionService;
import com.google.gson.Gson;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RemainingP1IT extends AbstractMongoIT {
    private com.mongodb.client.MongoDatabase isolated;
    @org.junit.jupiter.api.BeforeEach void realMongoWhenRequested() {
        final String uri = System.getProperty("philter.test.mongoUri");
        if (uri != null) {
            mongoClient.close();
            mongoClient = spy(com.mongodb.client.MongoClients.create(uri));
            isolated = mongoClient.getDatabase("philter_p1_races_" + new ObjectId());
            doReturn(isolated).when(mongoClient).getDatabase("philter");
        }
    }
    @AfterEach void cleanRealDatabase() { if (isolated != null) isolated.drop(); }
    private final AuditEventPublisher audit = mock(AuditEventPublisher.class);
    private UserService users() { return new UserService(mongoClient, new TestEncryptionService(), audit); }
    private UserEntity account(UserService users) {
        UserEntity user = new UserEntity();
        user.setUsername("operator"); user.setPassword("unused"); user.setRole("admin");
        user.setFpeKey("0123456789abcdef0123456789abcdef");
        user.setId(users.save(user));
        return user;
    }
    private void authenticate(UserEntity user) {
        final var principal = new DashboardPrincipal(user, false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
    @AfterEach void clearAuthentication() {
        SecurityContextHolder.clearContext();
        com.vaadin.flow.server.VaadinSession.setCurrent(null);
    }

    @Test void staleSettingsCannotRestorePasswordRoleOrMfaState() {
        UserService service = users();
        UserEntity stale = account(service);
        service.enableMfa("req", service.findOneById(stale.getId()), "NEWSECRET", "system");
        service.setUserRole("req", service.findOneById(stale.getId()), "user", "system");
        service.changePassword("req", stale, "a sufficiently long password", "system");
        UserEntity current = service.findOneById(stale.getId());
        assertEquals("user", current.getRole()); assertTrue(current.isMfaEnabled());
        assertEquals("NEWSECRET", current.getMfaSecret());
        assertEquals(3L, current.getSecurityVersion());
        assertThrows(UnsupportedOperationException.class, () -> service.update(stale));
        authenticate(current);
        var mfaSession = mock(com.vaadin.flow.server.VaadinSession.class);
        when(mfaSession.getAttribute(ai.philterd.philter.views.MfaChallengeView.MFA_SATISFIED_ATTRIBUTE))
                .thenReturn(current.getId() + ":" + current.getSecurityVersion());
        com.vaadin.flow.server.VaadinSession.setCurrent(mfaSession);
        stale.setWebhookUrl("https://example.com/events"); stale.setWebhookSecret("webhook secret");
        service.updateWebhook(stale);
        current = service.findOneById(stale.getId());
        assertEquals("user", current.getRole()); assertTrue(current.isMfaEnabled());
        assertEquals("NEWSECRET", current.getMfaSecret());
        assertTrue(service.passwordMatches(current, "a sufficiently long password"));
    }

    @Test void alreadyOpenRpcIsRejectedAfterDemotionEvenAfterRePromotion() throws Exception {
        UserService service = users(); UserEntity before = account(service); authenticate(before);
        service.setUserRole("req", before, "user", "system");
        service.setUserRole("req", before, "admin", "system");
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> service.setUserRole("req", before, "admin", "webui"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin");
        request.setParameter("v-r", "uidl");
        final var session = (org.springframework.mock.web.MockHttpSession) request.getSession();
        MockHttpServletResponse response = new MockHttpServletResponse();
        new DashboardSessionFilter(users()).doFilter(request, response, (req, res) -> fail("Revoked RPC reached dashboard"));
        assertEquals(401, response.getStatus()); assertTrue(session.isInvalid());
    }

    @Test void enrollmentRevokesPreviouslyUnenrolledSessionAndOldCodeCannotSatisfyNewEpoch() throws Exception {
        UserService service = users(); UserEntity before = account(service); authenticate(before);
        service.enableMfa("req", before, "FIRST", "system");
        UserEntity firstEnrollment = service.findOneById(before.getId());
        service.disableMfa("req", firstEnrollment, "system");
        service.enableMfa("req", firstEnrollment, "SECOND", "system");
        assertFalse(service.recordAcceptedMfaTimeStep(firstEnrollment, 100));
        MockHttpServletResponse response = new MockHttpServletResponse();
        new DashboardSessionFilter(users()).doFilter(new MockHttpServletRequest("POST", "/account"), response,
                (req, res) -> fail("Old session survived enrollment"));
        assertEquals(401, response.getStatus());
    }

    @Test void currentSessionRequestStillReachesDashboard() throws Exception {
        UserService service = users(); authenticate(account(service));
        final boolean[] invoked = {false};
        new DashboardSessionFilter(users()).doFilter(new MockHttpServletRequest("POST", "/account"),
                new MockHttpServletResponse(), (req, res) -> invoked[0] = true);
        assertTrue(invoked[0]);
    }

    private PolicyDataService policies(PolicyVersionDataService versions) {
        return new PolicyDataService(mongoClient, audit, new Gson(), versions,
                new ai.philterd.philter.services.cache.RedactionCache());
    }
    private static String json(String value) { return "{\"identifiers\":{\"ssn\":{}},\"name\":\"" + value + "\"}"; }

    @Test void nameReuseKeepsBothGoverningContentsAndMonotonicRevision() {
        var versions = new PolicyVersionDataService(mongoClient, audit); var service = policies(versions);
        ObjectId owner = new ObjectId();
        assertTrue(service.create("req", owner, json("old"), "", "", "reused", "test").isSuccessful());
        var old = service.findOne("reused", owner);
        assertTrue(service.deleteByName("req", "reused", owner, Source.API).isSuccessful());
        assertTrue(service.create("req", owner, json("new"), "", "", "reused", "test").isSuccessful());
        var latest = service.findOne("reused", owner);
        assertTrue(latest.getRevision() > old.getRevision());
        assertEquals(json("old"), versions.findByContentHash(PolicyVersionDataService.contentHash(old.getPolicy())).getPolicy());
        assertEquals(json("new"), versions.findByContentHash(PolicyVersionDataService.contentHash(latest.getPolicy())).getPolicy());
    }

    @Test void competingEditsPublishOnlyOneHeadAndRetainItsExactContent() throws Exception {
        var versions = new PolicyVersionDataService(mongoClient, audit); var service = policies(versions);
        ObjectId owner = new ObjectId(); service.create("req", owner, json("base"), "", "", "race", "test");
        var initial = service.findOne("race", owner);
        var bothRead = new CountDownLatch(2); var release = new CountDownLatch(1);
        var first = spy(policies(versions)); var second = spy(policies(versions));
        for (var instance : java.util.List.of(first, second)) {
            doAnswer(call -> { var result = call.callRealMethod(); bothRead.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS)); return result; })
                    .when(instance).findOneById(initial.getId(), owner);
        }
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> first.update("a", owner, initial.getId(), json("a"), "", "", "test"));
            var b = executor.submit(() -> second.update("b", owner, initial.getId(), json("b"), "", "", "test"));
            try { assertTrue(bothRead.await(5, TimeUnit.SECONDS)); } finally { release.countDown(); }
            ServiceResponse ar = a.get(10, TimeUnit.SECONDS), br = b.get(10, TimeUnit.SECONDS);
            assertNotEquals(ar.isSuccessful(), br.isSuccessful());
            assertEquals(409, ar.isSuccessful() ? br.getStatusCode() : ar.getStatusCode());
        }
        var head = service.findOne("race", owner);
        assertEquals(head.getPolicy(), versions.findByContentHash(PolicyVersionDataService.contentHash(head.getPolicy())).getPolicy());
    }

    @Test void failedSnapshotPreventsPublishingLiveUpdate() {
        var versions = spy(new PolicyVersionDataService(mongoClient, audit)); var service = policies(versions);
        ObjectId owner = new ObjectId(); service.create("req", owner, json("base"), "", "", "policy", "test");
        var initial = service.findOne("policy", owner);
        doThrow(new IllegalStateException("storage unavailable")).when(versions).snapshot(any());
        assertThrows(IllegalStateException.class, () -> service.update("req", owner, initial.getId(), json("new"), "", "", "test"));
        assertEquals(json("base"), service.findOne("policy", owner).getPolicy());
    }
    @Test void scopeChangeLosingRaceWithRevocationCannotRestoreKey() throws Exception {
        var cache = new ai.philterd.philter.services.cache.ApiKeyCache("", 0, "", false);
        var revoker = new ApiKeyDataService(mongoClient, audit, cache);
        var editor = spy(new ApiKeyDataService(mongoClient, audit, cache));
        var owner = new ObjectId();
        var key = revoker.findOneByApiKey(revoker.createApiKey("req", owner, "test").getMessage());
        var read = new CountDownLatch(1); var resume = new CountDownLatch(1);
        doAnswer(call -> { var result = call.callRealMethod(); read.countDown();
            assertTrue(resume.await(5, TimeUnit.SECONDS)); return result; }).when(editor).findOneById(key.getId());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = executor.submit(() -> editor.updateScopes("req", owner, key, java.util.Set.of("redact"), "test"));
            try {
                assertTrue(read.await(5, TimeUnit.SECONDS));
                assertTrue(revoker.deleteByApiKey("req", owner, key, "test").isSuccessful());
            } finally { resume.countDown(); }
            assertFalse(pending.get(5, TimeUnit.SECONDS).isSuccessful());
        }
        assertTrue(mongoClient.getDatabase("philter").getCollection("api_keys")
                .find(new Document("_id", key.getId())).first().getBoolean("deleted"));
        assertNull(revoker.findOneByApiKey("not-a-key"));
    }

    @Test void staleAdminCannotChangeGlobalSettingsAtServiceBoundary() {
        UserService users = users(); var admin = account(users); authenticate(admin);
        users.setUserRole("req", admin, "user", "system");
        var settings = new AdminSettingsDataService(mongoClient, new TestEncryptionService(), audit);
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> settings.saveMfaEnabled(admin.getId(), false));
    }
    @Test void passwordAuthenticationAloneCannotMutateAnMfaEnrolledAccount() {
        var service = users(); var account = account(service);
        service.enableMfa("req", account, "SECRET", "system");
        account = service.findOneById(account.getId()); authenticate(account);
        final var target = account;
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> service.disableMfa("req", target, "webui"));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> new AdminSettingsDataService(mongoClient, new TestEncryptionService(), audit)
                        .saveMfaEnabled(target.getId(), false));
    }
}

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

import ai.philterd.phileas.policy.Crypto;
import ai.philterd.phileas.policy.FPE;
import ai.philterd.phileas.utils.Encryption;
import ai.philterd.philter.api.exceptions.RestApiExceptions;
import ai.philterd.philter.api.requests.ReidentifyRequest;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.config.AdminAccessConfig;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.entities.PolicyEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.services.cache.ApiKeyCache;
import ai.philterd.philter.services.encryption.EncryptionService;
import com.google.gson.Gson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReidentifyApiControllerTest {

    // 32-byte (256-bit) AES key in hex — used for CRYPTO_REPLACE tests.
    private static final String CRYPTO_HEX_KEY = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";

    // 32-byte FPE key in hex — used for FPE_ENCRYPT_REPLACE tests.
    private static final String FPE_HEX_KEY = "ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100";

    private static final String API_KEY = "sk_abcdefghijklmnopqrstuvwxyz012345";
    private static final String AUTH_HEADER = "Bearer " + API_KEY;
    private static final String UNKNOWN_AUTH_HEADER = "Bearer sk_unknownunknownunknownunknown00";

    @Mock private UserService userService;
    @Mock private PolicyDataService policyDataService;
    @Mock private ApiKeyDataService apiKeyDataService;
    @Mock private AuditEventPublisher auditEventPublisher;
    @Mock private ApiKeyCache apiKeyCache;

    private ObjectId userId;
    private MockMvc mockMvc;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        userId = new ObjectId();

        final ApiKeyEntity apiKeyEntity = new ApiKeyEntity();
        apiKeyEntity.setUserId(userId);
        apiKeyEntity.setId(new ObjectId());

        when(apiKeyCache.containsApiKey(API_KEY)).thenReturn(false);
        when(apiKeyDataService.findOneByApiKey(API_KEY)).thenReturn(apiKeyEntity);

        AdminAccessConfig.setOverrideForTesting(true);

        final ReidentifyApiController controller = new ReidentifyApiController(
                userService, policyDataService, apiKeyDataService, auditEventPublisher, apiKeyCache, gson);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new RestApiExceptions())
                .build();
    }

    @AfterEach
    void clearAdminOverride() {
        AdminAccessConfig.setOverrideForTesting(null);
    }

    // ----- Input validation -----

    @Test
    void missingAuthorizationHeaderReturns401() throws Exception {
        mockMvc.perform(post("/api/reidentify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":[\"x\"],\"strategy\":\"CRYPTO_REPLACE\",\"policyName\":\"p\",\"reason\":\"r\"}")
                        .requestAttr("requestId", "req-no-auth"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownApiKeyReturns401() throws Exception {
        mockMvc.perform(post("/api/reidentify")
                        .header("Authorization", UNKNOWN_AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":[\"x\"],\"strategy\":\"CRYPTO_REPLACE\",\"policyName\":\"p\",\"reason\":\"r\"}")
                        .requestAttr("requestId", "req-bad-key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void emptyValuesListReturns400() throws Exception {
        final ReidentifyRequest req = new ReidentifyRequest();
        req.setValues(List.of());
        req.setStrategy("CRYPTO_REPLACE");
        req.setPolicyName("p");
        req.setReason("r");

        final String body = mockMvc.perform(post("/api/reidentify")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gson.toJson(req))
                        .requestAttr("requestId", "req-empty-values"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("values"));
    }

    @Test
    void missingReasonReturns400() throws Exception {
        final ReidentifyRequest req = new ReidentifyRequest();
        req.setValues(List.of("someValue"));
        req.setStrategy("CRYPTO_REPLACE");
        req.setPolicyName("p");

        final String body = mockMvc.perform(post("/api/reidentify")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gson.toJson(req))
                        .requestAttr("requestId", "req-no-reason"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("reason"));
    }

    @Test
    void missingStrategyReturns400() throws Exception {
        final ReidentifyRequest req = new ReidentifyRequest();
        req.setValues(List.of("someValue"));
        req.setReason("r");

        final String body = mockMvc.perform(post("/api/reidentify")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gson.toJson(req))
                        .requestAttr("requestId", "req-no-strategy"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("strategy"));
    }

    @Test
    void invalidStrategyReturns400() throws Exception {
        final ReidentifyRequest req = new ReidentifyRequest();
        req.setValues(List.of("someValue"));
        req.setStrategy("UNKNOWN_STRATEGY");
        req.setReason("r");

        mockMvc.perform(post("/api/reidentify")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gson.toJson(req))
                        .requestAttr("requestId", "req-bad-strategy"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cryptoStrategyWithoutPolicyNameReturns400() throws Exception {
        final ReidentifyRequest req = new ReidentifyRequest();
        req.setValues(List.of("someValue"));
        req.setStrategy("CRYPTO_REPLACE");
        req.setReason("r");

        final String body = mockMvc.perform(post("/api/reidentify")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gson.toJson(req))
                        .requestAttr("requestId", "req-no-policy"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("policyName"));
    }

    // ----- CRYPTO_REPLACE -----

    @Test
    void cryptoReplaceDecryptsSuccessfully() throws Exception {
        final String plaintext = "John Smith";
        final String encrypted = Encryption.encrypt(plaintext, new Crypto(CRYPTO_HEX_KEY, null));

        final String policyJson = "{\"name\":\"my-policy\",\"crypto\":{\"key\":\"" + CRYPTO_HEX_KEY + "\"},\"identifiers\":{}}";
        final PolicyEntity policyEntity = new PolicyEntity();
        policyEntity.setPolicy(policyJson);
        when(policyDataService.findOne("my-policy", userId)).thenReturn(policyEntity);

        final ReidentifyRequest req = request(List.of(encrypted), "CRYPTO_REPLACE", "my-policy", "authorized review");

        final String body = mockMvc.perform(post("/api/reidentify")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gson.toJson(req))
                        .requestAttr("requestId", "req-crypto-ok"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains(plaintext));
        verify(auditEventPublisher).auditEvent(eq("req-crypto-ok"), eq(AuditLogEvent.REDACTION_REVERSED),
                eq(userId), any(), any(), any());
    }

    @Test
    void cryptoReplaceDecryptsMultipleValues() throws Exception {
        final String enc1 = Encryption.encrypt("Alice", new Crypto(CRYPTO_HEX_KEY, null));
        final String enc2 = Encryption.encrypt("Bob", new Crypto(CRYPTO_HEX_KEY, null));

        final String policyJson = "{\"name\":\"p\",\"crypto\":{\"key\":\"" + CRYPTO_HEX_KEY + "\"},\"identifiers\":{}}";
        final PolicyEntity policyEntity = new PolicyEntity();
        policyEntity.setPolicy(policyJson);
        when(policyDataService.findOne("p", userId)).thenReturn(policyEntity);

        final ReidentifyRequest req = request(List.of(enc1, enc2), "CRYPTO_REPLACE", "p", "batch test");

        final String body = mockMvc.perform(post("/api/reidentify")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gson.toJson(req))
                        .requestAttr("requestId", "req-crypto-multi"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("Alice"));
        assertTrue(body.contains("Bob"));
    }

    @Test
    void cryptoReplaceMixedBatchReturnsPartialSuccess() throws Exception {
        final String plaintext = "Alice";
        final String goodEnc = Encryption.encrypt(plaintext, new Crypto(CRYPTO_HEX_KEY, null));
        final String badEnc = "not-valid-ciphertext";

        final String policyJson = "{\"name\":\"p\",\"crypto\":{\"key\":\"" + CRYPTO_HEX_KEY + "\"},\"identifiers\":{}}";
        final PolicyEntity policyEntity = new PolicyEntity();
        policyEntity.setPolicy(policyJson);
        when(policyDataService.findOne("p", userId)).thenReturn(policyEntity);

        final ReidentifyRequest req = request(List.of(goodEnc, badEnc), "CRYPTO_REPLACE", "p", "mixed test");

        final String body = mockMvc.perform(post("/api/reidentify")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gson.toJson(req))
                        .requestAttr("requestId", "req-crypto-mixed"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The successful value is decrypted; the bad value produces a per-item error.
        assertTrue(body.contains(plaintext));
        assertTrue(body.contains("error"));

        // Audit event must record only 1 success out of 2 requested.
        verify(auditEventPublisher).auditEvent(eq("req-crypto-mixed"), eq(AuditLogEvent.REDACTION_REVERSED),
                eq(userId), any(), any(),
                org.mockito.ArgumentMatchers.argThat(detail ->
                        detail.contains("requested: 2") && detail.contains("succeeded: 1")));
    }

    @Test
    void cryptoReplaceTamperedValueReturnsPerItemError() throws Exception {
        final String policyJson = "{\"name\":\"p\",\"crypto\":{\"key\":\"" + CRYPTO_HEX_KEY + "\"},\"identifiers\":{}}";
        final PolicyEntity policyEntity = new PolicyEntity();
        policyEntity.setPolicy(policyJson);
        when(policyDataService.findOne("p", userId)).thenReturn(policyEntity);

        final ReidentifyRequest req = request(List.of("not-valid-ciphertext"), "CRYPTO_REPLACE", "p", "test");

        final String body = mockMvc.perform(post("/api/reidentify")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gson.toJson(req))
                        .requestAttr("requestId", "req-crypto-tampered"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("error"));
    }

    @Test
    void cryptoReplacePolicyNotFoundReturns404() throws Exception {
        when(policyDataService.findOne("missing-policy", userId)).thenReturn(null);

        final ReidentifyRequest req = request(List.of("enc"), "CRYPTO_REPLACE", "missing-policy", "test");

        mockMvc.perform(post("/api/reidentify")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gson.toJson(req))
                        .requestAttr("requestId", "req-crypto-no-policy"))
                .andExpect(status().isNotFound());
    }

    @Test
    void cryptoReplacePolicyWithNoCryptoKeyReturns400() throws Exception {
        final String policyJson = "{\"name\":\"p\",\"identifiers\":{}}";
        final PolicyEntity policyEntity = new PolicyEntity();
        policyEntity.setPolicy(policyJson);
        when(policyDataService.findOne("p", userId)).thenReturn(policyEntity);

        final ReidentifyRequest req = request(List.of("enc"), "CRYPTO_REPLACE", "p", "test");

        mockMvc.perform(post("/api/reidentify")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gson.toJson(req))
                        .requestAttr("requestId", "req-crypto-no-key"))
                .andExpect(status().isBadRequest());
    }

    // ----- FPE_ENCRYPT_REPLACE -----

    @Test
    void fpeDecryptsSuccessfully() throws Exception {
        final String fpeTweak = EncryptionService.deriveFpeTweak(FPE_HEX_KEY);
        final String plaintext = "1234567890";
        final String encrypted = Encryption.formatPreservingEncrypt(new FPE(FPE_HEX_KEY, fpeTweak), plaintext);

        final UserEntity userEntity = new UserEntity();
        userEntity.setId(userId);
        userEntity.setFpeKey(FPE_HEX_KEY);
        when(userService.findOneById(userId)).thenReturn(userEntity);
        when(userService.ensureFpeKey(userEntity)).thenReturn(FPE_HEX_KEY);

        final ReidentifyRequest req = request(List.of(encrypted), "FPE_ENCRYPT_REPLACE", null, "authorized review");

        final String body = mockMvc.perform(post("/api/reidentify")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gson.toJson(req))
                        .requestAttr("requestId", "req-fpe-ok"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains(plaintext));
        verify(auditEventPublisher).auditEvent(eq("req-fpe-ok"), eq(AuditLogEvent.REDACTION_REVERSED),
                eq(userId), any(), any(), any());
    }

    @Test
    void fpeDecryptsMultipleValues() throws Exception {
        final String fpeTweak = EncryptionService.deriveFpeTweak(FPE_HEX_KEY);
        final FPE fpe = new FPE(FPE_HEX_KEY, fpeTweak);
        final String enc1 = Encryption.formatPreservingEncrypt(fpe, "1234567");
        final String enc2 = Encryption.formatPreservingEncrypt(fpe, "9876543");

        final UserEntity userEntity = new UserEntity();
        userEntity.setId(userId);
        when(userService.findOneById(userId)).thenReturn(userEntity);
        when(userService.ensureFpeKey(userEntity)).thenReturn(FPE_HEX_KEY);

        final ReidentifyRequest req = request(List.of(enc1, enc2), "FPE_ENCRYPT_REPLACE", null, "batch test");

        final String body = mockMvc.perform(post("/api/reidentify")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gson.toJson(req))
                        .requestAttr("requestId", "req-fpe-multi"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("1234567"));
        assertTrue(body.contains("9876543"));
    }

    @Test
    void fpeMixedBatchReturnsPartialSuccess() throws Exception {
        final String fpeTweak = EncryptionService.deriveFpeTweak(FPE_HEX_KEY);
        final String plaintext = "1234567";
        final String goodEnc = Encryption.formatPreservingEncrypt(new FPE(FPE_HEX_KEY, fpeTweak), plaintext);
        // Five digits — below FF3's minimum length, so decryption will fail for this item.
        final String badEnc = "12345";

        final UserEntity userEntity = new UserEntity();
        userEntity.setId(userId);
        when(userService.findOneById(userId)).thenReturn(userEntity);
        when(userService.ensureFpeKey(userEntity)).thenReturn(FPE_HEX_KEY);

        final ReidentifyRequest req = request(List.of(goodEnc, badEnc), "FPE_ENCRYPT_REPLACE", null, "mixed test");

        final String body = mockMvc.perform(post("/api/reidentify")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gson.toJson(req))
                        .requestAttr("requestId", "req-fpe-mixed"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The successful value is decrypted; the too-short value produces a per-item error.
        assertTrue(body.contains(plaintext));
        assertTrue(body.contains("error"));

        // Audit event must record only 1 success out of 2 requested.
        verify(auditEventPublisher).auditEvent(eq("req-fpe-mixed"), eq(AuditLogEvent.REDACTION_REVERSED),
                eq(userId), any(), any(),
                org.mockito.ArgumentMatchers.argThat(detail ->
                        detail.contains("requested: 2") && detail.contains("succeeded: 1")));
    }

    @Test
    void fpeDecryptsUsingPolicyKeyWhenPolicyNameSupplied() throws Exception {
        final String fpeTweak = EncryptionService.deriveFpeTweak(FPE_HEX_KEY);
        final String plaintext = "1234567890";
        final String encrypted = Encryption.formatPreservingEncrypt(new FPE(FPE_HEX_KEY, fpeTweak), plaintext);

        final String policyJson = "{\"name\":\"p\",\"fpe\":{\"key\":\"" + FPE_HEX_KEY + "\",\"tweak\":\"ignored\"},\"identifiers\":{}}";
        final PolicyEntity policyEntity = new PolicyEntity();
        policyEntity.setPolicy(policyJson);
        when(policyDataService.findOne("p", userId)).thenReturn(policyEntity);

        final UserEntity userEntity = new UserEntity();
        userEntity.setId(userId);
        when(userService.findOneById(userId)).thenReturn(userEntity);

        final ReidentifyRequest req = request(List.of(encrypted), "FPE_ENCRYPT_REPLACE", "p", "test");

        final String body = mockMvc.perform(post("/api/reidentify")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gson.toJson(req))
                        .requestAttr("requestId", "req-fpe-policy-key"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains(plaintext));
        verify(userService, never()).ensureFpeKey(any());
    }

    @Test
    void fpePolicyNotFoundReturns404() throws Exception {
        when(policyDataService.findOne("missing", userId)).thenReturn(null);

        final UserEntity userEntity = new UserEntity();
        userEntity.setId(userId);
        when(userService.findOneById(userId)).thenReturn(userEntity);

        final ReidentifyRequest req = request(List.of("1234567890"), "FPE_ENCRYPT_REPLACE", "missing", "test");

        mockMvc.perform(post("/api/reidentify")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gson.toJson(req))
                        .requestAttr("requestId", "req-fpe-no-policy"))
                .andExpect(status().isNotFound());
    }

    @Test
    void fpeTooShortValueReturnsPerItemError() throws Exception {
        final UserEntity userEntity = new UserEntity();
        userEntity.setId(userId);
        when(userService.findOneById(userId)).thenReturn(userEntity);
        when(userService.ensureFpeKey(userEntity)).thenReturn(FPE_HEX_KEY);

        // A value shorter than FF3's minimum length cannot be decrypted.
        final ReidentifyRequest req = request(List.of("12345"), "FPE_ENCRYPT_REPLACE", null, "test");

        final String body = mockMvc.perform(post("/api/reidentify")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gson.toJson(req))
                        .requestAttr("requestId", "req-fpe-short"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("error"));
    }

    // ----- Audit -----

    @Test
    void auditEventRecordsStrategyAndReason() throws Exception {
        final String encrypted = Encryption.encrypt("test", new Crypto(CRYPTO_HEX_KEY, null));

        final String policyJson = "{\"name\":\"p\",\"crypto\":{\"key\":\"" + CRYPTO_HEX_KEY + "\"},\"identifiers\":{}}";
        final PolicyEntity policyEntity = new PolicyEntity();
        policyEntity.setPolicy(policyJson);
        when(policyDataService.findOne("p", userId)).thenReturn(policyEntity);

        final ReidentifyRequest req = request(List.of(encrypted), "CRYPTO_REPLACE", "p", "legal hold review");

        mockMvc.perform(post("/api/reidentify")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gson.toJson(req))
                        .requestAttr("requestId", "req-audit"))
                .andExpect(status().isOk());

        verify(auditEventPublisher).auditEvent(
                eq("req-audit"),
                eq(AuditLogEvent.REDACTION_REVERSED),
                eq(userId),
                any(),
                any(),
                org.mockito.ArgumentMatchers.argThat(detail ->
                        detail.contains("legal hold review") && detail.contains(encrypted))
        );
    }

    // ----- Admin cross-user access -----

    @Test
    void adminCanReidentifyAnotherUsersValues() throws Exception {
        final ObjectId otherUserId = new ObjectId();
        makeCallerAdmin();
        makeOwnerLookup("other@example.com", otherUserId);

        final String encrypted = Encryption.encrypt("Secret", new Crypto(CRYPTO_HEX_KEY, null));

        final String policyJson = "{\"name\":\"p\",\"crypto\":{\"key\":\"" + CRYPTO_HEX_KEY + "\"},\"identifiers\":{}}";
        final PolicyEntity policyEntity = new PolicyEntity();
        policyEntity.setPolicy(policyJson);
        when(policyDataService.findOne("p", otherUserId)).thenReturn(policyEntity);

        final ReidentifyRequest req = request(List.of(encrypted), "CRYPTO_REPLACE", "p", "admin review");

        final String body = mockMvc.perform(post("/api/reidentify")
                        .header("Authorization", AUTH_HEADER)
                        .param("owner", "other@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gson.toJson(req))
                        .requestAttr("requestId", "req-admin"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("Secret"));
        // Policy lookup must target the owner's userId, not the caller's.
        verify(policyDataService).findOne("p", otherUserId);
        verify(policyDataService, never()).findOne(any(), eq(userId));
    }

    @Test
    void nonAdminNamingAnotherOwnerGets404() throws Exception {
        final ObjectId otherUserId = new ObjectId();
        makeOwnerLookup("other@example.com", otherUserId);
        final UserEntity caller = new UserEntity();
        caller.setId(userId);
        caller.setRole("user");
        when(userService.findOneById(userId)).thenReturn(caller);

        final ReidentifyRequest req = request(List.of("enc"), "CRYPTO_REPLACE", "p", "test");

        mockMvc.perform(post("/api/reidentify")
                        .header("Authorization", AUTH_HEADER)
                        .param("owner", "other@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gson.toJson(req))
                        .requestAttr("requestId", "req-forbidden"))
                .andExpect(status().isNotFound());

        verify(policyDataService, never()).findOne(any(), eq(otherUserId));
    }

    // ----- Helpers -----

    private ReidentifyRequest request(final List<String> values, final String strategy,
                                      final String policyName, final String reason) {
        final ReidentifyRequest req = new ReidentifyRequest();
        req.setValues(values);
        req.setStrategy(strategy);
        req.setPolicyName(policyName);
        req.setReason(reason);
        return req;
    }

    private void makeCallerAdmin() {
        final UserEntity admin = new UserEntity();
        admin.setId(userId);
        admin.setRole("admin");
        when(userService.findOneById(userId)).thenReturn(admin);
    }

    private void makeOwnerLookup(final String email, final ObjectId ownerId) {
        final UserEntity owner = new UserEntity();
        owner.setId(ownerId);
        owner.setEmail(email);
        when(userService.findByEmail(email)).thenReturn(owner);
    }

}

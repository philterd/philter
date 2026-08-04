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
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.SigningKeyDataService;
import ai.philterd.philter.services.cache.ApiKeyCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SigningApiControllerTest {

    @Mock
    private ApiKeyDataService apiKeyDataService;

    @Mock
    private ApiKeyCache apiKeyCache;

    @Mock
    private SigningKeyDataService signingKeyDataService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        final SigningApiController controller = new SigningApiController(
                apiKeyDataService, apiKeyCache, signingKeyDataService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new RestApiExceptions())
                .build();
    }

    @Test
    void getSigningKeyReturns200WithPemJwkAndFingerprint() throws Exception {
        when(signingKeyDataService.getPublicKeyPem())
                .thenReturn("-----BEGIN PUBLIC KEY-----\nMFkw...\n-----END PUBLIC KEY-----\n");
        when(signingKeyDataService.getPublicKeyJwk())
                .thenReturn("{\"kty\":\"EC\",\"crv\":\"P-256\",\"x\":\"abc\",\"y\":\"def\"}");
        when(signingKeyDataService.getPublicKeyFingerprint())
                .thenReturn("aa:bb:cc:dd");

        final String body = mockMvc.perform(get("/api/signing-key").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("\"fingerprint\":\"aa:bb:cc:dd\""), "response must include fingerprint; was: " + body);
        assertTrue(body.contains("\"kty\":\"EC\""), "JWK must contain kty=EC; was: " + body);
        assertTrue(body.contains("\"crv\":\"P-256\""), "JWK must contain crv=P-256; was: " + body);
        assertTrue(body.contains("BEGIN PUBLIC KEY"), "response must include PEM BEGIN marker; was: " + body);
        assertTrue(body.contains("END PUBLIC KEY"), "response must include PEM END marker; was: " + body);
    }

    @Test
    void getSigningKeyResponseIsJson() throws Exception {
        when(signingKeyDataService.getPublicKeyPem())
                .thenReturn("-----BEGIN PUBLIC KEY-----\nMFkw...\n-----END PUBLIC KEY-----\n");
        when(signingKeyDataService.getPublicKeyJwk())
                .thenReturn("{\"kty\":\"EC\",\"crv\":\"P-256\",\"x\":\"abc\",\"y\":\"def\"}");
        when(signingKeyDataService.getPublicKeyFingerprint())
                .thenReturn("aa:bb:cc:dd");

        final String contentType = mockMvc.perform(get("/api/signing-key").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentType();

        assertTrue(contentType != null && contentType.contains("application/json"),
                "content type must be application/json; was: " + contentType);
    }

    @Test
    void getSigningKeyByIdReturnsTheRetainedKeyAndMarksItInactive() throws Exception {
        when(signingKeyDataService.getPublicKeyPem("key-1"))
                .thenReturn("-----BEGIN PUBLIC KEY-----\nMFkw...\n-----END PUBLIC KEY-----\n");
        when(signingKeyDataService.getActiveKeyId()).thenReturn("key-2");

        final String body = mockMvc.perform(get("/api/signing-key/key-1").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("\"keyId\":\"key-1\""), "response must name the key; was: " + body);
        assertTrue(body.contains("BEGIN PUBLIC KEY"), "response must include the PEM; was: " + body);
        // A superseded key is still served (ledger entries signed with it must stay verifiable) but
        // must not claim to be the active one.
        assertTrue(body.contains("\"active\":false"), "a superseded key must report active=false; was: " + body);
    }

    @Test
    void getSigningKeyByIdMarksTheCurrentKeyActive() throws Exception {
        when(signingKeyDataService.getPublicKeyPem("key-2"))
                .thenReturn("-----BEGIN PUBLIC KEY-----\nMFkw...\n-----END PUBLIC KEY-----\n");
        when(signingKeyDataService.getActiveKeyId()).thenReturn("key-2");

        final String body = mockMvc.perform(get("/api/signing-key/key-2").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("\"active\":true"), "the current key must report active=true; was: " + body);
    }

    @Test
    void getSigningKeyByIdReturns404ForAnUnknownKey() throws Exception {
        when(signingKeyDataService.getPublicKeyPem("nope")).thenReturn(null);

        mockMvc.perform(get("/api/signing-key/nope").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    void getSigningKeyByIdEscapesThePemForJson() throws Exception {
        // The response is assembled as a JSON string by hand, so newlines in the PEM must be escaped
        // rather than emitted raw, which would produce a malformed document.
        when(signingKeyDataService.getPublicKeyPem("key-1"))
                .thenReturn("-----BEGIN PUBLIC KEY-----\nline\n-----END PUBLIC KEY-----\n");
        when(signingKeyDataService.getActiveKeyId()).thenReturn("key-1");

        final String body = mockMvc.perform(get("/api/signing-key/key-1").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("\n-----END"), "raw newlines must not appear in the JSON; was: " + body);
        assertTrue(body.contains("\\n"), "newlines must be escaped; was: " + body);
    }

}

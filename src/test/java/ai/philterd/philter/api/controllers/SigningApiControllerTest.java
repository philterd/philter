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

}

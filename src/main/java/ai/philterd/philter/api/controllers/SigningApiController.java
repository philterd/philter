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

import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.SigningKeyDataService;
import ai.philterd.philter.services.cache.ApiKeyCache;
import ai.philterd.philter.services.signing.SigningService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

@Tag(name = "Signing", description = "Retrieve the public signing key used to verify Philter output signatures.")
@Controller
public class SigningApiController extends AbstractApiController {

    private final SigningKeyDataService signingKeyDataService;

    @Autowired
    public SigningApiController(final ApiKeyDataService apiKeyDataService,
                                final ApiKeyCache apiKeyCache,
                                final SigningKeyDataService signingKeyDataService) {
        super(apiKeyDataService, apiKeyCache);
        this.signingKeyDataService = signingKeyDataService;
    }

    @Operation(
            summary = "Get the public signing key.",
            description = "Returns the operator's ES256 (ECDSA P-256) public signing key in PEM, JWK, and "
                    + "fingerprint form. Use the public key to verify the `" + SigningService.SIGNATURE_HEADER
                    + "` JWT header returned on successful `POST /api/filter` and `POST /api/explain` responses "
                    + "when output signing is enabled. This endpoint does not require authentication.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Public signing key details.")
    })
    @RequestMapping(value = "/api/signing-key", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<String> getSigningKey() {

        final String pem = signingKeyDataService.getPublicKeyPem();
        final String jwk = signingKeyDataService.getPublicKeyJwk();
        final String fingerprint = signingKeyDataService.getPublicKeyFingerprint();

        final String json = "{\"pem\":\"" + escapePem(pem) + "\",\"jwk\":" + jwk + ",\"fingerprint\":\"" + fingerprint + "\"}";

        return ResponseEntity.status(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body(json);
    }

    private static String escapePem(final String pem) {
        return pem.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

}

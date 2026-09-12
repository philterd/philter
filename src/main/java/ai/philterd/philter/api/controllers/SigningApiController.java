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

import ai.philterd.philter.api.exceptions.UnauthorizedException;
import ai.philterd.philter.api.responses.GenericResponse;
import ai.philterd.philter.api.security.RequiresScope;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.SigningKeyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.ApiKeyScope;
import ai.philterd.philter.services.cache.ApiKeyCache;
import ai.philterd.philter.services.signing.SigningService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

@Tag(name = "Signing", description = "Retrieve the public signing key used to verify Philter output signatures, and rotate it.")
@Controller
public class SigningApiController extends AbstractApiController {

    private final SigningKeyDataService signingKeyDataService;
    private final UserService userService;

    @Autowired
    public SigningApiController(final ApiKeyDataService apiKeyDataService,
                                final ApiKeyCache apiKeyCache,
                                final SigningKeyDataService signingKeyDataService,
                                final UserService userService) {
        super(apiKeyDataService, apiKeyCache);
        this.signingKeyDataService = signingKeyDataService;
        this.userService = userService;
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
    // Overrides the document-wide bearer requirement: verifiers fetch the public key without credentials.
    @SecurityRequirements
    @RequestMapping(value = "/api/signing-key", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<String> getSigningKey() {

        final var key = signingKeyDataService.getPublicKeyInfo();
        final String pem = key.pem();
        final String jwk = key.jwk();
        final String fingerprint = key.fingerprint();

        final String json = "{\"keyId\":\"" + escapePem(key.keyId())
                + "\",\"pem\":\"" + escapePem(pem) + "\",\"jwk\":" + jwk + ",\"fingerprint\":\"" + fingerprint + "\"}";

        return ResponseEntity.status(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body(json);
    }

    @Operation(
            summary = "Get a specific public signing key.",
            description = "Returns a retained public signing key by its id, in PEM form. A key that has been "
                    + "superseded by a regeneration is kept, because redaction-ledger entries signed with it "
                    + "must remain verifiable. Ledger entries and exports name the key that signed them in "
                    + "their signingKeyId field. This endpoint does not require authentication.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The public key with the given id."),
            @ApiResponse(responseCode = "404", description = "No key with that id is retained.")
    })
    // Overrides the document-wide bearer requirement: verifiers fetch the public key without credentials.
    @SecurityRequirements
    @RequestMapping(value = "/api/signing-key/{keyId}", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<String> getSigningKeyById(@PathVariable("keyId") final String keyId) {

        final String pem = signingKeyDataService.getPublicKeyPem(keyId);

        if (pem == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        final boolean active = keyId.equals(signingKeyDataService.getActiveKeyId());
        final String json = "{\"keyId\":\"" + escapePem(keyId) + "\",\"pem\":\"" + escapePem(pem)
                + "\",\"active\":" + active + "}";

        return ResponseEntity.status(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body(json);
    }

    @Operation(
            summary = "Rotate the output signing key.",
            description = "Generates a new ES256 keypair and makes it the active signing key. The superseded "
                    + "key is retained and stays retrievable through GET /api/signing-key/{keyId}, so "
                    + "signatures and ledger entries made with it remain verifiable; consumers should resolve "
                    + "each signature's key id rather than caching one key. Rotation is the response to a "
                    + "suspected key compromise, so it requires an administrator as well as the signing:write "
                    + "scope, and is recorded as a signing_key_regenerated audit event.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The key was rotated. The body names the key that is now active."),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "403", description = "The key does not hold signing:write, or the caller is not an administrator."),
            @ApiResponse(responseCode = "409", description = "The signing key is managed by PHILTER_SIGNING_KEY_PATH and cannot be rotated through the API.")
    })
    @RequiresScope(ApiKeyScope.SIGNING_WRITE)
    @RequestMapping(value = "/api/signing-key/regenerate", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<String> regenerateSigningKey(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestAttribute("requestId") String requestId,
            final HttpServletRequest httpServletRequest) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);
        if (apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ResponseEntity<GenericResponse> refusal =
                authorizeAdminOnly(userService, apiKeyEntity.getUserId(), "Rotating the signing key");
        if (refusal != null) {
            return ResponseEntity.status(refusal.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"message\":\"" + escapePem(refusal.getBody().getMessage()) + "\"}");
        }

        // Checked here so the refusal is a 409 naming the cause rather than the service's
        // IllegalStateException reaching the catch-all as a 500.
        if (signingKeyDataService.isExternallyManaged()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"message\":\"The signing key is managed by PHILTER_SIGNING_KEY_PATH; "
                            + "replace the file and restart all instances.\"}");
        }

        // The principal recorded is always the user; the key that carried the request goes in details.
        final String keyId = signingKeyDataService.regenerate(requestId, apiKeyEntity.getUserId(),
                getClientIpAddress(httpServletRequest), "source: api, api_key: " + apiKeyEntity.getId());

        return ResponseEntity.status(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"keyId\":\"" + escapePem(keyId) + "\"}");
    }

    private static String escapePem(final String pem) {
        if (pem == null) {
            return "";
        }
        return pem.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

}

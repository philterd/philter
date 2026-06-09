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
import ai.philterd.phileas.policy.Policy;
import ai.philterd.phileas.utils.Encryption;
import ai.philterd.philter.api.exceptions.UnauthorizedException;
import ai.philterd.philter.api.requests.ReidentifyRequest;
import ai.philterd.philter.api.responses.GenericResponse;
import ai.philterd.philter.api.responses.ReidentifyResponse;
import ai.philterd.philter.audit.AuditEventPublisher;
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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.List;

@Tag(name = "Re-identification", description = "Governed, audited reversal of cryptographically-redacted values.")
@Controller
public class ReidentifyApiController extends AbstractApiController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReidentifyApiController.class);

    private static final String STRATEGY_CRYPTO = "CRYPTO_REPLACE";
    private static final String STRATEGY_FPE = "FPE_ENCRYPT_REPLACE";

    private final UserService userService;
    private final PolicyDataService policyDataService;
    private final AuditEventPublisher auditEventPublisher;
    private final Gson gson;

    public ReidentifyApiController(final UserService userService,
                                   final PolicyDataService policyDataService,
                                   final ApiKeyDataService apiKeyDataService,
                                   final AuditEventPublisher auditEventPublisher,
                                   final ApiKeyCache apiKeyCache,
                                   final Gson gson) {
        super(apiKeyDataService, apiKeyCache);
        this.userService = userService;
        this.policyDataService = policyDataService;
        this.auditEventPublisher = auditEventPublisher;
        this.gson = gson;
    }

    @Operation(
            summary = "Re-identify redacted values.",
            description = "Reverses one or more values that were redacted via the CRYPTO_REPLACE or "
                    + "FPE_ENCRYPT_REPLACE strategy. The caller must supply a stated reason, which is "
                    + "recorded in the audit log alongside the actor, timestamp, and count of reversals. "
                    + "A user may re-identify their own values; an admin may re-identify any user's values "
                    + "by supplying that user's email via the owner parameter. "
                    + "CRYPTO_REPLACE requires policyName so the key can be resolved from the stored policy. "
                    + "FPE_ENCRYPT_REPLACE uses the user's per-account FPE key; if the policy specified a "
                    + "custom FPE key, also pass policyName so that key is used instead."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Request processed; inspect each result for per-value errors."),
            @ApiResponse(responseCode = "400", description = "Missing or invalid request fields."),
            @ApiResponse(responseCode = "401", description = "Missing or invalid API key."),
            @ApiResponse(responseCode = "404", description = "Named policy not found, or owner not found.")
    })
    @RequestMapping(value = "/api/reidentify", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> reidentify(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestBody ReidentifyRequest request,
            final @RequestParam(value = "owner", required = false) String owner,
            final @RequestAttribute("requestId") String requestId,
            final HttpServletRequest httpServletRequest) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);
        if (apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        if (request == null || request.getValues() == null || request.getValues().isEmpty()) {
            return badRequest("'values' must not be empty.");
        }
        if (request.getReason() == null || request.getReason().isBlank()) {
            return badRequest("'reason' is required.");
        }
        if (request.getStrategy() == null || request.getStrategy().isBlank()) {
            return badRequest("'strategy' is required (CRYPTO_REPLACE or FPE_ENCRYPT_REPLACE).");
        }

        final String strategy = request.getStrategy().toUpperCase();
        if (!STRATEGY_CRYPTO.equals(strategy) && !STRATEGY_FPE.equals(strategy)) {
            return badRequest("'strategy' must be CRYPTO_REPLACE or FPE_ENCRYPT_REPLACE.");
        }
        if (STRATEGY_CRYPTO.equals(strategy) && (request.getPolicyName() == null || request.getPolicyName().isBlank())) {
            return badRequest("'policyName' is required when strategy is CRYPTO_REPLACE.");
        }

        final ObjectId targetUserId = resolveTargetUserId(userService, apiKeyEntity.getUserId(), owner);
        if (targetUserId == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        auditAdminCrossUserAccess(auditEventPublisher, requestId, apiKeyEntity.getUserId(), targetUserId,
                "re-identify " + request.getValues().size() + " value(s) via " + strategy);

        final List<ReidentifyResponse.ReidentifyResult> results;
        try {
            results = STRATEGY_CRYPTO.equals(strategy)
                    ? reidentifyCrypto(request, targetUserId)
                    : reidentifyFpe(request, targetUserId);
        } catch (PolicyNotFoundException e) {
            return new ResponseEntity<>(gson.toJson(new GenericResponse(e.getMessage())), HttpStatus.NOT_FOUND);
        } catch (MissingCryptoKeyException e) {
            return badRequest(e.getMessage());
        }

        final long successCount = results.stream().filter(r -> r.getError() == null).count();
        // Record the encrypted input values (ciphertexts) as the "what" — they are not sensitive
        // since they are already the distributed replacement tokens.
        final String valuesDetail = String.join(",", request.getValues());
        auditEventPublisher.auditEvent(
                requestId,
                AuditLogEvent.REDACTION_REVERSED,
                apiKeyEntity.getUserId(),
                null,
                getClientIpAddress(httpServletRequest),
                "strategy: " + strategy
                        + ", requested: " + request.getValues().size()
                        + ", succeeded: " + successCount
                        + ", reason: " + request.getReason()
                        + ", values: [" + valuesDetail + "]"
                        + (owner != null && !owner.isBlank() ? ", owner: " + targetUserId : "")
        );

        return new ResponseEntity<>(gson.toJson(new ReidentifyResponse(results)), HttpStatus.OK);
    }

    private List<ReidentifyResponse.ReidentifyResult> reidentifyCrypto(
            final ReidentifyRequest request, final ObjectId userId) {

        final PolicyEntity policyEntity = policyDataService.findOne(request.getPolicyName(), userId);
        if (policyEntity == null) {
            throw new PolicyNotFoundException("Policy '" + request.getPolicyName() + "' not found.");
        }

        final Policy policy = gson.fromJson(policyEntity.getPolicy(), Policy.class);
        if (policy.getCrypto() == null || policy.getCrypto().getKey() == null) {
            throw new MissingCryptoKeyException(
                    "Policy '" + request.getPolicyName() + "' has no crypto key configured.");
        }

        final Crypto crypto = policy.getCrypto();
        final List<ReidentifyResponse.ReidentifyResult> results = new ArrayList<>(request.getValues().size());

        for (final String encryptedValue : request.getValues()) {
            try {
                final String decrypted = Encryption.decrypt(encryptedValue, crypto);
                results.add(new ReidentifyResponse.ReidentifyResult(encryptedValue, decrypted));
            } catch (Exception e) {
                LOGGER.warn("CRYPTO_REPLACE decryption failed for a value: {}", e.getMessage());
                results.add(new ReidentifyResponse.ReidentifyResult(encryptedValue, null, "Decryption failed."));
            }
        }

        return results;
    }

    private List<ReidentifyResponse.ReidentifyResult> reidentifyFpe(
            final ReidentifyRequest request, final ObjectId userId) {

        final UserEntity userEntity = userService.findOneById(userId);
        if (userEntity == null) {
            throw new IllegalStateException("User not found.");
        }

        // Prefer a policy-level FPE key when a policyName is supplied; fall back to the user's
        // account-level key (the same fallback path as during redaction).
        String fpeKey = null;
        if (request.getPolicyName() != null && !request.getPolicyName().isBlank()) {
            final PolicyEntity policyEntity = policyDataService.findOne(request.getPolicyName(), userId);
            if (policyEntity == null) {
                throw new PolicyNotFoundException("Policy '" + request.getPolicyName() + "' not found.");
            }
            final Policy policy = gson.fromJson(policyEntity.getPolicy(), Policy.class);
            if (policy.getFpe() != null && policy.getFpe().getKey() != null) {
                fpeKey = policy.getFpe().getKey();
            }
        }

        if (fpeKey == null) {
            fpeKey = userService.ensureFpeKey(userEntity);
        }

        final String fpeTweak = EncryptionService.deriveFpeTweak(fpeKey);
        final FPE fpe = new FPE(fpeKey, fpeTweak);

        final List<ReidentifyResponse.ReidentifyResult> results = new ArrayList<>(request.getValues().size());

        for (final String encryptedValue : request.getValues()) {
            try {
                final String decrypted = Encryption.formatPreservingDecrypt(fpe, encryptedValue);
                results.add(new ReidentifyResponse.ReidentifyResult(encryptedValue, decrypted));
            } catch (Exception e) {
                LOGGER.warn("FPE_ENCRYPT_REPLACE decryption failed for a value: {}", e.getMessage());
                results.add(new ReidentifyResponse.ReidentifyResult(encryptedValue, null, "Decryption failed."));
            }
        }

        return results;
    }

    private ResponseEntity<String> badRequest(final String message) {
        return new ResponseEntity<>(gson.toJson(new GenericResponse(message)), HttpStatus.BAD_REQUEST);
    }

    private static class PolicyNotFoundException extends RuntimeException {
        PolicyNotFoundException(final String message) {
            super(message);
        }
    }

    private static class MissingCryptoKeyException extends RuntimeException {
        MissingCryptoKeyException(final String message) {
            super(message);
        }
    }

}

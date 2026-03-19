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

import ai.philterd.phileas.policy.Policy;
import ai.philterd.philter.api.exceptions.BadRequestException;
import ai.philterd.philter.api.exceptions.UnauthorizedException;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.entities.PolicyEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.model.Source;
import ai.philterd.philter.services.RequestIdGenerator;
import ai.philterd.philter.services.cache.ApiKeyCache;
import com.google.gson.Gson;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.bson.types.ObjectId;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.io.IOException;
import java.util.List;

@Controller
public class PoliciesApiController extends AbstractApiController {

    private final PolicyDataService policyDataService;
    private final Gson gson;

    public PoliciesApiController(final PolicyDataService policyDataService, final ApiKeyDataService apiKeyDataService,
                                 final AuditEventPublisher auditEventPublisher, final ApiKeyCache apiKeyCache, final Gson gson) {
        super(apiKeyDataService, apiKeyCache);
        this.policyDataService = policyDataService;
        this.gson = gson;
    }

    @RequestMapping(value = "/api/policies", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<List<String>> getPolicyNames(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestParam(value = "offset", defaultValue = "0") int offset,
            final @RequestParam(value = "limit", defaultValue = "25") int limit
    ) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);

        if(apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = apiKeyEntity.getId();

        final List<PolicyEntity> policies = policyDataService.findAll(userId, offset, limit, false);
        final List<String> policyNames = policies.stream().map(PolicyEntity::getName).toList();

        return ResponseEntity.status(HttpStatus.OK)
                .body(policyNames);

    }

    @RequestMapping(value = "/api/policies/{policyName}", method = RequestMethod.GET)
    public @ResponseBody ResponseEntity<Policy> get(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            @PathVariable(name = "policyName") String policyName) throws IOException {

        if (StringUtils.isEmpty(policyName)) {
            throw new BadRequestException("The policy name is missing.");
        }

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);

        if(apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = apiKeyEntity.getId();

        final PolicyEntity policyEntity = policyDataService.findOne(policyName, userId);
        final Policy policy = gson.fromJson(policyEntity.getPolicy(), Policy.class);

        return ResponseEntity.status(HttpStatus.OK)
                .body(policy);

    }

    @RequestMapping(value = "/api/policies", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public void save(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            @RequestParam("name") final String name, @RequestBody Policy policy) throws IOException {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);

        if(apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = apiKeyEntity.getId();

        final PolicyEntity policyEntity = new PolicyEntity();
        policyEntity.setUserId(userId);
        policyEntity.setPolicy(gson.toJson(policy));
        policyEntity.setName(name);

        policyDataService.save(policyEntity);

    }

    @RequestMapping(value = "/api/policies/{policyName}", method = RequestMethod.DELETE)
    @ResponseStatus(HttpStatus.OK)
    public void delete(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            @PathVariable(name = "policyName") String policyName,
            final HttpServletRequest request) throws IOException {

        if (StringUtils.isEmpty(policyName)) {
            throw new BadRequestException("The policy name is missing.");
        }

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);

        if(apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = apiKeyEntity.getId();

        final String requestId = RequestIdGenerator.generate();

        policyDataService.deleteByName(requestId, policyName, userId, Source.API);

    }

}
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
import ai.philterd.philter.services.policies.PolicyService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.io.IOException;
import java.util.List;

@Controller
public class PoliciesApiController extends AbstractController {

    @Autowired
    private PolicyService policyService;

    @RequestMapping(value = "/api/policies", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<List<String>> getPolicyNames() throws IOException {

        final List<String> policyNames = policyService.get();

        return ResponseEntity.status(HttpStatus.OK)
                .body(policyNames);

    }

    @RequestMapping(value = "/api/policies/{policyName}", method = RequestMethod.GET)
    public @ResponseBody
    ResponseEntity<Policy> get(
            @PathVariable(name="policyName") String policyName) throws IOException {

        if (StringUtils.isEmpty(policyName)) {
            throw new BadRequestException("The policy name is missing.");
        }

        final Policy policy = policyService.get(policyName);

        return ResponseEntity.status(HttpStatus.OK)
                .body(policy);

    }

    @RequestMapping(value = "/api/policies", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public void save(
            @RequestBody Policy policy) throws IOException {

        policyService.save(policy);

    }

    @RequestMapping(value = "/api/policies/{policyName}", method = RequestMethod.DELETE)
    @ResponseStatus(HttpStatus.OK)
    public void delete(
            @PathVariable(name="policyName") String policyName) throws IOException {

        if (StringUtils.isEmpty(policyName)) {
            throw new BadRequestException("The policy name is missing.");
        }

        policyService.delete(policyName);

    }

}
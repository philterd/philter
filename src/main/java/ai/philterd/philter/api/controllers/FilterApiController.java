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

import ai.philterd.phileas.model.filtering.AbstractFilterResult;
import ai.philterd.phileas.model.filtering.BinaryDocumentFilterResult;
import ai.philterd.phileas.model.filtering.MimeType;
import ai.philterd.phileas.model.filtering.TextFilterResult;
import ai.philterd.philter.api.exceptions.UnauthorizedException;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.services.cache.ApiKeyCache;
import ai.philterd.philter.services.filtering.RedactionService;
import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.nio.charset.StandardCharsets;

@Controller
public class FilterApiController extends AbstractApiController {

    private static final Logger LOGGER = LogManager.getLogger(FilterApiController.class);

    private final RedactionService redactionService;

    @Autowired
    public FilterApiController(final RedactionService redactionService, final PolicyDataService policyDataService, final ApiKeyDataService apiKeyDataService,
                               final AuditEventPublisher auditEventPublisher, final ApiKeyCache apiKeyCache, final Gson gson) {
        super(apiKeyDataService, apiKeyCache);
        this.redactionService = redactionService;
    }

    @RequestMapping(value = "/api/filter", method = RequestMethod.POST, produces = "application/zip", consumes = MediaType.APPLICATION_PDF_VALUE)
    public @ResponseBody ResponseEntity<byte[]> filterApplicationPdfAsApplicationZip(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            @RequestParam(value = "c", defaultValue = "none") String context,
            @RequestParam(value = "p", defaultValue = "default") String policyName,
            @RequestBody byte[] body) throws Exception {

        LOGGER.info("Received uploaded binary PDF file to be returned as ZIP.");

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);

        if(apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = apiKeyEntity.getId();

        final AbstractFilterResult response = redactionService.filter(policyName, userId, context, body, MimeType.IMAGE_JPEG);
        final BinaryDocumentFilterResult binaryDocumentFilterResult = (BinaryDocumentFilterResult) response;

        return ResponseEntity.status(HttpStatus.OK)
                .body(binaryDocumentFilterResult.getDocument());

    }

    @RequestMapping(value = "/api/filter", method = RequestMethod.POST, produces = MediaType.APPLICATION_PDF_VALUE, consumes = MediaType.APPLICATION_PDF_VALUE)
    public @ResponseBody ResponseEntity<byte[]> filterApplicationPdfAsApplicationPdf(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            @RequestParam(value = "c", defaultValue = "none") String context,
            @RequestParam(value = "p", defaultValue = "default") String policyName,
            @RequestBody byte[] body) throws Exception {

        LOGGER.info("Received uploaded binary PDF file to be returned as PDF.");

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);

        if(apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = apiKeyEntity.getId();

        final AbstractFilterResult response = redactionService.filter(policyName, userId, context, body, MimeType.APPLICATION_PDF);
        final BinaryDocumentFilterResult binaryDocumentFilterResult = (BinaryDocumentFilterResult) response;

        return ResponseEntity.status(HttpStatus.OK)
                .body(binaryDocumentFilterResult.getDocument());

    }

    @RequestMapping(value = "/api/filter", method = RequestMethod.POST, produces = MediaType.TEXT_PLAIN_VALUE, consumes = MediaType.TEXT_PLAIN_VALUE)
    public @ResponseBody ResponseEntity<String> filterTextPlainAsTextPlain(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            @RequestParam(value = "c", defaultValue = "none") String context,
            @RequestParam(value = "p", defaultValue = "default") String policyName,
            @RequestBody String body) throws Exception {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);

        if(apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = apiKeyEntity.getUserId();

        final AbstractFilterResult response = redactionService.filter(policyName, userId, context, body.getBytes(StandardCharsets.UTF_8), MimeType.TEXT_PLAIN);
        final TextFilterResult textFilterResult = (TextFilterResult) response;

        return ResponseEntity.status(HttpStatus.OK)
                .body(textFilterResult.getFilteredText());

    }

}

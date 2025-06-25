/*
 *     Copyright 2025 Philterd, LLC @ https://www.philterd.ai
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

import ai.philterd.phileas.model.enums.MimeType;
import ai.philterd.phileas.model.responses.BinaryDocumentFilterResponse;
import ai.philterd.phileas.model.responses.FilterResponse;
import ai.philterd.phileas.model.services.FilterService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Arrays;
import java.util.List;

@Controller
public class FilterApiController extends AbstractController {

	private static final Logger LOGGER = LogManager.getLogger(FilterApiController.class);

	@Autowired
	private FilterService filterService;

	@Autowired
	public FilterApiController(FilterService filterService) {
		this.filterService = filterService;
	}

	@RequestMapping(value="/api/filter", method=RequestMethod.POST, produces = "application/zip", consumes = MediaType.APPLICATION_PDF_VALUE)
	public @ResponseBody ResponseEntity<byte[]> filterApplicationPdfAsApplicationZip(
			@RequestParam(value="c", defaultValue="none") String context,
			@RequestParam(value="d", defaultValue="") String documentId,
			@RequestParam(value="p", defaultValue="default") String policyName,
			@RequestBody byte[] body) throws Exception {

		LOGGER.info("Received uploaded binary PDF file to be returned as ZIP.");

		final List<String> policies = Arrays.asList(policyName);
		final BinaryDocumentFilterResponse response = filterService.filter(policies, context, documentId, body, MimeType.APPLICATION_PDF, MimeType.IMAGE_JPEG);

		return ResponseEntity.status(HttpStatus.OK)
				.header("x-document-id", response.getDocumentId())
				.body(response.getDocument());

	}

	@RequestMapping(value="/api/filter", method=RequestMethod.POST, produces = MediaType.APPLICATION_PDF_VALUE, consumes = MediaType.APPLICATION_PDF_VALUE)
	public @ResponseBody ResponseEntity<byte[]> filterApplicationPdfAsApplicationPdf(
			@RequestParam(value="c", defaultValue="none") String context,
			@RequestParam(value="d", defaultValue="") String documentId,
			@RequestParam(value="p", defaultValue="default") String policyName,
			@RequestBody byte[] body) throws Exception {

		LOGGER.info("Received uploaded binary PDF file to be returned as PDF.");

		final List<String> policies = Arrays.asList(policyName);
		final BinaryDocumentFilterResponse response = filterService.filter(policies, context, documentId, body, MimeType.APPLICATION_PDF, MimeType.APPLICATION_PDF);

		return ResponseEntity.status(HttpStatus.OK)
				.header("x-document-id", response.getDocumentId())
				.body(response.getDocument());

	}

	@RequestMapping(value="/api/filter", method=RequestMethod.POST, produces = MediaType.TEXT_PLAIN_VALUE, consumes = MediaType.TEXT_PLAIN_VALUE)
	public @ResponseBody ResponseEntity<String> filterTextPlainAsTextPlain(
			@RequestParam(value="c", defaultValue="none") String context,
			@RequestParam(value="d", defaultValue="") String documentId,
            @RequestParam(value="p", defaultValue="default") String policyName,
            @RequestBody String body) throws Exception {

		final List<String> policies = Arrays.asList(policyName);
		final FilterResponse response = filterService.filter(policies, context, documentId, body, MimeType.TEXT_PLAIN);

		return ResponseEntity.status(HttpStatus.OK)
				.header("x-document-id", response.getDocumentId())
				.body(response.getFilteredText());

	}

	/*@RequestMapping(value="/api/filter", method=RequestMethod.POST, produces = MediaType.IMAGE_JPEG_VALUE, consumes = MediaType.IMAGE_JPEG_VALUE)
	public @ResponseBody ResponseEntity<byte[]> filterImageFacialRecognition(
			@RequestParam(value="c", defaultValue="none") String context,
			@RequestParam(value="d", defaultValue="") String documentId,
			@RequestParam(value="p", defaultValue="default") String policyName,
			@RequestBody byte[] body) throws Exception {

		final BinaryDocumentFilterResponse response = filterService.filter(policyName, context, documentId, body, MimeType.IMAGE_JPEG, MimeType.IMAGE_JPEG);

		return ResponseEntity.status(HttpStatus.OK)
				.header("x-document-id", response.getDocumentId())
				.body(response.getDocument());

	}*/

	/*@RequestMapping(value="/api/filter", method=RequestMethod.POST, produces = "text/csv", consumes = "text/csv")
	public @ResponseBody ResponseEntity<String> filterTextCsvAsTextCsv(
			@RequestParam(value="c", defaultValue="none") String context,
			@RequestParam(value="d", defaultValue="") String documentId,
			@RequestParam(value="p", defaultValue="default") String policyName,
			@RequestBody String body) throws Exception {

		if(!statusUp) {
			statusUp = getPythonRESTServiceStatus(phileasConfiguration.philterNerEndpoint());
		}

		if(statusUp) {

			final FilterResponse response = filterService.filter(policyName, context, documentId, body, MimeType.CSV);

			return ResponseEntity.status(HttpStatus.OK)
					.header("x-document-id", response.getDocumentId())
					.body(response.getFilteredText());

		} else {

			throw new ServiceUnavailableException("Philter is still initializing. If message persists refer to Philter's log file.");

		}

	}*/

}

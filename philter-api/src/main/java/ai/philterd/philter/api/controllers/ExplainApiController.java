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
import ai.philterd.phileas.model.responses.FilterResponse;
import ai.philterd.phileas.model.services.FilterService;
import com.google.gson.Gson;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

@Controller
public class ExplainApiController extends AbstractController {

	private final FilterService filterService;
	private final Gson gson;

	@Autowired
	public ExplainApiController(FilterService filterService, Gson gson) {
		this.filterService = filterService;
		this.gson = gson;
	}

	@RequestMapping(value="/api/explain", method=RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.TEXT_PLAIN_VALUE)
	public @ResponseBody ResponseEntity<String> explainTextPlainAsApplicationJson(
			@RequestParam(value="c", defaultValue="none") String context,
			@RequestParam(value="d", defaultValue="") String documentId,
			@RequestParam(value="p", defaultValue="default") String policyName,
			@RequestBody String body) throws Exception {

			final List<String> policies = Arrays.asList(policyName);
			final FilterResponse response = filterService.filter(policies, context, documentId, body, MimeType.TEXT_PLAIN);

		return ResponseEntity.status(HttpStatus.OK)
				.header("x-document-id", response.getDocumentId())
				.contentType(MediaType.APPLICATION_JSON)
				.body(gson.toJson(response));


	}

}

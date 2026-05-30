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

import ai.philterd.phileas.policy.PolicySchema;
import ai.philterd.philter.api.responses.StatusResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.InputStream;
import java.util.Properties;

@Controller
public class StatusApiController {

    private static final Logger LOGGER = LogManager.getLogger(StatusApiController.class);

    private final String applicationVersion;
    private final String gitCommit;

    public StatusApiController(@Value("${build.version}") final String applicationVersion) {
        this.applicationVersion = applicationVersion;
        this.gitCommit = readGitCommit();
    }

    @Operation(summary = "Get the status of Philter, including the supported redaction policy schema version.")
    @RequestMapping(value = {"/api/status", "/api/health"}, method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<StatusResponse> status() {

        final StatusResponse response = new StatusResponse(
                "Healthy",
                applicationVersion,
                PolicySchema.getSupportedSchemaVersion(),
                gitCommit);

        return ResponseEntity.status(HttpStatus.OK).body(response);

    }

    private static String readGitCommit() {
        try (final InputStream inputStream = StatusApiController.class.getClassLoader().getResourceAsStream("git.properties")) {
            if (inputStream == null) {
                return null;
            }
            final Properties properties = new Properties();
            properties.load(inputStream);
            return properties.getProperty("git.commit.id.abbrev");
        } catch (final Exception e) {
            LOGGER.warn("Unable to read git commit from git.properties.", e);
            return null;
        }
    }

}

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
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StatusApiControllerTest {

    @Test
    public void statusReturnsVersionsAndHealthy() {
        final StatusApiController controller = new StatusApiController("4.0.0");

        final ResponseEntity<StatusResponse> entity = controller.status();

        assertEquals(HttpStatus.OK, entity.getStatusCode());
        final StatusResponse body = entity.getBody();
        assertNotNull(body);
        assertEquals("Healthy", body.getStatus());
        assertEquals("4.0.0", body.getApplicationVersion());
        // The schema version must be the version supported by the bundled Phileas.
        assertEquals(PolicySchema.getSupportedSchemaVersion(), body.getRedactionPolicySchemaVersion());
        // The git commit is read from git.properties (generated at build time).
        // Assert it matches the abbreviated-hash format rather than a fixed value,
        // which would change on every commit.
        assertNotNull(body.getGitCommit());
        assertTrue(body.getGitCommit().matches("[0-9a-f]{7,}"),
                "expected an abbreviated git hash, got: " + body.getGitCommit());
    }

}

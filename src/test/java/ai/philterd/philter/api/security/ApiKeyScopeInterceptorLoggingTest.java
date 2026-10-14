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
package ai.philterd.philter.api.security;

import ai.philterd.philter.api.controllers.AbstractApiController;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.model.ApiKeyScope;
import ai.philterd.philter.testutil.LogCapture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A refused request is logged by its route, so a name in its path stays out of the log. */
class ApiKeyScopeInterceptorLoggingTest {

    static class Handler {
        @RequiresScope(ApiKeyScope.CONTEXTS_READ)
        public void getContext() {
        }
    }

    @Test
    @DisplayName("A refusal names the route, not the context in the path")
    void aRefusalLogsTheRouteNotTheName() throws Exception {

        final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/contexts/jane-doe-oncology");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/contexts/{name}");
        final ApiKeyEntity key = new ApiKeyEntity();
        key.setScopes(Set.of(ApiKeyScope.POLICIES_READ.getScope()));
        request.setAttribute(AbstractApiController.API_KEY_ENTITY_ATTRIBUTE, key);
        final MockHttpServletResponse response = new MockHttpServletResponse();

        try (final LogCapture log = new LogCapture()) {
            final boolean allowed = new ApiKeyScopeInterceptor().preHandle(request, response,
                    new HandlerMethod(new Handler(), Handler.class.getMethod("getContext")));

            assertFalse(allowed);
            assertEquals(403, response.getStatus());
            assertTrue(log.contains("Refusing GET /api/contexts/{name}"), log.messages().toString());
            assertFalse(log.contains("jane-doe-oncology"), log.messages().toString());
        }

    }

}

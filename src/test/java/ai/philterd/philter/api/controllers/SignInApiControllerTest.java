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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SignInApiControllerTest {

    @Test
    void theAuditedUsernameIsCappedAndCannotFakeFields() {
        assertEquals("(none)", SignInApiController.auditable(null));
        assertEquals("jordan@example.com", SignInApiController.auditable("jordan@example.com"));
        assertEquals("x_ api_key: 1__forged", SignInApiController.auditable("x, api_key: 1\n\rforged"));
        assertEquals("y".repeat(100) + "...", SignInApiController.auditable("y".repeat(5000)));
    }

}

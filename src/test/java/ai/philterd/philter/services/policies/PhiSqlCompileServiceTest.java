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
package ai.philterd.philter.services.policies;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhiSqlCompileServiceTest {

    private final PhiSqlCompileService service = new PhiSqlCompileService();

    @Test
    void compilesValidPhiSqlToNativePolicyJson() {
        final PhiSqlCompileService.Result result = service.compile("POLICY ssn_only;\nREDACT SSN WITH MASK;");

        assertTrue(result.isSuccess());
        assertEquals("ssn_only", result.getName());
        assertNotNull(result.getPolicyJson());
        // Compiles to the native Phileas shape (identifiers -> ssn -> ssnFilterStrategies -> MASK).
        assertTrue(result.getPolicyJson().contains("ssnFilterStrategies"));
        assertTrue(result.getPolicyJson().contains("MASK"));
    }

    @Test
    void returnsAnErrorForInvalidPhiSql() {
        final PhiSqlCompileService.Result result = service.compile("this is not valid phisql");

        assertFalse(result.isSuccess());
        assertNotNull(result.getError());
    }

    @Test
    void returnsAnErrorForEmptySource() {
        assertFalse(service.compile("").isSuccess());
        assertFalse(service.compile("   ").isSuccess());
        assertFalse(service.compile(null).isSuccess());
    }

}

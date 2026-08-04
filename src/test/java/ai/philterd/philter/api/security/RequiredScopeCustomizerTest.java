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

import ai.philterd.philter.PhilterApplication;
import ai.philterd.philter.model.ApiKeyScope;
import io.swagger.v3.oas.models.Operation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The customizer that writes each endpoint's required scope into the generated OpenAPI specification.
 *
 * <p>The specification is what clients outside Java are generated from, so it is where a developer
 * discovers that exporting a ledger needs {@code ledger:export} rather than any valid key. The sentence
 * is derived from the same annotation the interceptor enforces; these tests pin that derivation.
 */
class RequiredScopeCustomizerTest {

    /** Stands in for a controller: what matters is the annotation on the handler method. */
    @SuppressWarnings("unused")
    static final class ExampleController {

        @RequiresScope(ApiKeyScope.LEDGER_EXPORT)
        public void annotated() {
        }

        public void unannotated() {
        }

    }

    private final OperationCustomizer customizer = new PhilterApplication().requiredScopeCustomizer();

    private static HandlerMethod handlerFor(final String methodName) throws NoSuchMethodException {
        final Method method = ExampleController.class.getMethod(methodName);
        return new HandlerMethod(new ExampleController(), method);
    }

    @Test
    @DisplayName("The required scope is appended to an existing description")
    void appendsToAnExistingDescription() throws Exception {
        final Operation operation = new Operation().description("Export a chain.");

        customizer.customize(operation, handlerFor("annotated"));

        assertEquals("Export a chain. Requires the `ledger:export` scope.", operation.getDescription());
    }

    @Test
    @DisplayName("An endpoint with no description gets the scope sentence on its own")
    void setsTheDescriptionWhenThereIsNone() throws Exception {
        final Operation operation = new Operation();

        customizer.customize(operation, handlerFor("annotated"));

        assertEquals("Requires the `ledger:export` scope.", operation.getDescription());
    }

    @Test
    @DisplayName("An endpoint requiring no scope is left alone")
    void leavesUnannotatedOperationsUntouched() throws Exception {
        final Operation operation = new Operation();

        customizer.customize(operation, handlerFor("unannotated"));

        // The unauthenticated endpoints take no key, so claiming a scope would be wrong.
        assertNull(operation.getDescription());
    }

    @Test
    @DisplayName("Customizing twice does not repeat the sentence")
    void isIdempotent() throws Exception {
        final Operation operation = new Operation().description("Export a chain.");

        customizer.customize(operation, handlerFor("annotated"));
        customizer.customize(operation, handlerFor("annotated"));

        // springdoc may customize a cached document more than once; the text must not accumulate.
        assertEquals(1, countOccurrences(operation.getDescription(), "Requires the `ledger:export` scope."),
                "the scope sentence must appear once: " + operation.getDescription());
        assertTrue(operation.getDescription().startsWith("Export a chain."));
    }

    private static int countOccurrences(final String text, final String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }

}

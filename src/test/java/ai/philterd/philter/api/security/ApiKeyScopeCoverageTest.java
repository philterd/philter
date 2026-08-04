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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Fails the build if any API endpoint does not declare the scope it requires.
 *
 * <p>{@link ApiKeyScopeInterceptor} already refuses an unannotated handler at runtime, so a missing
 * annotation cannot leave an endpoint unprotected. That failure would surface as a working endpoint
 * suddenly returning 403, which is a poor way to find out. This test turns the same mistake into a
 * compile-and-test failure naming the handler.
 *
 * <p>Endpoints served without an API key have no scope to require and are listed here explicitly, so
 * adding one to that set is a deliberate, reviewable act rather than an omission.
 */
class ApiKeyScopeCoverageTest {

    private static final Path CONTROLLERS = Path.of("src/main/java/ai/philterd/philter/api/controllers");

    /** Endpoints deliberately served without authentication, and therefore without a scope. */
    private static final Set<String> UNAUTHENTICATED = Set.of(
            "StatusApiController.status",
            "SigningApiController.getSigningKey",
            "SigningApiController.getSigningKeyById");

    @Test
    @DisplayName("Every API handler declares a required scope")
    void everyHandlerDeclaresAScope() throws Exception {

        final List<String> unannotated = new ArrayList<>();
        int checked = 0;

        for (final Class<?> controller : controllerClasses()) {
            for (final Method method : controller.getDeclaredMethods()) {

                if (method.getAnnotation(RequestMapping.class) == null) {
                    continue;
                }

                final String name = controller.getSimpleName() + "." + method.getName();

                if (UNAUTHENTICATED.contains(name)) {
                    continue;
                }

                checked++;

                if (method.getAnnotation(RequiresScope.class) == null) {
                    unannotated.add(name);
                }

            }
        }

        assertTrue(checked > 40, "the scan must find the API handlers; found only " + checked);

        if (!unannotated.isEmpty()) {
            fail("These API handlers declare no @RequiresScope, so the interceptor will refuse them: "
                    + unannotated + ". Annotate each with the scope it requires, or add it to "
                    + "UNAUTHENTICATED here if it is deliberately served without an API key.");
        }

    }

    /** Loads every controller class from the controllers package. */
    private static List<Class<?>> controllerClasses() throws IOException, ClassNotFoundException {

        final List<Class<?>> classes = new ArrayList<>();

        try (final Stream<Path> files = Files.list(CONTROLLERS)) {
            for (final Path file : files.toList()) {

                final String fileName = file.getFileName().toString();

                if (!fileName.endsWith("ApiController.java") || fileName.startsWith("Abstract")) {
                    continue;
                }

                classes.add(Class.forName("ai.philterd.philter.api.controllers."
                        + fileName.substring(0, fileName.length() - ".java".length())));

            }
        }

        assertTrue(classes.size() >= 10, "the scan must find the controllers; found " + classes.size());

        return classes;

    }

}

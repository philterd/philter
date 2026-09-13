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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link ApiKeyScopeCoverageTest} proves every handler declares <em>a</em> scope. This proves each one
 * declares the <em>right</em> scope, which is otherwise unchecked: a copy-pasted {@code @RequiresScope}
 * naming a weaker scope compiles, passes every controller test, and silently widens what a key can do.
 *
 * <p>The expected assignment is not restated here. It is read from the table in
 * {@code docs/docs/account/api_keys.md}, which is what a user consults when granting a key. One table,
 * checked both ways: a scope changed in code without the documentation fails, and so does a documented
 * endpoint that no longer exists.
 */
class ApiKeyScopeAssignmentTest {

    private static final Path CONTROLLERS = Path.of("src/main/java/ai/philterd/philter/api/controllers");
    private static final Path SCOPE_TABLE = Path.of("docs/docs/account/api_keys.md");

    /** Served without an API key, so they carry no scope. Documented under "Unauthenticated endpoints". */
    private static final Set<String> UNAUTHENTICATED = Set.of(
            "GET /api/health",
            "GET /api/signing-key",
            "GET /api/signing-key/{keyId}");

    @Test
    @DisplayName("Every handler requires the scope the documentation promises")
    void annotatedScopesMatchTheDocumentedTable() throws Exception {

        final Map<String, String> annotated = annotatedScopes();
        final Map<String, String> documented = documentedScopes();

        assertTrue(annotated.size() > 40, "the scan must find the API handlers; found " + annotated.size());

        final List<String> problems = new ArrayList<>();

        for (final Map.Entry<String, String> entry : annotated.entrySet()) {
            final String documentedScope = documented.get(entry.getKey());
            if (documentedScope == null) {
                problems.add(entry.getKey() + " requires " + entry.getValue() + " but is in no documented scope");
            } else if (!documentedScope.equals(entry.getValue())) {
                problems.add(entry.getKey() + " requires " + entry.getValue()
                        + " but is documented under " + documentedScope);
            }
        }

        for (final String endpoint : documented.keySet()) {
            if (!annotated.containsKey(endpoint)) {
                problems.add(endpoint + " is documented under " + documented.get(endpoint) + " but no handler serves it");
            }
        }

        if (!problems.isEmpty()) {
            fail("The scopes the API enforces and the scopes " + SCOPE_TABLE + " promises disagree:\n  "
                    + String.join("\n  ", problems)
                    + "\nChange whichever is wrong. A widened scope here is a widened API key.");
        }

    }

    @Test
    @DisplayName("A read scope does not admit a write, and a write is not demanded for a read")
    void scopesMatchTheDirectionOfTheOperation() throws Exception {

        // A safeguard for endpoints added later, when the table above is edited to match the code
        // rather than the other way round: the suffix must still agree with the HTTP method.
        final List<String> problems = new ArrayList<>();

        for (final Map.Entry<String, String> entry : annotatedScopes().entrySet()) {

            final String httpMethod = entry.getKey().split(" ")[0];
            final String scope = entry.getValue();
            final boolean mutating = !"GET".equals(httpMethod);

            // Compiling PhiSQL persists nothing, so it is a POST that legitimately reads.
            if ("POST /api/policies/compile".equals(entry.getKey())) {
                continue;
            }

            if (mutating && scope.endsWith(":read")) {
                problems.add(entry.getKey() + " changes state but requires only " + scope);
            }
            if (!mutating && scope.endsWith(":write")) {
                problems.add(entry.getKey() + " only reads but demands " + scope);
            }

        }

        assertEquals(List.of(), problems, "scope suffixes must match what the endpoint does");

    }

    /** Every scoped handler, as "METHOD /path" to the scope it requires. */
    private static Map<String, String> annotatedScopes() throws Exception {

        final Map<String, String> scopes = new TreeMap<>();

        for (final Class<?> controller : controllerClasses()) {
            for (final Method method : controller.getDeclaredMethods()) {

                final RequestMapping mapping = method.getAnnotation(RequestMapping.class);
                if (mapping == null) {
                    continue;
                }

                final String endpoint = mapping.method()[0].name() + " " + mapping.value()[0];
                final RequiresScope required = method.getAnnotation(RequiresScope.class);

                if (required == null) {
                    assertTrue(UNAUTHENTICATED.contains(endpoint),
                            endpoint + " declares no scope and is not a documented unauthenticated endpoint");
                    continue;
                }

                // Several handlers can share a path by content type; they must agree on the scope, or
                // the weaker one decides what the path costs.
                final String previous = scopes.put(endpoint, required.value().getScope());
                if (previous != null && !previous.equals(required.value().getScope())) {
                    fail(endpoint + " is served by handlers requiring different scopes: "
                            + previous + " and " + required.value().getScope());
                }

            }
        }

        return scopes;

    }

    /** The "Scopes and the endpoints they cover" table, as "METHOD /path" to the scope documented for it. */
    private static Map<String, String> documentedScopes() throws IOException {

        final Map<String, String> scopes = new LinkedHashMap<>();
        final Pattern row = Pattern.compile("^\\| `([a-z:-]+)` \\| (.+) \\|$");
        final Pattern endpoint = Pattern.compile("`([A-Z]+) (/api/[^`]*)`");

        for (final String line : Files.readAllLines(SCOPE_TABLE)) {

            final Matcher rowMatcher = row.matcher(line.trim());
            if (!rowMatcher.matches()) {
                continue;
            }

            final Matcher endpoints = endpoint.matcher(rowMatcher.group(2));
            while (endpoints.find()) {
                scopes.put(endpoints.group(1) + " " + endpoints.group(2), rowMatcher.group(1));
            }

        }

        assertTrue(scopes.size() > 40, "the scope table in " + SCOPE_TABLE + " was not parsed; found " + scopes.size());

        return scopes;

    }

    private static List<Class<?>> controllerClasses() throws IOException, ClassNotFoundException {

        final List<Class<?>> classes = new ArrayList<>();
        final Set<String> names = new LinkedHashSet<>();

        try (final Stream<Path> files = Files.list(CONTROLLERS)) {
            for (final Path file : files.toList()) {
                final String name = file.getFileName().toString();
                if (name.endsWith("ApiController.java")) {
                    names.add(name.substring(0, name.length() - ".java".length()));
                }
            }
        }

        for (final String name : names) {
            classes.add(Class.forName("ai.philterd.philter.api.controllers." + name));
        }

        return classes;

    }

}

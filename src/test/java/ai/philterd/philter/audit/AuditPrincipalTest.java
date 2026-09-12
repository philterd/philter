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
package ai.philterd.philter.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AuditPrincipalIT} covers the endpoints it drives; this covers the call sites it does not. A
 * hard-coded null principal is how policy deletion, rollback, and version history reads came to be
 * audited anonymously.
 */
class AuditPrincipalTest {

    private static final Path MAIN = Path.of("src/main/java");

    /**
     * Matches a call whose third argument, the principal, is the literal null. Arg one is matched
     * loosely (it may be a call with its own arguments); excluding ';' keeps the match in one call.
     */
    private static final Pattern ANONYMOUS_CALL =
            Pattern.compile("auditEvent\\([^;]*?AuditLogEvent\\.(\\w+)\\s*,\\s*null\\s*[,)]");

    /**
     * {@code ClassName.EVENT} for events with no principal to record: an unissued or malformed key
     * identifies nobody, and the signing key is generated at startup, before any request. Listed
     * individually rather than exempted by class.
     */
    private static final Set<String> NO_PRINCIPAL_EXISTS = Set.of(
            "ApiAuthenticationFilter.API_AUTHENTICATION_FAILED",
            "SigningKeyDataService.SIGNING_KEY_GENERATED");

    @Test
    @DisplayName("No audit event is written with a hard-coded null principal")
    void noAuditEventIsWrittenAnonymously() throws IOException {

        final List<String> anonymous = new ArrayList<>();
        int callSites = 0;

        try (final Stream<Path> files = Files.walk(MAIN)) {
            for (final Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {

                final String className = file.getFileName().toString().replace(".java", "");

                // Collapsed, so a call split across lines matches like a single-line one.
                final String source = Files.readString(file).replaceAll("\\s+", " ");
                callSites += source.split("auditEvent\\(", -1).length - 1;

                final Matcher matcher = ANONYMOUS_CALL.matcher(source);

                while (matcher.find()) {
                    final String site = className + "." + matcher.group(1);
                    if (!NO_PRINCIPAL_EXISTS.contains(site)) {
                        anonymous.add(site);
                    }
                }

            }
        }

        // A renamed publisher method or moved source root would otherwise pass silently.
        assertTrue(callSites > 20, "the scan must reach the audit call sites; it found " + callSites);

        assertEquals(List.of(), anonymous.stream().distinct().sorted().toList(),
                "these call sites audit an action without recording who performed it, so the audit log "
                        + "cannot answer the question it exists to answer");

    }

}

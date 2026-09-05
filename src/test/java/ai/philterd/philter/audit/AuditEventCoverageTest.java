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

import ai.philterd.philter.model.AuditLogEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The event list reads as an inventory of what the audit log covers, so an event nobody emits is a
 * promise nobody keeps: eleven of the sixty-two declared here were emitted nowhere, and one of them
 * was documented as recording administrator settings changes that were not recorded at all.
 */
class AuditEventCoverageTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Path DOCS = Path.of("docs/docs/auditing.md");

    @Test
    @DisplayName("Every declared audit event is emitted somewhere")
    void everyEventIsEmitted() throws IOException {

        final String source = sourceExcludingTheEnum();
        final List<String> never = new ArrayList<>();

        for (final AuditLogEvent event : AuditLogEvent.values()) {
            if (!source.contains("AuditLogEvent." + event.name())) {
                never.add(event.name());
            }
        }

        assertTrue(AuditLogEvent.values().length > 40,
                "the scan must find the events; found " + AuditLogEvent.values().length);
        assertEquals(List.of(), never,
                "these events are declared but nothing emits them, so the list overstates what is audited");

    }

    @Test
    @DisplayName("Every declared audit event is documented")
    void everyEventIsDocumented() throws IOException {

        final String documented = Files.readString(DOCS);
        final List<String> undocumented = new ArrayList<>();

        for (final AuditLogEvent event : AuditLogEvent.values()) {
            if (!documented.contains("`" + event.getAuditLogEvent() + "`")) {
                undocumented.add(event.getAuditLogEvent());
            }
        }

        assertEquals(List.of(), undocumented,
                "an event a deployment can see in its audit log must be listed in auditing.md");

    }

    private static String sourceExcludingTheEnum() throws IOException {
        try (final Stream<Path> files = Files.walk(MAIN)) {
            final StringBuilder source = new StringBuilder();
            for (final Path file : files.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> !f.endsWith("AuditLogEvent.java")).toList()) {
                source.append(Files.readString(file));
            }
            return source.toString();
        }
    }

}

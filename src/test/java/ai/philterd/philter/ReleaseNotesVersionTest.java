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
package ai.philterd.philter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The changelog told readers this release ships Phileas 4.2.0 while the build shipped 4.4.0. Nobody
 * noticed, because a dependency bump lands in the pom and the release notes are written once.
 *
 * <p>The rule is that the changelog may be less precise than the build — "Vaadin 25" for 25.2.6 is
 * fine — but never different. A version it names must be a prefix of the version that is built.
 */
class ReleaseNotesVersionTest {

    private static final Path POM = Path.of("pom.xml");
    private static final Path CHANGELOG = Path.of("CHANGELOG.md");

    /** The pom property holding each dependency's version, by the name the changelog calls it. */
    private static final Map<String, String> PROPERTIES = Map.of(
            "Phileas", "phileas.version",
            "Vaadin", "vaadin.version",
            "Java", "java.version");

    @Test
    @DisplayName("Every version the changelog names is the version that is built")
    void theChangelogNamesTheVersionsThatAreBuilt() throws IOException {

        final String changelog = Files.readString(CHANGELOG);
        final Map<String, String> claimed = new LinkedHashMap<>();

        for (final String dependency : PROPERTIES.keySet()) {
            final Matcher named = Pattern.compile(dependency + " (\\d+(?:\\.\\d+)*)").matcher(changelog);
            if (named.find()) {
                claimed.put(dependency, named.group(1));
            }
        }

        // Every dependency this test knows about must actually be named, or it would quietly check
        // nothing the day someone rewords the summary.
        assertEquals(PROPERTIES.keySet(), claimed.keySet(),
                "the changelog no longer names these; the check would silently stop checking them");

        final List<String> wrong = new ArrayList<>();

        for (final Map.Entry<String, String> claim : claimed.entrySet()) {

            final String built = property(PROPERTIES.get(claim.getKey()));

            if (!describes(claim.getValue(), built)) {
                wrong.add(claim.getKey() + ": the changelog says " + claim.getValue()
                        + ", the build uses " + built);
            }

        }

        assertEquals(List.of(), wrong,
                "CHANGELOG.md describes a release that is not the one being built");

    }

    @Test
    @DisplayName("A shorter version in the notes is allowed, a different one is not")
    void aClaimMayBeLessPreciseButNeverWrong() {

        assertTrue(describes("4.4.0", "4.4.0"));
        assertTrue(describes("25", "25.2.6"), "the notes may name a major alone");
        assertTrue(describes("25.2", "25.2.6"));

        assertFalse(describes("4.2.0", "4.4.0"), "this is the drift the check exists for");
        assertFalse(describes("25", "26.0.1"), "a major bump the notes missed");
        assertFalse(describes("4.4", "4.40.0"), "the boundary must fall on a dot, not mid-number");
        assertFalse(describes("4.4.0", "4.4"), "the notes must not claim more than is built");

    }

    /** Whether a version named in the notes describes the version that is built. */
    private static boolean describes(final String claimed, final String built) {
        return built.equals(claimed) || built.startsWith(claimed + ".");
    }

    private static String property(final String name) throws IOException {

        final Matcher value = Pattern.compile("<" + Pattern.quote(name) + ">([^<]+)<")
                .matcher(Files.readString(POM));

        assertTrue(value.find(), "pom.xml has no <" + name + "> property");

        return value.group(1).trim();

    }

}

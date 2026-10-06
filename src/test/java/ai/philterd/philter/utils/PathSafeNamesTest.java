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
package ai.philterd.philter.utils;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathSafeNamesTest {

    /** Each of these, percent-encoded in a path, was refused before reaching Philter (probed on Tomcat). */
    @ParameterizedTest
    @ValueSource(strings = {"LIT/2026/001", "a\\b", "a;b", "a%b", "a%2Fb", ".", "..", "a\u0000b", "a\tb", "a\nb"})
    void refusesWhatCannotBeUsedInAPath(final String name) {
        assertFalse(PathSafeNames.isPathSafe(name), name);
    }

    /** These round-trip through an encoded path, so they stay allowed. */
    @ParameterizedTest
    @ValueSource(strings = {"LIT-2026-001", "Smith v. Jones 2026", "a.b", "...", ".hidden", "a?b", "a#b", "a+b",
            "a&b", "a:b", "a'b", "a(b)", "Projet Cardinal é", "Employee-Names-2024"})
    void allowsWhatCanBeUsedInAPath(final String name) {
        assertTrue(PathSafeNames.isPathSafe(name), name);
    }

}

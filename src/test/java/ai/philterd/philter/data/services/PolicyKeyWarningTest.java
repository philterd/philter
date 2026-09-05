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
package ai.philterd.philter.data.services;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Policies are not encrypted at rest, so a key written into one is stored beside the values it
 * encrypted. The env: prefix keeps it out; this is what notices when it was not used.
 */
class PolicyKeyWarningTest {

    private static List<String> sections(final String policyJson) {
        return PolicyDataService.keySectionsHoldingALiteralKey(policyJson);
    }

    @Test
    @DisplayName("A literal crypto key is noticed")
    void aLiteralCryptoKeyIsNoticed() {
        assertEquals(List.of("crypto"),
                sections("{\"name\":\"p\",\"crypto\":{\"key\":\"0011223344556677889aabbccddeeff00112233445566778899aabbccddeeff0\"}}"));
    }

    @Test
    @DisplayName("A key read from the environment is not a key in the policy")
    void anEnvironmentReferenceIsNotAKey() {
        assertEquals(List.of(),
                sections("{\"name\":\"p\",\"crypto\":{\"key\":\"env:CRYPTO_KEY\"}}"));
    }

    @Test
    @DisplayName("The FPE section is checked too, including its tweak")
    void theFpeSectionIsChecked() {
        assertEquals(List.of("fpe"), sections("{\"fpe\":{\"key\":\"AABB\",\"tweak\":\"env:TWEAK\"}}"));
        assertEquals(List.of("fpe"), sections("{\"fpe\":{\"key\":\"env:K\",\"tweak\":\"0011\"}}"));
        assertEquals(List.of(), sections("{\"fpe\":{\"key\":\"env:K\",\"tweak\":\"env:T\"}}"));
    }

    @Test
    @DisplayName("Both sections are reported when both hold a key")
    void bothSectionsAreReported() {
        assertEquals(List.of("crypto", "fpe"),
                sections("{\"crypto\":{\"key\":\"AA\"},\"fpe\":{\"key\":\"BB\"}}"));
    }

    @Test
    @DisplayName("A section is reported once however many of its fields hold a key")
    void aSectionIsReportedOnce() {
        assertEquals(List.of("fpe"), sections("{\"fpe\":{\"key\":\"AA\",\"tweak\":\"BB\"}}"));
    }

    @Test
    @DisplayName("A policy with no encryption settings has nothing to report")
    void aPolicyWithoutKeysIsQuiet() {
        assertEquals(List.of(), sections("{\"name\":\"p\",\"identifiers\":{\"ssn\":{}}}"));
        assertEquals(List.of(), sections("{}"));
    }

    @Test
    @DisplayName("An absent, empty or non-string key is not a key")
    void anAbsentOrEmptyKeyIsNotAKey() {
        assertEquals(List.of(), sections("{\"crypto\":{}}"));
        assertEquals(List.of(), sections("{\"crypto\":{\"key\":\"\"}}"));
        assertEquals(List.of(), sections("{\"crypto\":{\"key\":\"   \"}}"));
        assertEquals(List.of(), sections("{\"crypto\":{\"key\":{\"nested\":\"AA\"}}}"));
        assertEquals(List.of(), sections("{\"crypto\":\"not-an-object\"}"));
    }

    @Test
    @DisplayName("A policy this check cannot read is left to validation to reject")
    void anUnreadablePolicyIsNotAnError() {
        assertEquals(List.of(), sections("not json at all"));
        assertEquals(List.of(), sections(""));
        assertEquals(List.of(), sections("null"));
    }

    @Test
    @DisplayName("The warning names the policy, the section, and the way out")
    void theWarningIsActionable() {

        final CapturingAppender appender = new CapturingAppender();
        appender.start();
        final org.apache.logging.log4j.core.Logger logger = (org.apache.logging.log4j.core.Logger)
                org.apache.logging.log4j.LogManager.getLogger(PolicyDataService.class);
        logger.addAppender(appender);

        try {
            PolicyDataService.warnAboutLiteralKeys("my-policy", "{\"crypto\":{\"key\":\"AABB\"}}");
        } finally {
            logger.removeAppender(appender);
            appender.stop();
        }

        final String logged = String.join("\n", appender.messages);
        assertTrue(logged.contains("my-policy"), "the policy must be named: " + logged);
        assertTrue(logged.contains("crypto"), "the section must be named: " + logged);
        assertTrue(logged.contains("env:"), "the way out must be given: " + logged);
        assertTrue(logged.contains("not encrypted at rest"), "the reason must be given: " + logged);

    }

    private static final class CapturingAppender
            extends org.apache.logging.log4j.core.appender.AbstractAppender {

        private final List<String> messages = new java.util.ArrayList<>();

        private CapturingAppender() {
            super("capture", null, null, true, org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY);
        }

        @Override
        public void append(final org.apache.logging.log4j.core.LogEvent event) {
            messages.add(event.getMessage().getFormattedMessage());
        }
    }

}

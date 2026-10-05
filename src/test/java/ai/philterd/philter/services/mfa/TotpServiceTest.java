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
package ai.philterd.philter.services.mfa;

import org.apache.commons.codec.binary.Base32;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TotpServiceTest {

    private final TotpService totp = new TotpService();

    /** RFC 6238 appendix B, SHA-1, truncated to six digits: the codes every authenticator app computes. */
    @Test
    void matchesTheRfc6238TestVectors() {
        final String secret = new Base32().encodeToString("12345678901234567890".getBytes(StandardCharsets.US_ASCII));
        assertEquals("287082", totp.codeAt(secret, 59L / 30));
        assertEquals("081804", totp.codeAt(secret, 1111111109L / 30));
        assertEquals("050471", totp.codeAt(secret, 1111111111L / 30));
        assertEquals("005924", totp.codeAt(secret, 1234567890L / 30));
        assertEquals("279037", totp.codeAt(secret, 2000000000L / 30));
    }

    @Test
    void acceptsTheAdjacentStepsAndReportsWhichMatched() {
        final String secret = totp.generateSecret();
        final long now = TotpService.currentTimeStep();
        assertEquals(now + 1, totp.matchingTimeStep(secret, totp.codeAt(secret, now + 1)));
        assertEquals(TotpService.NO_MATCH, totp.matchingTimeStep(secret, totp.codeAt(secret, now + 5)));
        assertTrue(totp.verifyCode(secret, " " + totp.codeAt(secret, now) + " "), "surrounding space is ignored");
    }

    @Test
    void refusesMissingOrMalformedInputWithoutThrowing() {
        final String secret = totp.generateSecret();
        assertFalse(totp.verifyCode(secret, null));
        assertFalse(totp.verifyCode(secret, ""));
        assertFalse(totp.verifyCode(null, "123456"));
        assertFalse(totp.verifyCode("not base32 !!", "123456"));
    }

    @Test
    void describesTheSecretForAuthenticatorApps() {
        assertEquals(32, totp.generateSecret().length(), "160 bits, Base32, unpadded");
        assertEquals("otpauth://totp/Philter:jordan%40example.com?secret=ABCD&issuer=Philter&algorithm=SHA1&digits=6&period=30",
                totp.otpauthUri("jordan@example.com", "ABCD"));
    }

}

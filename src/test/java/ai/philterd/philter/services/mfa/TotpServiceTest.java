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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TotpServiceTest {

    private static final long TIME_STEP_SECONDS = 30L;

    private final TotpService totpService = new TotpService();

    @Test
    void generateSecretIsDecodableBase32WithoutPadding() {
        final String secret = totpService.generateSecret();
        assertNotNull(secret);
        assertFalse(secret.contains("="), "the secret must not contain Base32 padding");
        assertEquals(20, new Base32().decode(secret).length, "the secret must be 160 bits");
    }

    @Test
    void generateSecretIsRandomPerCall() {
        assertNotEquals(totpService.generateSecret(), totpService.generateSecret());
    }

    @Test
    void otpauthUriContainsIssuerLabelAndSecret() {
        final String secret = totpService.generateSecret();
        final String uri = totpService.otpauthUri("alice@example.com", secret);
        assertTrue(uri.startsWith("otpauth://totp/"));
        assertTrue(uri.contains("secret=" + secret));
        assertTrue(uri.contains("issuer=Philter"));
    }

    @Test
    void verifyCodeAcceptsTheCurrentStepAndTheNextStep() {
        final String secret = totpService.generateSecret();
        final long step = currentStep();

        // The current step is always accepted; the next step is within the +/-1 skew tolerance. Both
        // remain true even if the 30-second boundary is crossed between this computation and verifyCode.
        assertTrue(totpService.verifyCode(secret, expectedCode(secret, step)));
        assertTrue(totpService.verifyCode(secret, expectedCode(secret, step + 1)));
    }

    @Test
    void verifyCodeRejectsCodesOutsideTheWindow() {
        final String secret = totpService.generateSecret();
        final long step = currentStep();

        // Two or more steps away on either side is outside the +/-1 tolerance regardless of a single
        // boundary crossing.
        assertFalse(totpService.verifyCode(secret, expectedCode(secret, step - 2)));
        assertFalse(totpService.verifyCode(secret, expectedCode(secret, step + 3)));
    }

    @Test
    void verifyCodeRejectsNullBlankAndMalformedInput() {
        final String secret = totpService.generateSecret();
        assertFalse(totpService.verifyCode(secret, null));
        assertFalse(totpService.verifyCode(secret, ""));
        assertFalse(totpService.verifyCode(secret, "   "));
        assertFalse(totpService.verifyCode(secret, "abcdef"));
        assertFalse(totpService.verifyCode(null, "123456"));
        assertFalse(totpService.verifyCode("not valid base32 !!!", "123456"));
    }

    private static long currentStep() {
        return Instant.now().getEpochSecond() / TIME_STEP_SECONDS;
    }

    /**
     * Independent RFC 6238 (HMAC-SHA1, 6-digit) computation for a given time step, used to cross-check
     * {@link TotpService#verifyCode} against a separate implementation rather than itself.
     */
    private static String expectedCode(final String secret, final long timeStep) {
        try {
            final byte[] key = new Base32().decode(secret);
            final byte[] data = ByteBuffer.allocate(Long.BYTES).putLong(timeStep).array();
            final Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            final byte[] hash = mac.doFinal(data);
            final int offset = hash[hash.length - 1] & 0x0F;
            final int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            return String.format("%06d", binary % 1_000_000);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }
}

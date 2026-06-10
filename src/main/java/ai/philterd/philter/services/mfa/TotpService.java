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

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.apache.commons.codec.binary.Base32;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

/**
 * Self-contained TOTP (RFC 6238) implementation for time-based one-time-password MFA. It uses only the
 * JDK's HMAC ({@code javax.crypto.Mac}) and commons-codec Base32 for the codes; ZXing renders the
 * enrollment QR code. This replaces an external TOTP dependency so the algorithm stays auditable and
 * its transitive dependencies stay current.
 *
 * <p>The shared secret is a Base32 string compatible with authenticator apps (Google Authenticator,
 * Authy, 1Password, etc.). Verification accepts the code for the current 30-second step plus the
 * adjacent steps, tolerating small clock skew.
 */
@Service
public class TotpService {

    private static final String ISSUER = "Philter";
    private static final String HMAC_ALGORITHM = "HmacSHA1";
    private static final long TIME_STEP_SECONDS = 30L;
    private static final int DIGITS = 6;
    /** How many adjacent 30-second windows on each side of "now" are accepted (clock-skew tolerance). */
    private static final int WINDOW = 1;

    private final SecureRandom secureRandom = new SecureRandom();

    /** Generates a new Base32-encoded shared secret (160 bits) for an authenticator app. */
    public String generateSecret() {
        final byte[] bytes = new byte[20];
        secureRandom.nextBytes(bytes);
        return new Base32().encodeToString(bytes).replace("=", "");
    }

    /**
     * Builds the {@code otpauth://} URI an authenticator app reads from the enrollment QR code. The
     * label is {@code Philter:<username>} so the entry is identifiable in the app.
     */
    public String otpauthUri(final String username, final String secret) {
        final String label = encode(ISSUER) + ":" + encode(username);
        return "otpauth://totp/" + label
                + "?secret=" + secret
                + "&issuer=" + encode(ISSUER)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + TIME_STEP_SECONDS;
    }

    /** Renders the given {@code otpauth://} URI as a PNG QR code encoded as a {@code data:} URI. */
    public String qrCodeDataUri(final String otpauthUri) {
        try {
            final BitMatrix matrix = new QRCodeWriter().encode(otpauthUri, BarcodeFormat.QR_CODE, 240, 240);
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (final Exception e) {
            throw new IllegalStateException("Failed to render the MFA QR code.", e);
        }
    }

    /**
     * Verifies a 6-digit TOTP code against the secret, accepting the current step and one step on each
     * side. Returns {@code false} for null/blank input or a malformed secret rather than throwing.
     */
    public boolean verifyCode(final String secret, final String code) {
        if (secret == null || secret.isBlank() || code == null || code.isBlank()) {
            return false;
        }
        final String trimmed = code.trim();
        final byte[] key;
        try {
            key = new Base32().decode(secret);
        } catch (final Exception e) {
            return false;
        }
        final long currentStep = Instant.now().getEpochSecond() / TIME_STEP_SECONDS;
        for (long i = -WINDOW; i <= WINDOW; i++) {
            if (constantTimeEquals(generateCode(key, currentStep + i), trimmed)) {
                return true;
            }
        }
        return false;
    }

    private String generateCode(final byte[] key, final long timeStep) {
        try {
            final byte[] data = ByteBuffer.allocate(Long.BYTES).putLong(timeStep).array();
            final Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            final byte[] hash = mac.doFinal(data);
            final int offset = hash[hash.length - 1] & 0x0F;
            final int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            final int otp = binary % (int) Math.pow(10, DIGITS);
            return String.format("%0" + DIGITS + "d", otp);
        } catch (final Exception e) {
            throw new IllegalStateException("Failed to generate a TOTP code.", e);
        }
    }

    private static String encode(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static boolean constantTimeEquals(final String a, final String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

}

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
package ai.philterd.philter.services.signing;

import ai.philterd.philter.data.entities.AdminSettingsEntity;
import ai.philterd.philter.data.services.AdminSettingsDataService;
import ai.philterd.philter.data.services.SigningKeyDataService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Signs redaction API responses with an ES256 (ECDSA P-256 / SHA-256) JWT.
 *
 * <p>Signing is opt-in and disabled by default. When enabled, a compact JWT is produced and
 * returned in the {@value #SIGNATURE_HEADER} response header on every successful 2xx response
 * from {@code POST /api/filter} (text) and {@code POST /api/explain}. Error responses are
 * never signed.
 *
 * <p>The JWT payload binds the SHA-256 hash of the response body, the applied policy name and
 * version, a per-response UUID, and the issue timestamp. Consumers verify the signature using
 * the operator's public key, available from {@code GET /api/signing-key}.
 */
public class SigningService {

    public static final String SIGNATURE_HEADER = "X-Philter-Signature";

    private final SigningKeyDataService signingKeyDataService;
    private final AdminSettingsDataService adminSettingsDataService;

    public SigningService(final SigningKeyDataService signingKeyDataService,
                         final AdminSettingsDataService adminSettingsDataService) {
        this.signingKeyDataService = signingKeyDataService;
        this.adminSettingsDataService = adminSettingsDataService;
    }

    public boolean isSigningEnabled() {
        final AdminSettingsEntity settings = adminSettingsDataService.findAdminSettings();
        return settings != null && settings.isSigningEnabled();
    }

    /**
     * Produces a compact ES256 JWT that attests the response body and its governance context.
     *
     * @param responseBody  the exact bytes that were written to the HTTP response body
     * @param policyName    the policy name applied during redaction
     * @param policyVersion the policy revision number applied during redaction
     * @param documentId    the document ID returned in the {@code X-Document-Id} response header
     * @return compact JWT string (header.payload.signature)
     */
    public String sign(final String responseBody, final String policyName,
                       final int policyVersion, final String documentId) throws Exception {

        final String bodyHash = sha256Hex(responseBody.getBytes(StandardCharsets.UTF_8));
        final long iat = System.currentTimeMillis() / 1000L;

        final String header = base64url("{\"alg\":\"ES256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        final String payload = base64url(buildPayload(bodyHash, policyName, policyVersion, documentId, iat)
                .getBytes(StandardCharsets.UTF_8));

        final String signingInput = header + "." + payload;

        final Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(signingKeyDataService.getPrivateKey());
        signer.update(signingInput.getBytes(StandardCharsets.UTF_8));
        final byte[] derSig = signer.sign();
        final byte[] p1363Sig = derToP1363(derSig);

        return signingInput + "." + base64url(p1363Sig);
    }

    private static String buildPayload(final String bodyHash, final String policyName,
                                       final int policyVersion, final String documentId, final long iat) {
        return "{\"bodyHash\":\"" + escape(bodyHash) + "\""
                + ",\"policyName\":\"" + escape(policyName) + "\""
                + ",\"policyVersion\":" + policyVersion
                + ",\"documentId\":\"" + escape(documentId) + "\""
                + ",\"iat\":" + iat + "}";
    }

    private static String sha256Hex(final byte[] bytes) throws Exception {
        final MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(bytes));
    }

    private static String base64url(final byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Converts a DER-encoded ECDSA signature to IEEE P1363 (fixed-length) format required by JWT ES256.
     *
     * <p>DER: {@code 30 [total-len] 02 [r-len] R 02 [s-len] S} where R and S may have a leading
     * zero byte to disambiguate from a negative two's-complement value. P1363 is simply
     * R || S, each left-padded with zeros to exactly 32 bytes.
     */
    static byte[] derToP1363(final byte[] der) {
        int offset = 2; // skip 30 and total-length byte
        final int rLen = der[offset + 1] & 0xFF;
        offset += 2;
        byte[] r = Arrays.copyOfRange(der, offset, offset + rLen);
        offset += rLen;
        final int sLen = der[offset + 1] & 0xFF;
        offset += 2;
        byte[] s = Arrays.copyOfRange(der, offset, offset + sLen);

        // Strip DER leading zero (present when the high bit is set)
        if (r.length > 32 && r[0] == 0) r = Arrays.copyOfRange(r, 1, r.length);
        if (s.length > 32 && s[0] == 0) s = Arrays.copyOfRange(s, 1, s.length);

        final byte[] result = new byte[64];
        System.arraycopy(r, 0, result, 32 - r.length, r.length);
        System.arraycopy(s, 0, result, 64 - s.length, s.length);
        return result;
    }

    private static String escape(final String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

}

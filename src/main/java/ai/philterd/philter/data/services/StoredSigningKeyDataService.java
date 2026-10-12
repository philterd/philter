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

import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.SigningKeyEntity;
import ai.philterd.philter.model.AuditLogEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;

/**
 * The rules for output signing keys, the same in every storage implementation: how a key is generated or loaded
 * from {@code PHILTER_SIGNING_KEY_PATH}, how its id is derived, who may rotate it, how the active key is chosen
 * when several instances start at once, and how public keys are published. An implementation stores the keys,
 * the private halves encrypted, and the pointer to the active one.
 *
 * <p>An implementation calls {@link #start()} at the end of its constructor, once it can read and write keys.
 */
public abstract class StoredSigningKeyDataService implements SigningKeyDataService {

    private static final Logger LOGGER = LogManager.getLogger(SigningKeyDataService.class);

    /** The key and its id together, so no reader can see half a rotation. */
    private record ActiveKey(KeyPair keyPair, String keyId) { }

    protected final AuditEventPublisher auditEventPublisher;
    private final String keyPath;
    private final boolean externallyManaged;
    private volatile ActiveKey active;

    protected StoredSigningKeyDataService(final AuditEventPublisher auditEventPublisher, final String keyPath) {
        this.auditEventPublisher = auditEventPublisher;
        this.keyPath = keyPath;
        this.externallyManaged = keyPath != null && !keyPath.isBlank();
    }

    /** Stores a key pair, the private half encrypted. */
    protected abstract void insertKey(final SigningKeyEntity key);

    /** Stores only the public half of a key under its id, unless a key with that id is already stored. */
    protected abstract void retainPublicKey(final String keyId, final byte[] publicKeyEncoded);

    /** The key with this id, with its private half, or null. */
    protected abstract SigningKeyEntity findKey(final String keyId);

    /** The public half of the key with this id, or null. */
    protected abstract byte[] findPublicKey(final String keyId);

    /** The id of the active key, or null when none has been published. */
    protected abstract String findActiveKeyId();

    /**
     * Publishes this key as the active one only when none has been published, and returns whether it was. Of
     * several instances starting at once, exactly one publishes.
     */
    protected abstract boolean publishFirstActiveKey(final String keyId);

    /** Makes this key the active one, and returns false when no key had been published yet. */
    protected abstract boolean publishActiveKey(final String keyId);

    /** Whether the user exists, is an administrator, and is not deactivated, read from the stored users. */
    protected abstract boolean isActiveAdministrator(final String userId);

    /** Loads the external key, or makes sure an active key exists. */
    protected final void start() {
        if (externallyManaged) {
            try {
                final KeyPair pair = loadFromPemFile(keyPath);
                active = new ActiveKey(pair, keyIdFor(pair.getPublic()));
                // Retain only the public half so old PEM signatures remain verifiable after restart.
                retainPublicKey(active.keyId(), pair.getPublic().getEncoded());
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load signing key from PHILTER_SIGNING_KEY_PATH: " + keyPath, e);
            }
        } else {
            if (findActiveKeyId() == null) {
                final ActiveKey candidate = generateAndPersist();
                // Concurrent bootstraps may retain unused candidates, but only one pointer wins.
                if (publishFirstActiveKey(candidate.keyId())) {
                    auditEventPublisher.auditEvent(null, AuditLogEvent.SIGNING_KEY_GENERATED,
                            null, null, null, null);
                }
            }
            currentActiveKey();
        }
    }

    @Override
    public boolean isExternallyManaged() {
        return externallyManaged;
    }

    @Override
    public String regenerate(final String requestId, final String actingUserId,
                             final String clientIpAddress, final String details) {
        if (actingUserId == null || !isActiveAdministrator(actingUserId)) {
            throw new AccessDeniedException("Current administrator authorization required.");
        }
        if (externallyManaged) {
            throw new IllegalStateException("Signing key is managed by PHILTER_SIGNING_KEY_PATH; replace the file and restart all instances.");
        }
        final ActiveKey candidate = generateAndPersist();
        if (!publishActiveKey(candidate.keyId())) {
            throw new IllegalStateException("Active signing-key pointer is missing; rotation was not published.");
        }
        auditEventPublisher.auditEvent(requestId, AuditLogEvent.SIGNING_KEY_REGENERATED,
                actingUserId, null, clientIpAddress, details);
        return candidate.keyId();
    }

    private ActiveKey currentActiveKey() {
        if (externallyManaged) {
            return active;
        }
        final String keyId = findActiveKeyId();
        if (keyId == null) {
            throw new IllegalStateException("Active signing-key pointer is missing.");
        }
        final ActiveKey cached = active;
        if (cached != null && keyId.equals(cached.keyId())) {
            return cached;
        }
        final SigningKeyEntity stored = findKey(keyId);
        if (stored == null) {
            throw new IllegalStateException("Active signing key is missing: " + keyId);
        }
        final KeyPair pair = fromEntity(stored);
        if (!keyId.equals(keyIdFor(pair.getPublic()))) {
            throw new IllegalStateException("Active signing-key ID does not match its public key.");
        }
        final ActiveKey loaded = new ActiveKey(pair, keyId);
        active = loaded;
        return loaded;
    }

    /** Stable identifier a third party can recompute from the public key alone. */
    public static String keyIdFor(final PublicKey publicKey) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(publicKey.getEncoded());
            return HexFormat.of().formatHex(Arrays.copyOfRange(digest, 0, 8));
        } catch (final Exception e) {
            throw new IllegalStateException("Failed to compute a signing key id.", e);
        }
    }

    @Override
    public String getActiveKeyId() {
        return currentActiveKey().keyId();
    }

    @Override
    public SigningKey currentSigningKey() {
        final ActiveKey snapshot = currentActiveKey();
        return new SigningKey(snapshot.keyPair().getPrivate(), snapshot.keyId());
    }

    @Override
    public PublicKey findPublicKeyById(final String keyId) {
        if (keyId == null) {
            return null;
        }
        final ActiveKey snapshot = active;
        if (snapshot != null && keyId.equals(snapshot.keyId())) {
            return snapshot.keyPair().getPublic();
        }
        // A storage failure is thrown, not reported as a missing key: that would make every signature it made
        // look forged.
        final byte[] encoded = findPublicKey(keyId);
        if (encoded == null) {
            return null;
        }
        try {
            return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(encoded));
        } catch (final Exception e) {
            LOGGER.warn("Stored signing key {} could not be read.", keyId, e);
            return null;
        }
    }

    private ActiveKey generateAndPersist() {
        try {
            final KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(new ECGenParameterSpec("secp256r1"));
            final KeyPair kp = kpg.generateKeyPair();

            final SigningKeyEntity entity = new SigningKeyEntity();
            entity.setKeyId(keyIdFor(kp.getPublic()));
            entity.setPrivateKeyEncoded(kp.getPrivate().getEncoded());
            entity.setPublicKeyEncoded(kp.getPublic().getEncoded());
            entity.setCreatedAt(new Date());

            // Stored before it is published: a key that signs must be one a verifier can find.
            insertKey(entity);

            return new ActiveKey(kp, entity.getKeyId());
        } catch (final Exception e) {
            throw new IllegalStateException("Failed to generate signing keypair.", e);
        }
    }

    private static KeyPair fromEntity(final SigningKeyEntity entity) {
        try {
            final KeyFactory kf = KeyFactory.getInstance("EC");
            final PrivateKey privateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(entity.getPrivateKeyEncoded()));
            final PublicKey publicKey = kf.generatePublic(new X509EncodedKeySpec(entity.getPublicKeyEncoded()));
            return new KeyPair(publicKey, privateKey);
        } catch (final Exception e) {
            throw new IllegalStateException("Failed to load signing keypair from stored bytes.", e);
        }
    }

    /**
     * Loads a PKCS8 PEM private key (BEGIN PRIVATE KEY) from the given file path and derives
     * the public key using BouncyCastle's secp256r1 curve parameters. The PEM file should be
     * generated with: {@code openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:secp256r1}
     */
    public static KeyPair loadFromPemFile(final String path) throws Exception {
        String pem = Files.readString(Paths.get(path), StandardCharsets.UTF_8);
        pem = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                 .replace("-----END PRIVATE KEY-----", "")
                 .replaceAll("\\s+", "");
        final byte[] der = Base64.getDecoder().decode(pem);

        final KeyFactory kf = KeyFactory.getInstance("EC");
        final PrivateKey privateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(der));

        // Derive Q = s * G using BouncyCastle's secp256r1 parameters (bcprov-jdk18on is already a dependency).
        final ECPrivateKey ecPriv = (ECPrivateKey) privateKey;
        final BigInteger s = ecPriv.getS();
        final X9ECParameters x9 = CustomNamedCurves.getByName("secp256r1");
        final org.bouncycastle.math.ec.ECPoint Q = x9.getG().multiply(s).normalize();
        final BigInteger qx = Q.getAffineXCoord().toBigInteger();
        final BigInteger qy = Q.getAffineYCoord().toBigInteger();

        final ECPublicKeySpec pubSpec = new ECPublicKeySpec(new ECPoint(qx, qy), ecPriv.getParams());
        final PublicKey publicKey = kf.generatePublic(pubSpec);
        return new KeyPair(publicKey, privateKey);
    }

    @Override
    public PublicKey getPublicKey() {
        return currentActiveKey().keyPair().getPublic();
    }

    @Override
    public String getPublicKeyPem(final String keyId) {
        final PublicKey key = findPublicKeyById(keyId);
        return key == null ? null : toPem(key.getEncoded());
    }

    private static String toPem(final byte[] encoded) {
        final String b64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded);
        return "-----BEGIN PUBLIC KEY-----\n" + b64 + "\n-----END PUBLIC KEY-----\n";
    }

    @Override
    public String getPublicKeyPem() {
        return toPem(currentActiveKey().keyPair().getPublic().getEncoded());
    }

    @Override
    public PublicKeyInfo getPublicKeyInfo() {
        final ActiveKey snapshot = currentActiveKey();
        return new PublicKeyInfo(snapshot.keyId(), toPem(snapshot.keyPair().getPublic().getEncoded()),
                toJwk(snapshot), fingerprint(snapshot));
    }

    private static String toJwk(final ActiveKey snapshot) {
        final ECPublicKey ecKey = (ECPublicKey) snapshot.keyPair().getPublic();
        final ECPoint point = ecKey.getW();
        final byte[] x = coordinateToBytes(point.getAffineX());
        final byte[] y = coordinateToBytes(point.getAffineY());
        final String xEnc = Base64.getUrlEncoder().withoutPadding().encodeToString(x);
        final String yEnc = Base64.getUrlEncoder().withoutPadding().encodeToString(y);
        return "{\"kid\":\"" + snapshot.keyId() + "\",\"kty\":\"EC\",\"crv\":\"P-256\",\"x\":\"" + xEnc + "\",\"y\":\"" + yEnc + "\"}";
    }

    private static String fingerprint(final ActiveKey snapshot) {
        try {
            final MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            final byte[] digest = sha256.digest(snapshot.keyPair().getPublic().getEncoded());
            return HexFormat.ofDelimiter(":").formatHex(digest);
        } catch (final Exception e) {
            return "(unavailable)";
        }
    }

    private static byte[] coordinateToBytes(final BigInteger coordinate) {
        final byte[] raw = coordinate.toByteArray();
        if (raw.length == 32) {
            return raw;
        }
        if (raw.length == 33 && raw[0] == 0) {
            return Arrays.copyOfRange(raw, 1, 33);
        }
        final byte[] padded = new byte[32];
        System.arraycopy(raw, 0, padded, 32 - raw.length, raw.length);
        return padded;
    }

}

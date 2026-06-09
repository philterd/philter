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
import com.mongodb.client.MongoClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bson.Document;
import org.bson.types.ObjectId;

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

public class SigningKeyDataService extends AbstractService<SigningKeyEntity> {

    private static final Logger LOGGER = LogManager.getLogger(SigningKeyDataService.class);

    private volatile KeyPair keyPair;

    public SigningKeyDataService(final MongoClient mongoClient, final AuditEventPublisher auditEventPublisher) {
        super(mongoClient, "signing_keys", auditEventPublisher);
        this.keyPair = loadOrGenerate();
    }

    private KeyPair loadOrGenerate() {
        final String keyPath = System.getenv("PHILTER_SIGNING_KEY_PATH");
        if (keyPath != null && !keyPath.isBlank()) {
            try {
                LOGGER.info("Loading signing key from PHILTER_SIGNING_KEY_PATH: {}", keyPath);
                return loadFromPemFile(keyPath);
            } catch (final Exception e) {
                throw new IllegalStateException("Failed to load signing key from PHILTER_SIGNING_KEY_PATH: " + keyPath, e);
            }
        }

        final Document doc = collection.find().first();
        if (doc != null) {
            LOGGER.info("Loaded existing signing key from MongoDB.");
            return fromEntity(SigningKeyEntity.fromDocument(doc));
        }

        LOGGER.info("No signing key found; generating a new ES256 keypair.");
        return generateAndPersist(true, null);
    }

    /**
     * Generates a new ES256 keypair, replaces any existing key in MongoDB, and updates the
     * in-memory keypair. Called from the admin UI "Regenerate Key" action.
     *
     * @param actingUserId the id of the admin who triggered the regeneration, for audit
     */
    public void regenerate(final ObjectId actingUserId) {
        LOGGER.info("Regenerating signing keypair.");
        collection.deleteMany(new Document());
        this.keyPair = generateAndPersist(false, actingUserId);
    }

    private KeyPair generateAndPersist(final boolean isFirstGeneration, final ObjectId actingUserId) {
        try {
            final KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(new ECGenParameterSpec("secp256r1"));
            final KeyPair kp = kpg.generateKeyPair();

            final SigningKeyEntity entity = new SigningKeyEntity();
            entity.setPrivateKeyEncoded(kp.getPrivate().getEncoded());
            entity.setPublicKeyEncoded(kp.getPublic().getEncoded());
            entity.setCreatedAt(new Date());

            collection.insertOne(entity.toDocument());

            final AuditLogEvent event = isFirstGeneration
                    ? AuditLogEvent.SIGNING_KEY_GENERATED
                    : AuditLogEvent.SIGNING_KEY_REGENERATED;
            auditEventPublisher.auditEvent(null, event, actingUserId, null, null, null);

            return kp;
        } catch (final Exception e) {
            throw new IllegalStateException("Failed to generate signing keypair.", e);
        }
    }

    private KeyPair fromEntity(final SigningKeyEntity entity) {
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
    /* package */ KeyPair loadFromPemFile(final String path) throws Exception {
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

    public PublicKey getPublicKey() {
        return keyPair.getPublic();
    }

    public PrivateKey getPrivateKey() {
        return keyPair.getPrivate();
    }

    /** Returns the public key in PEM (BEGIN PUBLIC KEY) format. */
    public String getPublicKeyPem() {
        final byte[] encoded = keyPair.getPublic().getEncoded();
        final String b64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded);
        return "-----BEGIN PUBLIC KEY-----\n" + b64 + "\n-----END PUBLIC KEY-----\n";
    }

    /** Returns the public key as a minimal JWK JSON object (kty=EC, crv=P-256 per RFC 7518). */
    public String getPublicKeyJwk() {
        final ECPublicKey ecKey = (ECPublicKey) keyPair.getPublic();
        final ECPoint point = ecKey.getW();
        final byte[] x = coordinateToBytes(point.getAffineX());
        final byte[] y = coordinateToBytes(point.getAffineY());
        final String xEnc = Base64.getUrlEncoder().withoutPadding().encodeToString(x);
        final String yEnc = Base64.getUrlEncoder().withoutPadding().encodeToString(y);
        return "{\"kty\":\"EC\",\"crv\":\"P-256\",\"x\":\"" + xEnc + "\",\"y\":\"" + yEnc + "\"}";
    }

    /** Returns the SHA-256 fingerprint of the public key (colon-separated hex bytes). */
    public String getPublicKeyFingerprint() {
        try {
            final MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            final byte[] digest = sha256.digest(keyPair.getPublic().getEncoded());
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

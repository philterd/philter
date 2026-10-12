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
package ai.philterd.philter.data.mongodb;

import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.SigningKeyEntity;
import ai.philterd.philter.data.services.SigningKeyDataService;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.services.encryption.EncryptionService;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bson.Document;

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

/** MongoDB implementation of {@link SigningKeyDataService}. */
public class MongoSigningKeyDataService extends AbstractEncryptedService<SigningKeyEntity> implements SigningKeyDataService {

    private final MongoClient mongoClient;

    private static final Logger LOGGER = LogManager.getLogger(SigningKeyDataService.class);

    /** The key and its id together, so no reader can see half a rotation. */
    private record ActiveKey(KeyPair keyPair, String keyId) { }

    private volatile ActiveKey active;
    private final MongoCollection<Document> keys;
    private final MongoCollection<Document> keyState;
    private final boolean externallyManaged;

    public MongoSigningKeyDataService(final MongoClient mongoClient, final EncryptionService encryptionService,
                                 final AuditEventPublisher auditEventPublisher) {
        this(mongoClient, encryptionService, auditEventPublisher, System.getenv("PHILTER_SIGNING_KEY_PATH"));
    }

    MongoSigningKeyDataService(final MongoClient mongoClient, final EncryptionService encryptionService,
                          final AuditEventPublisher auditEventPublisher, final String keyPath) {
        super(mongoClient, "signing_keys", encryptionService, auditEventPublisher);
        this.mongoClient = mongoClient;
        keys = collection.withReadPreference(ReadPreference.primary()).withWriteConcern(WriteConcern.MAJORITY);
        keyState = mongoClient.getDatabase("philter").getCollection("signing_key_state")
                .withReadPreference(ReadPreference.primary()).withWriteConcern(WriteConcern.MAJORITY);
        keys.createIndex(Indexes.ascending("key_id"), new IndexOptions().unique(true));
        externallyManaged = keyPath != null && !keyPath.isBlank();
        if (externallyManaged) {
            try {
                final KeyPair pair = loadFromPemFile(keyPath);
                active = new ActiveKey(pair, keyIdFor(pair.getPublic()));
                // Retain only the public half so old PEM signatures remain verifiable after restart.
                keys.updateOne(Filters.eq("key_id", active.keyId()),
                        new Document("$setOnInsert", new Document("key_id", active.keyId())
                                .append("public_key", pair.getPublic().getEncoded()).append("created_at", new Date())),
                        new UpdateOptions().upsert(true));
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load signing key from PHILTER_SIGNING_KEY_PATH: " + keyPath, e);
            }
        } else {
            if (keyState.find(Filters.eq("_id", "active")).first() == null) {
                final ActiveKey candidate = generateAndPersist();
                // Concurrent bootstraps may retain unused candidates, but only one pointer wins.
                final var result = keyState.updateOne(Filters.eq("_id", "active"),
                        new Document("$setOnInsert", new Document("key_id", candidate.keyId())),
                        new UpdateOptions().upsert(true));
                if (result.getUpsertedId() != null) {
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
        AdministratorAuthorization.requireActiveAdministrator(mongoClient, actingUserId);
        if (externallyManaged) {
            throw new IllegalStateException("Signing key is managed by PHILTER_SIGNING_KEY_PATH; replace the file and restart all instances.");
        }
        final ActiveKey candidate = generateAndPersist();
        if (keyState.updateOne(Filters.eq("_id", "active"), Updates.set("key_id", candidate.keyId()))
                .getMatchedCount() != 1) {
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
        final Document pointer = keyState.find(Filters.eq("_id", "active")).first();
        if (pointer == null || pointer.getString("key_id") == null) {
            throw new IllegalStateException("Active signing-key pointer is missing.");
        }
        final String keyId = pointer.getString("key_id");
        final ActiveKey cached = active;
        if (cached != null && keyId.equals(cached.keyId())) {
            return cached;
        }
        final Document document = keys.find(Filters.eq("key_id", keyId)).first();
        if (document == null) {
            throw new IllegalStateException("Active signing key is missing: " + keyId);
        }
        final KeyPair pair = fromEntity(SigningKeyDocuments.fromDocument(document, encryptionService));
        if (!keyId.equals(keyIdFor(pair.getPublic()))) {
            throw new IllegalStateException("Active signing-key ID does not match its public key.");
        }
        final ActiveKey loaded = new ActiveKey(pair, keyId);
        active = loaded;
        return loaded;
    }

    /** Stable identifier a third party can recompute from the public key alone. */
    static String keyIdFor(final PublicKey publicKey) {
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
        final Document doc = keys.find(Filters.eq("key_id", keyId)).first();
        if (doc == null) {
            return null;
        }
        try {
            return KeyFactory.getInstance("EC").generatePublic(
                    new X509EncodedKeySpec(SigningKeyDocuments.publicPartFromDocument(doc).getPublicKeyEncoded()));
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
            keys.insertOne(SigningKeyDocuments.toDocument(entity, encryptionService));

            return new ActiveKey(kp, entity.getKeyId());
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

    @Override
    public PublicKey getPublicKey() {
        return currentActiveKey().keyPair().getPublic();
    }

    @Override
    public PrivateKey getPrivateKey() {
        return currentActiveKey().keyPair().getPrivate();
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

    @Override
    public String getPublicKeyJwk() {
        return toJwk(currentActiveKey());
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

    @Override
    public String getPublicKeyFingerprint() {
        return fingerprint(currentActiveKey());
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

    @Override
    protected Document toDocument(final SigningKeyEntity entity) {
        return SigningKeyDocuments.toDocument(entity, encryptionService);
    }

}

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
import ai.philterd.philter.services.signing.SigningService;
import ai.philterd.philter.testutil.AbstractMongoIT;
import ai.philterd.philter.testutil.TestEncryptionService;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SharedSigningKeyIT extends AbstractMongoIT {
    private SigningKeyDataService service() {
        return new SigningKeyDataService(mongoClient, new TestEncryptionService(), mock(AuditEventPublisher.class));
    }

    @Test
    void concurrentStartupConvergesOnOnePublishedKey() throws Exception {
        final var barrier = new CyclicBarrier(8);
        final var services = new ArrayList<SigningKeyDataService>();
        try (var pool = Executors.newFixedThreadPool(8)) {
            final var tasks = new ArrayList<Future<SigningKeyDataService>>();
            for (int i = 0; i < 8; i++) {
                tasks.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return service();
                }));
            }
            for (var task : tasks) {
                services.add(task.get(15, TimeUnit.SECONDS));
            }
        }
        final String winner = services.getFirst().getActiveKeyId();
        for (var instance : services) {
            assertEquals(winner, instance.currentSigningKey().keyId());
        }
        assertEquals(1, mongoClient.getDatabase("philter").getCollection("signing_key_state").countDocuments());
    }

    @Test
    void rotationPropagatesToLedgerAndResponseSigningAndRetainsOldVerification() throws Exception {
        final var first = service();
        final var second = service();
        final var signing = new SigningService(second, mock(AdminSettingsDataService.class));
        final var old = signing.signLedgerEntry("old");
        final String oldJwt = signing.sign("before", "p", 1, "doc");
        first.regenerate(null);
        assertEquals(first.getActiveKeyId(), signing.signLedgerEntry("new").keyId());
        assertTrue(signing.verifyLedgerEntry("old", old.signature(), old.keyId()));
        final String jwt = signing.sign("after", "p", 1, "doc");
        assertEquals(first.getActiveKeyId(), jwtKey(jwt));
        verifyJwt(jwt, first);
        verifyJwt(oldJwt, first);
        final var info = second.getPublicKeyInfo();
        assertEquals(first.getActiveKeyId(), info.keyId());
        assertEquals(info.keyId(), JsonParser.parseString(info.jwk()).getAsJsonObject().get("kid").getAsString());
    }

    @Test
    void simultaneousRotationsAndResponsesAlwaysCarryTheirActualKeyId() throws Exception {
        final var first = service();
        final var second = service();
        final var signing = new SigningService(second, mock(AdminSettingsDataService.class));
        final var start = new CyclicBarrier(3);
        try (var pool = Executors.newFixedThreadPool(3)) {
            final Future<?> a = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                for (int i = 0; i < 12; i++) first.regenerate(null);
                return null;
            });
            final Future<?> b = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                for (int i = 0; i < 12; i++) second.regenerate(null);
                return null;
            });
            final Future<?> c = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                for (int i = 0; i < 30; i++) {
                    verifyJwt(signing.sign("response", "p", 1, "doc"), first);
                    final var info = second.getPublicKeyInfo();
                    final var der = Base64.getDecoder().decode(info.pem()
                            .replace("-----BEGIN PUBLIC KEY-----", "")
                            .replace("-----END PUBLIC KEY-----", "").replaceAll("\\s+", ""));
                    final var publicKey = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
                    assertEquals(SigningKeyDataService.keyIdFor(publicKey), info.keyId());
                    assertEquals(info.keyId(), JsonParser.parseString(info.jwk()).getAsJsonObject().get("kid").getAsString());
                }
                return null;
            });
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
            c.get(30, TimeUnit.SECONDS);
        }
        assertEquals(first.getActiveKeyId(), second.getActiveKeyId());
    }

    private static String jwtKey(final String jwt) {
        return JsonParser.parseString(new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[0]),
                StandardCharsets.UTF_8)).getAsJsonObject().get("kid").getAsString();
    }

    private static void verifyJwt(final String jwt, final SigningKeyDataService keys) throws Exception {
        final var parts = jwt.split("\\.");
        final var verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(keys.findPublicKeyById(jwtKey(jwt)));
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(parts[2])));
    }
}

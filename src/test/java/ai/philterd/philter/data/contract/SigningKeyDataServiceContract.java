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
package ai.philterd.philter.data.contract;

import ai.philterd.philter.data.services.SigningKeyDataService;
import org.junit.jupiter.api.Test;

import java.security.Signature;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The guarantees of {@link SigningKeyDataService}. */
public interface SigningKeyDataServiceContract extends StorageContract {

    @Test
    default void signingKeyInstancesStartingTogetherAgreeOnTheActiveKey() throws Exception {
        final List<String> active = StorageContract.concurrently(6,
                () -> storage().anotherInstance().signingKeys().getActiveKeyId());
        assertEquals(1, Set.copyOf(active).size());
        assertNotNull(active.get(0));
        final SigningKeyDataService.SigningKey key = storage().signingKeys().currentSigningKey();
        assertEquals(active.get(0), key.keyId());
        // The private key reads back unchanged: another instance verifies what it signed.
        final Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(key.privateKey());
        signer.update(new byte[]{1, 2, 3});
        final byte[] signature = signer.sign();
        final Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(storage().anotherInstance().signingKeys().findPublicKeyById(key.keyId()));
        verifier.update(new byte[]{1, 2, 3});
        assertTrue(verifier.verify(signature));
    }

}

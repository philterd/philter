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

import ai.philterd.philter.data.RecordIds;
import ai.philterd.philter.data.services.SignInChallengeDataService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The guarantees of {@link SignInChallengeDataService}. */
public interface SignInChallengeDataServiceContract extends StorageContract {

    @Test
    default void signInChallengeIsUsedAtMostOnce() throws Exception {
        final String userId = RecordIds.newId();
        final SignInChallengeDataService.Challenge challenge = storage().signInChallenges().create(userId);
        final List<String> results = StorageContract.concurrently(8,
                () -> storage().anotherInstance().signInChallenges().consume(challenge.token()));
        assertEquals(List.of(userId), results.stream().filter(Objects::nonNull).toList());
        assertNull(storage().signInChallenges().consume(challenge.token()));
    }

    @Test
    default void signInChallengeCannotBeUsedAfterItExpires() {
        final SignInChallengeDataService.Challenge challenge = storage().signInChallenges().create(RecordIds.newId());
        storage().expireSignInChallenge(challenge.token());
        assertNull(storage().signInChallenges().consume(challenge.token()));
    }

}

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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * The base of every storage contract: the implementation under test, and running callers concurrently. A contract
 * is an interface of tests; an implementation runs them all by implementing every contract in one test class that
 * supplies {@link #storage()}.
 */
public interface StorageContract {

    /** The implementation under test, over a database that is empty when each test starts. */
    StorageUnderTest storage();

    /**
     * Stores a user and returns their id. Context tokens are hashed for their owner's username, so a context's owner
     * has to exist.
     */
    default String newOwner() {
        final String username = "user-" + ai.philterd.philter.data.RecordIds.newId();
        if (!storage().users().createUser("req", username, null, ai.philterd.philter.data.services.UserService.ROLE_USER,
                storage().policies(), storage().contexts(), "test").isSuccessful()) {
            throw new IllegalStateException("The test user was not created.");
        }
        return storage().users().findByUsername(username).getId();
    }

    /** Runs {@code callers} copies of {@code call} at once, released together, and returns their results. */
    static <T> List<T> concurrently(final int callers, final Callable<T> call) throws Exception {
        final CountDownLatch start = new CountDownLatch(1);
        final ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            final List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            final List<T> results = new ArrayList<>();
            for (final Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

}

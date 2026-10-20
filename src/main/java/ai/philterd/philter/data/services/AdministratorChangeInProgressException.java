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

import java.io.Serial;

/**
 * Another change to the administrators held the administrator change guard for longer than a change waits. Nothing
 * was changed, and the change can be retried.
 */
public final class AdministratorChangeInProgressException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 3170582961734409215L;

    private final long retryAfterSeconds;

    public AdministratorChangeInProgressException(final long retryAfterSeconds) {
        super("Another change to the administrators is still in progress; try again.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** Seconds until the guard's current holder's lease ends, at least 1. */
    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

}

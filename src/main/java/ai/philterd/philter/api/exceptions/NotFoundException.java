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
package ai.philterd.philter.api.exceptions;

import java.io.Serial;

/**
 * A 404 that says only "not found", for something that does not exist or that the caller may not
 * reach. The two read the same, so an owner or a name cannot be used to discover what exists.
 */
public final class NotFoundException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 2214503925412216831L;

    public NotFoundException() {
        super(ApiErrors.NOT_FOUND_MESSAGE);
    }

}

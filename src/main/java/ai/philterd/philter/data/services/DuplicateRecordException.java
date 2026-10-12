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

/**
 * Thrown by a storage implementation when a write would break a uniqueness guarantee, such as a second record
 * with a name that must be unique per owner. Nothing was written. Shared code turns it into the refusal the
 * caller sees, so every implementation refuses the same way.
 */
public class DuplicateRecordException extends RuntimeException {

    public DuplicateRecordException(final Throwable cause) {
        super("A record with the same unique value already exists.", cause);
    }

}

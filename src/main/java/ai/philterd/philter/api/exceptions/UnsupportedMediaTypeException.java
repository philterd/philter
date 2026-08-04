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

/**
 * Thrown when a request body contradicts its declared {@code Content-Type}.
 *
 * <p>Refusing is deliberate. Redacting a document Philter never parsed would return a clean-looking
 * response containing unredacted content, which is a worse outcome than an error.
 */
public class UnsupportedMediaTypeException extends RuntimeException {

    public UnsupportedMediaTypeException(final String message) {
        super(message);
    }

}

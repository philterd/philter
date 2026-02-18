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
 * Exception thrown when a request payload exceeds the maximum allowed size.
 * This exception is designed to be caught by the GlobalSaasExceptionHandler
 * and transformed into a JSON response with HTTP 413 status code.
 */
public class PayloadTooLargeException extends RuntimeException {

    /**
     * Creates a new PayloadTooLargeException with the specified message.
     *
     * @param message the detail message explaining why the payload is too large
     */
    public PayloadTooLargeException(final String message) {
        super(message);
    }

}

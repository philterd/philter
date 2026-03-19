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
package ai.philterd.philter.services.policies;

public class PolicyValidation {

    private final boolean valid;
    private final String message;

    private PolicyValidation(final boolean valid, final String message) {
        this.valid = valid;
        this.message = message;
    }

    public static PolicyValidation valid(final String message) {
        return new PolicyValidation(true, message);
    }

    public static PolicyValidation invalid(final String message) {
        return new PolicyValidation(false, message);
    }

    public boolean isValid() {
        return valid;
    }

    public String getMessage() {
        return message;
    }

}

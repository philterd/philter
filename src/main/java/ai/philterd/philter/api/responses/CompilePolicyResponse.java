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
package ai.philterd.philter.api.responses;

import com.google.gson.JsonElement;

/**
 * The result of compiling a PhiSQL policy: the policy name and description from the PhiSQL
 * {@code POLICY} declaration and the compiled native Phileas policy JSON.
 */
public class CompilePolicyResponse {

    private final String name;
    private final String description;
    private final JsonElement policy;

    public CompilePolicyResponse(final String name, final String description, final JsonElement policy) {
        this.name = name;
        this.description = description;
        this.policy = policy;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public JsonElement getPolicy() {
        return policy;
    }

}

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

/** A resource's name and the username of the user it belongs to, as listed with {@code all_users}. */
public class OwnedNameResponse {

    private final String name;
    private final String owner;

    public OwnedNameResponse(final String name, final String owner) {
        this.name = name;
        this.owner = owner;
    }

    public String getName() { return name; }

    public String getOwner() { return owner; }

}

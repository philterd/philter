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
package ai.philterd.philter.api.requests;

import java.util.List;

/**
 * The body of a request to replace an account's always-redact and never-redact lists. Each field is
 * the complete desired contents of the corresponding list; a {@code POST} replaces both lists in full.
 * A null or omitted field is treated as an empty list (clearing it).
 */
public class RedactListsRequest {

    private List<String> alwaysRedact;
    private List<String> neverRedact;

    public List<String> getAlwaysRedact() {
        return alwaysRedact;
    }

    public void setAlwaysRedact(final List<String> alwaysRedact) {
        this.alwaysRedact = alwaysRedact;
    }

    public List<String> getNeverRedact() {
        return neverRedact;
    }

    public void setNeverRedact(final List<String> neverRedact) {
        this.neverRedact = neverRedact;
    }

}

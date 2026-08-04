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
package ai.philterd.philter.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What an API key is permitted to do. A key carries a set of these; a request to an endpoint whose
 * scope the key does not hold is refused with {@code 403 Forbidden}.
 *
 * <p>Scopes bound a <em>key</em>, not a user: they can only narrow what the owning user could already
 * do, never widen it. Every other check still applies, so an admin-only operation needs both the scope
 * and the admin role, and reaching another user's data still needs the {@code owner} parameter,
 * administrator rights, and {@code ADMIN_CROSS_USER_ACCESS_ENABLED}.
 *
 * <p>{@link #LEDGER_EXPORT} and {@link #REIDENTIFY} are separate from the read scopes of the resources
 * they belong to because they are the two capabilities that return the original sensitive values in
 * the clear. Splitting them out is what lets a key read and validate a ledger without being able to
 * dump its plaintext.
 */
public enum ApiKeyScope {

    REDACT("redact", "Redact text and documents, and explain redactions."),

    CONTEXTS_READ("contexts:read", "List and read contexts and their entries, including exports."),
    CONTEXTS_WRITE("contexts:write", "Create, update, and delete contexts and their entries, including imports."),

    POLICIES_READ("policies:read", "List and read policies, their versions, and diffs."),
    POLICIES_WRITE("policies:write", "Create, delete, roll back, and compile policies."),

    LISTS_READ("lists:read", "Read custom lists and the always/never redact lists."),
    LISTS_WRITE("lists:write", "Create, update, and delete custom lists and the always/never redact lists."),

    DOCUMENTS_READ("documents:read", "List asynchronous redaction jobs and download their results."),
    DOCUMENTS_WRITE("documents:write", "Delete asynchronous redaction records."),

    LEDGER_READ("ledger:read", "List, read, and validate redaction ledger chains."),
    LEDGER_EXPORT("ledger:export", "Export a ledger chain, which contains the original tokens in the clear."),
    LEDGER_DELETE("ledger:delete", "Delete or purge ledger entries. Also requires an administrator."),

    HOLDS_READ("holds:read", "List and read legal holds."),
    HOLDS_WRITE("holds:write", "Place and release legal holds."),

    REIDENTIFY("reidentify", "Reverse a replacement to its original value.");

    private final String scope;
    private final String description;

    ApiKeyScope(final String scope, final String description) {
        this.scope = scope;
        this.description = description;
    }

    public String getScope() {
        return scope;
    }

    public String getDescription() {
        return description;
    }

    @Override
    public String toString() {
        return scope;
    }

    /** Every scope, in declaration order. Used for the bootstrap key and the dashboard's select-all. */
    public static Set<String> all() {
        final Set<String> scopes = new LinkedHashSet<>();
        for (final ApiKeyScope value : values()) {
            scopes.add(value.getScope());
        }
        return Collections.unmodifiableSet(scopes);
    }

    /**
     * The scope with the given wire name, or {@code null} if it is not one. Unknown names are ignored
     * rather than rejected so a key written by a newer version does not break an older one; the unknown
     * scope simply grants nothing.
     */
    public static ApiKeyScope fromScope(final String scope) {
        return Arrays.stream(values())
                .filter(value -> value.getScope().equals(scope))
                .findFirst()
                .orElse(null);
    }

}

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
package ai.philterd.philter.services.cache;

import ai.philterd.philter.utils.EnvUtils;
import com.google.gson.Gson;
import org.bson.types.ObjectId;

import java.util.List;

/**
 * A short-lived, in-process cache of the per-request inputs to redaction: a user's stored policy JSON
 * (keyed by user and policy name) and their always/never redact lists. Caching these lets the hot
 * {@code /api/filter} and {@code /api/explain} paths skip a database read each on a cache hit.
 *
 * <p><strong>Always in-process, never Valkey.</strong> Unlike the other caches, this one deliberately
 * uses only the in-JVM backend regardless of {@code CACHE_HOSTNAME}. Stored policy JSON can contain
 * PII inside filter-strategy conditions, and the redact-list terms are themselves sensitive, so this
 * data must not be written to a shared or persistent cache. Keeping it in-process holds it within the
 * same trust boundary as request processing.
 *
 * <p>The user's managed FPE key is <em>not</em> cached here — it is resolved and injected into the
 * policy fresh on every request, so no key material lives in the cache.
 *
 * <p>Entries expire after {@code REDACTION_CACHE_TTL_SECONDS} (default 60), which is also the upper
 * bound on how long an edited or deleted policy / redact list keeps being used, so it is kept low.
 */
public class RedactionCache {

    private static final int TTL_SECONDS = EnvUtils.getInt("REDACTION_CACHE_TTL_SECONDS", 60);

    private final CacheBackend backend = InMemoryCacheBackend.INSTANCE;
    private final Gson gson = new Gson();

    /** Returns the cached policy (JSON plus revision) for the user and policy name, or null if absent. */
    public CachedPolicy getPolicy(final ObjectId userId, final String policyName) {
        final String json = backend.get(policyKey(userId, policyName));
        return json == null ? null : gson.fromJson(json, CachedPolicy.class);
    }

    /** Caches the policy JSON and its revision for the user and policy name. */
    public void putPolicy(final ObjectId userId, final String policyName, final String policyJson, final int revision) {
        backend.setex(policyKey(userId, policyName), TTL_SECONDS, gson.toJson(new CachedPolicy(policyJson, revision)));
    }

    /** Returns the cached redact lists for the user, or null if not cached. */
    public CachedRedactLists getRedactLists(final ObjectId userId) {
        final String json = backend.get(redactListsKey(userId));
        return json == null ? null : gson.fromJson(json, CachedRedactLists.class);
    }

    /** Caches the user's always/never redact term lists. */
    public void putRedactLists(final ObjectId userId, final List<String> alwaysRedact, final List<String> neverRedact) {
        backend.setex(redactListsKey(userId), TTL_SECONDS, gson.toJson(new CachedRedactLists(alwaysRedact, neverRedact)));
    }

    private static String policyKey(final ObjectId userId, final String policyName) {
        return "redaction_policy_" + userId.toHexString() + "_" + policyName;
    }

    private static String redactListsKey(final ObjectId userId) {
        return "redaction_lists_" + userId.toHexString();
    }

    /** Immutable holder for a cached policy: its JSON body and the revision (version) it was at. */
    public static final class CachedPolicy {

        private final String policyJson;
        private final int revision;

        public CachedPolicy(final String policyJson, final int revision) {
            this.policyJson = policyJson;
            this.revision = revision;
        }

        public String getPolicyJson() {
            return policyJson;
        }

        public int getRevision() {
            return revision;
        }

    }

    /** Immutable holder for a user's always-redact and never-redact term lists. */
    public static final class CachedRedactLists {

        private final List<String> alwaysRedact;
        private final List<String> neverRedact;

        public CachedRedactLists(final List<String> alwaysRedact, final List<String> neverRedact) {
            this.alwaysRedact = alwaysRedact;
            this.neverRedact = neverRedact;
        }

        public List<String> getAlwaysRedact() {
            return alwaysRedact == null ? List.of() : alwaysRedact;
        }

        public List<String> getNeverRedact() {
            return neverRedact == null ? List.of() : neverRedact;
        }

    }

}

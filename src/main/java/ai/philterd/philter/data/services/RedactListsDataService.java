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

import ai.philterd.philter.data.entities.RedactListsEntity;

import java.util.List;
import java.util.Locale;

/**
 * Each user's always-redact and never-redact lists, kept together per user. Each list has its
 * own revision, which every write of that list increments, so a client can replace one list on the
 * condition that nobody changed it since the client read it.
 *
 * <p>Guarantees every implementation must provide:
 * <ul>
 *   <li>Conditional writes: given an expected revision, {@link #replaceList} replaces the list only if it is
 *   still at that revision. Otherwise nothing is written and the refusal's reason is
 *   {@link #REASON_REDACT_LIST_CHANGED}. Of two writes racing with the same revision, exactly one succeeds.</li>
 *   <li>Every write of a list increments that list's revision, and only that list's.</li>
 *   <li>Terms are stored encrypted, and what is written is read back unchanged.</li>
 * </ul>
 */
public interface RedactListsDataService {

    /** The maximum number of terms allowed in each list (always-redact and never-redact). */
    int MAXIMUM_TERMS_PER_LIST = 1000;

    /** The maximum length, in characters, of a single term. */
    int MAXIMUM_TERM_LENGTH = 100;

    /** Why a list write was refused with 409: the list changed since the revision the client gave. */
    String REASON_REDACT_LIST_CHANGED = "redact_list_changed";

    /** One of the two lists, with the fields that hold its terms and its revision. */
    enum RedactList {

        ALWAYS("always"),
            NEVER("never");

        private final String name;

        RedactList(final String name) {
            this.name = name;
        }

        /** The list's name in the API path: {@code always} or {@code never}. */
        public String getName() {
            return name;
        }

        /** The list named in an API path, or {@code null} for any other name. */
        public static RedactList fromName(final String name) {
            if (name == null) {
                return null;
            }
            for (final RedactList list : values()) {
                if (list.name.equals(name.toLowerCase(Locale.ROOT))) {
                    return list;
                }
            }
            return null;
        }

    }

    /** One list's terms and the revision they are at. */
    record ListContents(List<String> terms, long revision) {
    }

    /** The revisions of a user's two lists. */
    record Revisions(long alwaysRedact, long neverRedact) {
    }

    /**
     * The revisions of the user's lists, without reading or decrypting their terms, so a cached copy can be checked
     * cheaply before every redaction. A user with no saved lists is at 0 and 0.
     */
    Revisions revisions(final String userId);

    /**
     * Writes the given lists for the user, creating the user's lists if there are none. A
     * {@code null} list is left as it is. Each list written moves to its next revision, whether or not
     * its terms changed, so a client holding an earlier revision learns that it was written.
     */
    void saveOrUpdate(final String requestId, final String userId, final List<String> termsToAlwaysRedact, final List<String> termsToNeverRedact, final String source);

    RedactListsEntity find(final String userId);

    /** One of the user's lists and its revision. A user with no saved lists has an empty list at revision 0. */
    ListContents findList(final String userId, final RedactList list);

    /**
     * Replaces one of the user's lists, leaving the other as it is, and moves it to its next revision.
     * With an expected revision, the list is written only if it is still at that revision, checked and
     * written in one operation so a concurrent write cannot slip between them.
     *
     * @param expectedRevision The revision the client read, or {@code null} to write unconditionally.
     * @return The list as written, or {@code null} when the list is no longer at the expected revision.
     */
    ListContents replaceList(final String requestId, final String userId, final RedactList list,
            final List<String> terms, final Long expectedRevision, final String source);

}

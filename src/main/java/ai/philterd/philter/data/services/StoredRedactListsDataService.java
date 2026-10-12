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

import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.RedactListsEntity;
import ai.philterd.philter.model.AuditLogEvent;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The rules for the always-redact and never-redact lists, the same in every storage implementation: what is
 * audited, and how a list's contents and revision are read. An implementation stores each user's lists, their
 * terms encrypted, and their revisions.
 */
public abstract class StoredRedactListsDataService implements RedactListsDataService {

    protected final AuditEventPublisher auditEventPublisher;

    protected StoredRedactListsDataService(final AuditEventPublisher auditEventPublisher) {
        this.auditEventPublisher = auditEventPublisher;
    }

    /**
     * Replaces the given lists' terms for the user, adding one to each one's revision, and creates the user's
     * lists when they have none.
     */
    protected abstract void write(final String userId, final Map<RedactList, List<String>> lists);

    /**
     * Replaces one list's terms and adds one to its revision, only if the list is at {@code expectedRevision}
     * (any revision when it is null; a list never written is at 0), and returns the list as written, or null
     * when it was at another revision and nothing was written.
     */
    protected abstract ListContents writeIfAt(final String userId, final RedactList list, final List<String> terms,
            final Long expectedRevision);

    @Override
    public void saveOrUpdate(final String requestId, final String userId, final List<String> termsToAlwaysRedact, final List<String> termsToNeverRedact, final String source) {

        final Map<RedactList, List<String>> lists = new EnumMap<>(RedactList.class);
        final StringBuilder details = new StringBuilder();

        if (termsToAlwaysRedact != null) {
            lists.put(RedactList.ALWAYS, termsToAlwaysRedact);
            details.append("alwaysRedact: ").append(termsToAlwaysRedact.size());
        }
        if (termsToNeverRedact != null) {
            lists.put(RedactList.NEVER, termsToNeverRedact);
            details.append(details.isEmpty() ? "" : ", ").append("neverRedact: ").append(termsToNeverRedact.size());
        }

        if (lists.isEmpty()) {
            return;
        }

        write(userId, lists);

        // The always-redact / never-redact lists are security-relevant: they force or suppress
        // redaction regardless of policy, so changes are audited.
        auditEventPublisher.auditEvent(requestId, AuditLogEvent.REDACT_LISTS_UPDATED, userId, userId, source,
                details.toString());

    }

    @Override
    public ListContents findList(final String userId, final RedactList list) {

        final RedactListsEntity entity = find(userId);
        if (entity == null) {
            return new ListContents(List.of(), 0L);
        }
        return contents(entity, list);

    }

    /** One list of a user's lists. */
    protected static ListContents contents(final RedactListsEntity entity, final RedactList list) {
        return list == RedactList.ALWAYS
                ? new ListContents(entity.getTermsToAlwaysRedact(), entity.getAlwaysRedactRevision())
                : new ListContents(entity.getTermsToNeverRedact(), entity.getNeverRedactRevision());
    }

    @Override
    public ListContents replaceList(final String requestId, final String userId, final RedactList list,
                                    final List<String> terms, final Long expectedRevision, final String source) {

        final ListContents contents = writeIfAt(userId, list, terms, expectedRevision);
        if (contents == null) {
            return null;
        }

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.REDACT_LISTS_UPDATED, userId, userId, source,
                "list: " + list.getName() + ", terms: " + terms.size() + ", revision: " + contents.revision());

        return contents;

    }

}

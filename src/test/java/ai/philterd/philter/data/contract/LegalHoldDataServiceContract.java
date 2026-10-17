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
package ai.philterd.philter.data.contract;

import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.audit.AuditLogService;
import ai.philterd.philter.data.RecordIds;
import ai.philterd.philter.data.entities.LegalHoldEntity;
import ai.philterd.philter.data.services.LegalHoldDataService;
import ai.philterd.philter.data.services.Listings;
import ai.philterd.philter.model.ServiceResponse;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The guarantees of {@link LegalHoldDataService}. */
public interface LegalHoldDataServiceContract extends StorageContract {

    private ServiceResponse hold(final String owner, final String reference, final String documentId) {
        return storage().legalHolds().create("req", reference, LegalHoldEntity.SCOPE_DOCUMENT_CHAIN, documentId,
                "litigation", owner, owner);
    }

    @Test
    default void legalHoldReferencesAreUniquePerOwner() throws Exception {
        final String owner = RecordIds.newId();
        final List<ServiceResponse> results = StorageContract.concurrently(6, () -> hold(owner, "case-1", "doc"));
        assertEquals(1, results.stream().filter(ServiceResponse::isSuccessful).count());
        assertEquals(1, storage().legalHolds().list(owner, null, new Listings.Sort("reference", false), 0, 10).total());
        assertEquals(201, hold(RecordIds.newId(), "case-1", "doc").getStatusCode());
    }

    @Test
    default void legalHoldsLastUntilReleased() {
        final String owner = RecordIds.newId();
        assertTrue(hold(owner, "case-1", "doc").isSuccessful());
        assertTrue(hold(owner, "case-2", "doc").isSuccessful());
        assertTrue(storage().legalHolds().isProtectedDocument(owner, "doc"));
        assertFalse(storage().legalHolds().isProtectedDocument(owner, "another-doc"));
        assertTrue(storage().legalHolds().release("req", "case-1", owner, owner, false).isSuccessful());
        // Releasing one hold never unblocks what another still covers.
        assertTrue(storage().legalHolds().isProtectedDocument(owner, "doc"));
        assertTrue(storage().legalHolds().release("req", "case-2", owner, owner, false).isSuccessful());
        assertFalse(storage().legalHolds().isProtectedDocument(owner, "doc"));
        assertFalse(storage().legalHolds().release("req", "case-2", owner, owner, false).isSuccessful());
    }

    @Test
    default void onlyTheSetterOrAnAdministratorReleasesAHold() {
        final String owner = RecordIds.newId();
        final String administrator = RecordIds.newId();
        assertEquals(201, storage().legalHolds().create("req", "case-1", LegalHoldEntity.SCOPE_USER, "jordan",
                "litigation", owner, administrator).getStatusCode());
        assertEquals(403, storage().legalHolds().release("req", "case-1", owner, owner, false).getStatusCode());
        assertTrue(storage().legalHolds().hasAnyHold(owner));
        assertEquals(200, storage().legalHolds().release("req", "case-1", owner, administrator, false).getStatusCode());

        assertEquals(201, storage().legalHolds().create("req", "case-2", LegalHoldEntity.SCOPE_USER, "jordan",
                "litigation", owner, administrator).getStatusCode());
        assertEquals(200, storage().legalHolds().release("req", "case-2", owner, RecordIds.newId(), true).getStatusCode());
        assertFalse(storage().legalHolds().hasAnyHold(owner));
    }

    @Test
    default void aReferenceCannotAddFieldsToTheAuditDetails() {
        final String owner = RecordIds.newId();
        final String reference = "case-9, releasedBy: someone-else";
        assertEquals(201, storage().legalHolds().create("req", reference, LegalHoldEntity.SCOPE_USER, "jordan",
                "litigation", owner, owner).getStatusCode());
        assertEquals(200, storage().legalHolds().release("req", reference, owner, owner, false).getStatusCode());
        final List<AuditLogService.AuditEvent> released = storage().auditLog().find(owner,
                AuditLogEvent.LEGAL_HOLD_RELEASED.getAuditLogEvent(), null, null, 0, 10);
        assertEquals(1, released.size());
        assertEquals("reference: \"case-9, releasedBy: someone-else\", releasedBy: " + owner, released.get(0).details());
    }

    @Test
    default void legalHoldListingsCountPageAndSearchAsPlainText() {
        final String owner = RecordIds.newId();
        for (final String reference : List.of("a.b", "axb", "A.B-2", "other", "more")) {
            assertTrue(hold(owner, reference, "doc").isSuccessful());
        }
        // Another owner's holds, some with the same references, are never listed or counted.
        final String other = RecordIds.newId();
        for (final String reference : List.of("a.b", "zzz")) {
            assertTrue(hold(other, reference, "doc").isSuccessful());
        }
        final Listings.Sort byReference = new Listings.Sort("reference", false);
        final Set<String> seen = new HashSet<>();
        for (int offset = 0; offset < 5; offset += 2) {
            final Listings.Page<LegalHoldEntity> page = storage().legalHolds().list(owner, null, byReference, offset, 2);
            assertEquals(5, page.total());
            page.items().forEach(hold -> assertTrue(seen.add(hold.getReference())));
        }
        assertEquals(5, seen.size());
        final Listings.Page<LegalHoldEntity> found = storage().legalHolds().list(owner, "a.b", byReference, 0, 10);
        assertEquals(Set.of("a.b", "A.B-2"), Set.copyOf(found.items().stream().map(LegalHoldEntity::getReference).toList()));
        assertEquals(2, found.total());
    }

}

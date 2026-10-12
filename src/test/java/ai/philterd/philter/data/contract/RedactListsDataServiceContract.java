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

import ai.philterd.philter.data.RecordIds;
import ai.philterd.philter.data.services.RedactListsDataService;
import ai.philterd.philter.data.services.RedactListsDataService.ListContents;
import ai.philterd.philter.data.services.RedactListsDataService.RedactList;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The guarantees of {@link RedactListsDataService}. */
public interface RedactListsDataServiceContract extends StorageContract {

    @Test
    default void redactListWritesAtTheReadRevisionApplyAndReturnTheNextOne() {
        final String owner = RecordIds.newId();
        final ListContents first = storage().redactLists().replaceList("req", owner, RedactList.ALWAYS,
                List.of("Project Wren"), null, "test");
        final ListContents second = storage().redactLists().replaceList("req", owner, RedactList.ALWAYS,
                List.of("Project Wren", "Project Heron"), first.revision(), "test");
        assertNotNull(second);
        assertEquals(first.revision() + 1, second.revision());
        assertEquals(List.of("Project Wren", "Project Heron"), storage().redactLists().findList(owner, RedactList.ALWAYS).terms());
    }

    @Test
    default void redactListWriteAtAStaleRevisionWritesNothing() {
        final String owner = RecordIds.newId();
        final ListContents read = storage().redactLists().replaceList("req", owner, RedactList.NEVER, List.of("a"), null, "test");
        storage().redactLists().replaceList("req", owner, RedactList.NEVER, List.of("b"), read.revision(), "test");
        assertNull(storage().redactLists().replaceList("req", owner, RedactList.NEVER, List.of("c"), read.revision(), "test"));
        assertEquals(List.of("b"), storage().redactLists().findList(owner, RedactList.NEVER).terms());
    }

    @Test
    default void redactListWritesRacingWithOneRevisionLetExactlyOneThrough() throws Exception {
        final String owner = RecordIds.newId();
        final long revision = storage().redactLists().replaceList("req", owner, RedactList.ALWAYS, List.of("start"), null,
                "test").revision();
        final AtomicInteger next = new AtomicInteger();
        final List<ListContents> results = StorageContract.concurrently(6, () -> storage().redactLists().replaceList(
                "req", owner, RedactList.ALWAYS, List.of("term " + next.incrementAndGet()), revision, "test"));
        assertEquals(1, results.stream().filter(Objects::nonNull).count());
    }

    @Test
    default void redactListWritesMoveOnlyTheirOwnListsRevision() {
        final String owner = RecordIds.newId();
        final long never = storage().redactLists().replaceList("req", owner, RedactList.NEVER, List.of("keep"), null,
                "test").revision();
        storage().redactLists().replaceList("req", owner, RedactList.ALWAYS, List.of("x"), null, "test");
        storage().redactLists().replaceList("req", owner, RedactList.ALWAYS, List.of("y"), null, "test");
        assertEquals(never, storage().redactLists().findList(owner, RedactList.NEVER).revision());
        assertEquals(List.of("keep"), storage().redactLists().findList(owner, RedactList.NEVER).terms());
    }

    @Test
    default void redactListTermsReadBackUnchanged() {
        final String owner = RecordIds.newId();
        final List<String> terms = List.of("Ünïcödé", "with, comma", "tab\there", "\"quoted\"");
        storage().redactLists().replaceList("req", owner, RedactList.ALWAYS, terms, null, "test");
        assertEquals(terms, storage().redactLists().findList(owner, RedactList.ALWAYS).terms());
    }

}

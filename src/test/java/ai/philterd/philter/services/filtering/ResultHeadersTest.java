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
package ai.philterd.philter.services.filtering;

import ai.philterd.phileas.model.filtering.Explanation;
import ai.philterd.phileas.model.filtering.LeftoverCheckResult;
import ai.philterd.phileas.model.filtering.TextFilterResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultHeadersTest {

    private static TextFilterResult result(final LeftoverCheckResult check) {
        return new TextFilterResult("redacted", "context", 0, new Explanation(List.of(), List.of()), List.of(), 0,
                false, check);
    }

    @Test
    void aCheckThatRanReportsItsCount() {
        assertEquals("0", ResultHeaders.leftovers(result(LeftoverCheckResult.performed(List.of()))));
    }

    @Test
    void aCheckThatCheckedNothingIsNotChecked() {
        for (final LeftoverCheckResult.Reason reason : List.of(LeftoverCheckResult.Reason.UNREADABLE_OUTPUT,
                LeftoverCheckResult.Reason.CHECK_FAILED)) {
            final LeftoverCheckResult check = LeftoverCheckResult.notPerformed(reason);
            assertEquals(ResultHeaders.NOT_CHECKED, ResultHeaders.leftovers(result(check)), reason.name());
            assertTrue(ResultHeaders.notChecked(check), reason.name());
        }
        assertFalse(ResultHeaders.found(ResultHeaders.NOT_CHECKED));
    }

    @Test
    void aCheckNotCalledForSendsNoHeader() {
        final LeftoverCheckResult notRequested = LeftoverCheckResult.notPerformed(LeftoverCheckResult.Reason.NOT_REQUESTED);
        assertNull(ResultHeaders.leftovers(result(notRequested)));
        assertNull(ResultHeaders.leftovers(result(null)));
        assertNull(ResultHeaders.leftovers(null));
        assertFalse(ResultHeaders.notChecked(notRequested));
        assertFalse(ResultHeaders.notChecked(null));
    }

}

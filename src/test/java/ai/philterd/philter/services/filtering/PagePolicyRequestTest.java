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

import ai.philterd.philter.api.exceptions.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PagePolicyRequestTest {

    @Test
    void parsesPageSetsAndPolicyNames() {
        assertEquals(List.of(new PagePolicyRequest("2-5,8", "records"), new PagePolicyRequest("12-", "exhibits"),
                        new PagePolicyRequest("1", "default")),
                PagePolicyRequest.parse(new String[]{"2-5, 8:records", "12-:exhibits", "1:default"}));
    }

    @Test
    void noValuesIsNoAssignments() {
        assertEquals(List.of(), PagePolicyRequest.parse(null));
        assertEquals(List.of(), PagePolicyRequest.parse(new String[0]));
    }

    @Test
    void refusesAValueWithoutPagesOrPolicy() {
        for (final String value : new String[]{"records", ":records", "2-5:", "2-5"}) {
            assertThrows(BadRequestException.class, () -> PagePolicyRequest.parse(new String[]{value}), value);
        }
    }

    @Test
    void refusesAMalformedPageSet() {
        for (final String value : new String[]{"0:records", "5-2:records", "a:records", "2,,3:records", "2,2:records"}) {
            assertThrows(BadRequestException.class, () -> PagePolicyRequest.parse(new String[]{value}), value);
        }
    }

    @Test
    void refusesOverlappingPageSetsNamingThePages() {
        final BadRequestException e = assertThrows(BadRequestException.class,
                () -> PagePolicyRequest.parse(new String[]{"2-5:records", "5-:exhibits"}));
        assertTrue(e.getMessage().contains("Page 5"), e.getMessage());
    }

    @Test
    void refusesTooManyPageSets() {
        final String[] values = new String[PagePolicyRequest.MAX_ASSIGNMENTS + 1];
        for (int i = 0; i < values.length; i++) {
            values[i] = (i + 1) + ":records";
        }
        assertThrows(BadRequestException.class, () -> PagePolicyRequest.parse(values));
    }

    @Test
    void refusesPagesBeyondTheEnd() {
        final List<PagePolicyRequest> requests = PagePolicyRequest.parse(new String[]{"1:a", "3-4:b"});
        final BadRequestException e = assertThrows(BadRequestException.class,
                () -> PagePolicyRequest.checkPageCount(requests, 2));
        assertTrue(e.getMessage().contains("3-4"), e.getMessage());
        PagePolicyRequest.checkPageCount(requests, 4);
        PagePolicyRequest.checkPageCount(PagePolicyRequest.parse(new String[]{"2-:a"}), 2);
    }

    @Test
    void describesEachPageSetsPolicyVersion() {
        assertEquals("2-5,8=records@3; 12-=exhibits@1", PagePolicyRequest.describe(List.of(
                new AppliedPagePolicy("2-5,8", new AppliedPolicy("records", 3, "a")),
                new AppliedPagePolicy("12-", new AppliedPolicy("exhibits", 1, "b")))));
    }

}

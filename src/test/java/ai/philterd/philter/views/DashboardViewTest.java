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
package ai.philterd.philter.views;

import ai.philterd.phileas.model.filtering.Explanation;
import ai.philterd.phileas.model.filtering.MimeType;
import ai.philterd.phileas.model.filtering.TextFilterResult;
import ai.philterd.philter.services.filtering.RedactionService;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the dashboard's text-filtering seam. The project has no Vaadin UI test harness, so the
 * redaction call extracted from the button handler is tested directly.
 */
@ExtendWith(MockitoExtension.class)
class DashboardViewTest {

    @Mock
    private RedactionService redactionService;

    private static TextFilterResult textResult(final String filteredText) {
        return new TextFilterResult(filteredText, "none", 0,
                new Explanation(Collections.emptyList(), Collections.emptyList()),
                Collections.emptyList(), 0L);
    }

    @Test
    void redactTextCallsRedactionServiceAndReturnsFilteredText() throws Exception {
        final ObjectId userId = new ObjectId();
        when(redactionService.filter(eq("default"), eq(userId), eq("none"), any(byte[].class), eq(MimeType.TEXT_PLAIN)))
                .thenReturn(textResult("{{{REDACTED-person}}} was president."));

        final String redacted = DashboardView.redactText(redactionService, "default", userId, "George Washington was president.");

        assertEquals("{{{REDACTED-person}}} was president.", redacted);

        // The text dashboard redacts as the signed-in user, with no context and the TEXT_PLAIN mime type.
        verify(redactionService).filter(eq("default"), eq(userId), eq("none"),
                eq("George Washington was president.".getBytes(StandardCharsets.UTF_8)), eq(MimeType.TEXT_PLAIN));
    }

    @Test
    void redactTextPropagatesServiceFailures() throws Exception {
        final ObjectId userId = new ObjectId();
        when(redactionService.filter(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("boom"));

        // The handler surfaces failures as a notification; the seam simply propagates the exception.
        assertThrows(RuntimeException.class,
                () -> DashboardView.redactText(redactionService, "default", userId, "some text"));
    }

}

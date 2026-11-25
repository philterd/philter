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

import ai.philterd.phileas.model.filtering.AbstractFilterResult;
import ai.philterd.phileas.model.filtering.BinaryDocumentFilterResult;
import ai.philterd.phileas.model.filtering.EmailReport;
import ai.philterd.phileas.model.filtering.LeftoverCheckResult;

import java.util.ArrayList;
import java.util.List;

/**
 * The response headers that report what a redaction found beyond its output: the leftover check, and for an email
 * message, what was left uninspected or taken out. None carries a matched value or a filename.
 */
public final class ResultHeaders {

    /** The number of leftover findings, or {@link #NOT_CHECKED}. Absent when the check did not run. */
    public static final String LEFTOVERS = "X-Philter-Leftovers";
    public static final String NOT_CHECKED = "not-checked";

    /** Counts of what an email message's redaction left uninspected or removed. */
    public static final String EMAIL_REPORT = "X-Philter-Email-Report";

    /** The X-Philter-Review reasons. */
    public static final String REVIEW_SAMPLED = "sampled";
    public static final String REVIEW_LEFTOVERS = "leftovers";

    private ResultHeaders() {
    }

    /** The {@link #LEFTOVERS} value for a result, or null when the check did not run. */
    public static String leftovers(final AbstractFilterResult result) {
        final LeftoverCheckResult check = result == null ? null : result.getLeftoverCheck();
        if (check == null) {
            return null;
        }
        if (check.isPerformed()) {
            return Integer.toString(check.getFindingCount());
        }
        return check.getReason() == LeftoverCheckResult.Reason.UNREADABLE_OUTPUT ? NOT_CHECKED : null;
    }

    /** Whether a {@link #LEFTOVERS} value reports findings. */
    public static boolean found(final String leftovers) {
        if (leftovers == null || NOT_CHECKED.equals(leftovers)) {
            return false;
        }
        try {
            return Integer.parseInt(leftovers) > 0;
        } catch (final NumberFormatException e) {
            return false;
        }
    }

    /**
     * The {@link #EMAIL_REPORT} value for an email message's result, or null for any other result. When the leftover
     * check ran, it also counts the redacted attachments the check could not read.
     */
    public static String emailReport(final AbstractFilterResult result) {
        if (!(result instanceof BinaryDocumentFilterResult binary) || binary.getEmail() == null) {
            return null;
        }
        final EmailReport report = binary.getEmail();
        final List<String> counts = new ArrayList<>();
        counts.add("uninspected-attachments=" + report.getUninspectedAttachments().size());
        counts.add("uninspected-inline-images=" + report.getUninspectedInlineImages().size());
        counts.add("removed-attachments=" + report.getRemovedAttachments().size());
        counts.add("dropped-signatures=" + report.getDroppedSignatures().size());
        final LeftoverCheckResult check = result.getLeftoverCheck();
        if (check != null && check.isPerformed()) {
            counts.add("unchecked-attachments=" + check.getUncheckedAttachments().size());
        }
        return String.join(", ", counts);
    }

    /** The X-Philter-Review value for the reasons that apply, or null when none does. */
    public static String review(final boolean sampled, final boolean leftovers) {
        if (sampled && leftovers) {
            return REVIEW_SAMPLED + ", " + REVIEW_LEFTOVERS;
        }
        return sampled ? REVIEW_SAMPLED : leftovers ? REVIEW_LEFTOVERS : null;
    }

}

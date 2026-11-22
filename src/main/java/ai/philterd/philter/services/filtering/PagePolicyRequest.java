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

import ai.philterd.phileas.policy.Policy;
import ai.philterd.phileas.services.pdf.PdfPagePolicies;
import ai.philterd.philter.api.exceptions.BadRequestException;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@code page_policy} request parameter, {@code <pages>:<policy>}: the named policy redacts those pages of a PDF
 * instead of the request's policy.
 */
public record PagePolicyRequest(String pages, String policyName) {

    public static final String PARAMETER = "page_policy";

    static final int MAX_ASSIGNMENTS = 50;

    /** Parses the parameter's values, or returns an empty list when there are none. */
    public static List<PagePolicyRequest> parse(final String[] values) {

        if (values == null || values.length == 0) {
            return List.of();
        }
        if (values.length > MAX_ASSIGNMENTS) {
            throw new BadRequestException("A request can assign policies to at most " + MAX_ASSIGNMENTS + " page sets.");
        }

        final List<PagePolicyRequest> requests = new ArrayList<>();
        // Checks each page set's syntax, and that no two overlap, before any policy is loaded.
        final PdfPagePolicies check = new PdfPagePolicies();

        for (final String value : values) {
            final int colon = value == null ? -1 : value.lastIndexOf(':');
            if (colon <= 0 || colon == value.length() - 1) {
                throw new BadRequestException(PARAMETER + " must be <pages>:<policy>, such as 2-5,8:records, not '"
                        + value + "'.");
            }
            final String pages = value.substring(0, colon).replaceAll("\\s", "");
            final String policyName = value.substring(colon + 1).strip();
            try {
                check.assign(pages, new Policy());
            } catch (final IllegalArgumentException e) {
                throw new BadRequestException(PARAMETER + " '" + value + "': " + e.getMessage());
            }
            requests.add(new PagePolicyRequest(pages, policyName));
        }

        return List.copyOf(requests);

    }

    /** Refuses page sets that extend beyond a PDF's last page, naming the pages. */
    public static void checkPageCount(final List<PagePolicyRequest> requests, final int pageCount) {
        if (requests.isEmpty()) {
            return;
        }
        final PdfPagePolicies check = new PdfPagePolicies();
        for (final PagePolicyRequest request : requests) {
            check.assign(request.pages(), new Policy());
        }
        try {
            check.validate(pageCount);
        } catch (final IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
    }

    /** {@code pages=policy@version; ...}, for a response header and the audit trail. */
    public static String describe(final List<AppliedPagePolicy> applied) {
        final List<String> parts = new ArrayList<>();
        for (final AppliedPagePolicy a : applied) {
            parts.add(a.pages() + "=" + a.policy().name() + "@" + a.policy().version());
        }
        return String.join("; ", parts);
    }

}

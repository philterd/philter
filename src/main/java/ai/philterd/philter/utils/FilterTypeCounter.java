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
package ai.philterd.philter.utils;

import ai.philterd.phileas.model.filtering.Span;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility class for counting filter types from redaction spans.
 */
public class FilterTypeCounter {

    /**
     * Counts the occurrences of each filter type from a list of spans.
     *
     * @param spans The list of spans to count
     * @return A map of filter type names to their counts, or an empty map if spans is null
     */
    public static Map<String, Integer> countFilterTypes(final List<Span> spans) {

        final Map<String, Integer> counts = new HashMap<>();

        if (spans == null) {
            return counts;
        }

        for (final Span span : spans) {
            if (span != null && span.getFilterType() != null) {
                final String filterType = span.getFilterType().getType();
                counts.put(filterType, counts.getOrDefault(filterType, 0) + 1);
            }
        }

        return counts;

    }

}

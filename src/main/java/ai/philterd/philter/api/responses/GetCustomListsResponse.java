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
package ai.philterd.philter.api.responses;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** A page of custom lists and how many there are. With all_users, each list also names its owner. */
public class GetCustomListsResponse {

    private final List<ListSummaryResponse> lists;
    private final long total;

    public GetCustomListsResponse(final List<ListSummaryResponse> lists, final long total) {
        this.lists = lists;
        this.total = total;
    }

    @Schema(description = "The lists on this page, each with its name, description, and number of items.")
    public List<ListSummaryResponse> getLists() { return lists; }

    @Schema(description = "How many lists the listing has across every page.")
    public long getTotal() { return total; }

}

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

import java.util.List;

/**
 * The paging and ordering shared by every listing, so every listing pages, counts, and orders the same way in
 * every storage implementation.
 */
public final class Listings {

    private Listings() {
    }

    /** One page of a listing and how many items the whole listing has. */
    public record Page<E>(List<E> items, long total) {
    }

    /**
     * The order of a listing: a record field, by the name Philter stores it under, and a direction. Ties are broken
     * by the record id in the same direction, so paging is stable when many items share a value. The field
     * {@code _id} orders by the record id, which follows creation order.
     */
    public record Sort(String field, boolean descending) {
    }

}

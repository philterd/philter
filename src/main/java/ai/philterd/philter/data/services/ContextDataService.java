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

import ai.philterd.philter.data.entities.ContextEntity;
import ai.philterd.philter.model.ServiceResponse;

import java.util.List;

/**
 * Each user's redaction contexts and their settings.
 *
 * <p>Guarantees every implementation must provide:
 * <ul>
 *   <li>A context name is unique per user: creating a second context with the same name is refused and
 *   writes nothing.</li>
 *   <li>A user never has more than {@link #MAXIMUM_CONTEXTS_PER_USER} contexts, even when contexts are created
 *   concurrently.</li>
 *   <li>Listings return their total, page by offset without repeating or skipping contexts, sort by the
 *   fields the API offers, and search names as case-insensitive plain text (see {@link Listings}).</li>
 * </ul>
 */
public interface ContextDataService {

    int MAXIMUM_CONTEXTS_PER_USER = 10;

    /** Reasons a create is refused with 409, carried in the response's details. */
    String REASON_CONTEXT_EXISTS = "context_exists";

    String REASON_CONTEXT_LIMIT_REACHED = "context_limit_reached";

    /** The refusal of a disambiguation scope that is not one of the allowed values. */
    String DISAMBIGUATION_SCOPE_REFUSAL =
            "disambiguation_scope must be " + ContextEntity.DISAMBIGUATION_SCOPE_VALUES + ".";

    ServiceResponse create(final String contextName, final String userId);

    ServiceResponse create(final String contextName, final String userId, final boolean disambiguation, final boolean ledger);

    /** As above, with a disambiguation scope: {@code document} or {@code context}, in any case. */
    ServiceResponse create(final String contextName, final String userId, final boolean disambiguation,
            final String disambiguationScope, final boolean ledger);

    /**
     * Returns every context for every user. Intended for admin-only views; ordinary access must use the
     * owner-scoped {@link #findAll(String)}.
     */
    List<ContextEntity> findAllAcrossUsers();

    /**
     * Returns one page of contexts across every user, ordered by name. Intended for admin-only views;
     * ordinary access must use the owner-scoped {@link #findAll(String)}.
     */
    List<ContextEntity> findAllAcrossUsers(final int offset, final int limit);

    /** Returns the total number of contexts across every user (for admin paging). */
    int countAllAcrossUsers();

    List<ContextEntity> findAll(final String userId);

    ContextEntity findOneByIdAndUserId(final String id, final String userId);

    ContextEntity findOneByNameAndUserId(final String contextName, final String userId);

    /**
     * A page of the user's contexts, or every user's when {@code userId} is null, whose names contain
     * {@code q}, and their total.
     */
    Listings.Page<ContextEntity> list(final String userId, final String q, final Listings.Sort sort,
            final int offset, final int limit);

    List<ContextEntity> findAll(final String userId, final int offset, final int limit);

    List<ContextEntity> findAll(final String userId, final int offset, final int limit, final String sortField, final String sortDirection);

    int count(final String userId);

    ContextEntity findOne(final String contextName, final String userId);

    /**
     * Changes a context's settings. A {@code null} setting keeps its current value, so changing one never
     * changes another. The disambiguation scope is {@code document} or {@code context}, in any case. Audited
     * as {@code context_updated}, naming each setting whose value changed, the acting user, and the acting
     * API key.
     */
    ServiceResponse updateSettings(final String requestId, final String contextName, final String userId,
            final Boolean disambiguation, final String disambiguationScope,
            final Boolean ledger, final String actingUserId,
            final String actingApiKeyId);

    ServiceResponse emptyByName(final String contextName, final String userId);

    ServiceResponse deleteByName(final String contextName, final String userId);

    ServiceResponse deleteByName(final String contextName, final String requesterUserId, final boolean requesterIsAdmin);

}

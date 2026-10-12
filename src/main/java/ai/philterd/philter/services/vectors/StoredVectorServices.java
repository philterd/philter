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
package ai.philterd.philter.services.vectors;

import ai.philterd.phileas.model.filtering.FilterType;
import ai.philterd.phileas.model.filtering.Span;
import ai.philterd.phileas.services.disambiguation.vector.VectorService;
import ai.philterd.philter.data.entities.VectorEntity;
import ai.philterd.philter.utils.EnvUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The rules for disambiguation vectors, the same in every storage implementation: zero hashes are not stored, a
 * context holds at most {@link #MAX_VECTORS_PER_CONTEXT} vectors with the oldest evicted first, and a
 * representation counts each hash. An implementation stores the vectors.
 */
public abstract class StoredVectorServices implements VectorServices {

    public static final int MAX_VECTORS_PER_CONTEXT = EnvUtils.getInt("MAX_VECTORS_PER_CONTEXT", 100000);

    /** Stores a vector. */
    protected abstract void insert(final VectorEntity vector);

    /** How many vectors the user's context holds. */
    protected abstract long count(final String userId, final String context);

    /** Deletes the user's context's oldest vector. */
    protected abstract void deleteOldest(final String userId, final String context);

    /** The hashes of the user's context's vectors of one filter type, one per stored vector. */
    protected abstract List<Double> findHashes(final String userId, final String context, final String filterType);

    @Override
    public VectorService forUser(final String userId) {

        return new VectorService() {

            @Override
            public void hashAndInsert(final String context, final double[] hashes, final Span span, final int vectorSize) {
                for (final double hash : hashes) {
                    if (hash != 0) {
                        if (count(userId, context) >= MAX_VECTORS_PER_CONTEXT) {
                            deleteOldest(userId, context);
                        }
                        final VectorEntity vectorEntity = new VectorEntity();
                        vectorEntity.setUserId(userId);
                        vectorEntity.setContext(context);
                        vectorEntity.setHash(hash);
                        vectorEntity.setSpan(span);
                        vectorEntity.setVectorSize(vectorSize);
                        vectorEntity.setFilterType(span.getFilterType().name());
                        insert(vectorEntity);
                    }
                }
            }

            @Override
            public Map<Double, Double> getVectorRepresentation(final String context, final FilterType filterType) {
                final Map<Double, Double> vectorRepresentation = new HashMap<>();
                for (final Double hash : findHashes(userId, context, filterType.name())) {
                    vectorRepresentation.merge(hash, 1.0, Double::sum);
                }
                return vectorRepresentation;
            }

        };

    }

}

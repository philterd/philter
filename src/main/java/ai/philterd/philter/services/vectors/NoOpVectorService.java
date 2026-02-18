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

import java.util.Map;

/**
 * Implementation of {@link VectorService} that does nothing.
 * It is used when entity type disambiguation is disabled.
 */
public class NoOpVectorService implements VectorService {

    @Override
    public void hashAndInsert(String context, double[] hashes, Span span, int vectorSize) {

    }

    @Override
    public Map<Double, Double> getVectorRepresentation(String context, FilterType filterType) {
        return Map.of();
    }

}

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
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.VectorEntity;
import ai.philterd.philter.data.services.AbstractService;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import java.util.HashMap;
import java.util.Map;

public class MongoVectorService extends AbstractService<VectorEntity> implements VectorService {

    private final ObjectId userId;

    public MongoVectorService(final MongoClient mongoClient, final ObjectId userId, final AuditEventPublisher auditEventPublisher) {

        super(mongoClient, "vectors", auditEventPublisher);
        this.userId = userId;

    }

    @Override
    public void hashAndInsert(final String context, final double[] hashes, final Span span, final int vectorSize) {

        // TODO: Should there be a limit on the number of stored vectors per context?
        // See https://github.com/philterd/philterd-data-services/issues/283

        for (double hash : hashes) {

            if (hash != 0) {

                final VectorEntity vectorEntity = new VectorEntity();
                vectorEntity.setContext(context);
                vectorEntity.setHash(hash);
                vectorEntity.setSpan(span);
                vectorEntity.setVectorSize(vectorSize);
                vectorEntity.setFilterType(span.getFilterType().name());

                save(vectorEntity);

            }

        }

    }

    @Override
    public Map<Double, Double> getVectorRepresentation(final String context, final FilterType filterType) {

        final Map<Double, Double> vectorRepresentation = new HashMap<>();

        final Bson filter = Filters.and(
                Filters.eq("user_id", userId),
                Filters.eq("context", context),
                Filters.eq("filter_type", filterType.name())
        );

        for (final Document document : collection.find(filter)) {

            final VectorEntity vectorEntity = VectorEntity.fromDocument(document);

            vectorRepresentation.putIfAbsent(vectorEntity.getHash(), 0.0);

            vectorRepresentation.compute(vectorEntity.getHash(), (k, value) -> value + 1.0);

        }

        return vectorRepresentation;

    }

}
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
package ai.philterd.philter.data.providers;

import ai.philterd.philter.data.entities.PolicyEntity;
import ai.philterd.philter.data.services.PolicyDataService;
import com.vaadin.flow.data.provider.AbstractBackEndDataProvider;
import com.vaadin.flow.data.provider.Query;
import com.vaadin.flow.data.provider.QuerySortOrder;
import com.vaadin.flow.data.provider.SortDirection;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

import java.util.stream.Stream;

@Service
public class PolicyEntityDataProvider extends AbstractBackEndDataProvider<PolicyEntity, Void> {

    private final PolicyDataService policyService;

    public PolicyEntityDataProvider(final PolicyDataService policyService) {
        this.policyService = policyService;
    }

    @Override
    protected Stream<PolicyEntity> fetchFromBackEnd(final Query<PolicyEntity, Void> query) {
        final int offset = query.getOffset();
        final int limit = query.getLimit();

        // Extract sort information from the query
        // Note: Currently only the first sort order is used; multi-column sorting is not supported
        if (!query.getSortOrders().isEmpty()) {
            final QuerySortOrder sortOrder = query.getSortOrders().get(0);
            final String sortField = sortOrder.getSorted();
            final boolean ascending = sortOrder.getDirection() == SortDirection.ASCENDING;

            // Map Java property name to MongoDB field name
            final String mongoField = mapPropertyToMongoField(sortField);

            return policyService.findAll(null, offset, limit, false, mongoField, ascending).stream();
        } else {
            // No sorting specified, use default
            return policyService.findAll(null, offset, limit, false).stream();
        }
    }

    /**
     * Maps Java property names to MongoDB field names.
     * Currently name and description are sortable in the UI grid.
     */
    private String mapPropertyToMongoField(final String propertyName) {
        return switch (propertyName) {
            case "name" -> "name";
            case "description" -> "description";
            case "createdTimestamp" -> "created_timestamp";
            case "lastUpdatedTimestamp" -> "last_updated_timestamp";
            default -> propertyName;
        };
    }

    @Override
    protected int sizeInBackEnd(final Query<PolicyEntity, Void> query) {
        return policyService.count(null);
    }

}

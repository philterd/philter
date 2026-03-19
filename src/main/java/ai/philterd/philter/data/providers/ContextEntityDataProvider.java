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

import ai.philterd.philter.data.entities.ContextEntity;
import ai.philterd.philter.data.services.ContextDataService;
import com.vaadin.flow.data.provider.AbstractBackEndDataProvider;
import com.vaadin.flow.data.provider.Query;
import com.vaadin.flow.data.provider.QuerySortOrder;
import com.vaadin.flow.data.provider.SortDirection;
import org.bson.types.ObjectId;

import java.util.stream.Stream;

/**
 * Used by the grid on the contexts view for paging.
 */
public class ContextEntityDataProvider extends AbstractBackEndDataProvider<ContextEntity, Void> {

    private final ContextDataService contextService;
    private final ObjectId userId;

    public ContextEntityDataProvider(final ObjectId userId, final ContextDataService contextService) {
        this.contextService = contextService;
        this.userId = userId;
    }

    @Override
    protected Stream<ContextEntity> fetchFromBackEnd(final Query<ContextEntity, Void> query) {

        final int offset = query.getOffset();
        final int limit = query.getLimit();
        
        // Extract sort information from the query
        String sortField = null;
        String sortDirection = null;
        
        if (!query.getSortOrders().isEmpty()) {
            final QuerySortOrder sortOrder = query.getSortOrders().get(0);
            sortField = sortOrder.getSorted();
            sortDirection = sortOrder.getDirection() == SortDirection.ASCENDING ? "ASC" : "DESC";
        }
        
        return contextService.findAll(userId, offset, limit, sortField, sortDirection).stream();

    }

    @Override
    protected int sizeInBackEnd(final Query<ContextEntity, Void> query) {
        return contextService.count(userId);
    }

}

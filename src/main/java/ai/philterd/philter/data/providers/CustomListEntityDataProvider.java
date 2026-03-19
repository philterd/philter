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

import ai.philterd.philter.data.entities.CustomListEntity;
import ai.philterd.philter.data.services.CustomListDataService;
import com.vaadin.flow.data.provider.AbstractBackEndDataProvider;
import com.vaadin.flow.data.provider.Query;
import com.vaadin.flow.data.provider.QuerySortOrder;
import com.vaadin.flow.data.provider.SortDirection;
import org.bson.types.ObjectId;

import java.util.stream.Stream;

/**
 * Used by the grid on the custom lists view for paging.
 */
public class CustomListEntityDataProvider extends AbstractBackEndDataProvider<CustomListEntity, Void> {

    private final CustomListDataService customListService;
    private final ObjectId userId;

    public CustomListEntityDataProvider(final ObjectId userId, final CustomListDataService customListService) {
        this.customListService = customListService;
        this.userId = userId;
    }

    @Override
    protected Stream<CustomListEntity> fetchFromBackEnd(final Query<CustomListEntity, Void> query) {
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
        
        return customListService.findAll(userId, offset, limit, sortField, sortDirection).stream();
    }

    @Override
    protected int sizeInBackEnd(final Query<CustomListEntity, Void> query) {
        return customListService.count(userId);
    }

}

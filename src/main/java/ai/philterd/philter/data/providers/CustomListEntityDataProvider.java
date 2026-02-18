package ai.philterd.philter.data.providers;

import ai.philterd.philter.data.entities.CustomListEntity;
import ai.philterd.philter.data.services.CustomListDataService;
import com.vaadin.flow.data.provider.AbstractBackEndDataProvider;
import com.vaadin.flow.data.provider.Query;
import com.vaadin.flow.data.provider.QuerySortOrder;
import com.vaadin.flow.data.provider.SortDirection;

import java.util.stream.Stream;

/**
 * Used by the grid on the custom lists view for paging.
 */
public class CustomListEntityDataProvider extends AbstractBackEndDataProvider<CustomListEntity, Void> {

    private final CustomListDataService customListService;

    public CustomListEntityDataProvider(final CustomListDataService customListService) {
        this.customListService = customListService;
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
        
        return customListService.findAll(offset, limit, sortField, sortDirection).stream();
    }

    @Override
    protected int sizeInBackEnd(final Query<CustomListEntity, Void> query) {
        return customListService.count();
    }

}

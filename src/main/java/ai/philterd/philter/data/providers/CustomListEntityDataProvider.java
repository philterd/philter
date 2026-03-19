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

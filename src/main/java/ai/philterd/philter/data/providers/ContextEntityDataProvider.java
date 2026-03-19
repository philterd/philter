package ai.philterd.philter.data.providers;

import ai.philterd.philter.data.entities.ContextEntity;
import ai.philterd.philter.data.services.ContextDataService;
import com.vaadin.flow.data.provider.AbstractBackEndDataProvider;
import com.vaadin.flow.data.provider.Query;
import com.vaadin.flow.data.provider.QuerySortOrder;
import com.vaadin.flow.data.provider.SortDirection;

import java.util.stream.Stream;

/**
 * Used by the grid on the contexts view for paging.
 */
public class ContextEntityDataProvider extends AbstractBackEndDataProvider<ContextEntity, Void> {

    private final ContextDataService contextService;

    public ContextEntityDataProvider(final ContextDataService contextService) {
        this.contextService = contextService;
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
        
        return contextService.findAll(null, offset, limit, sortField, sortDirection).stream();
    }

    @Override
    protected int sizeInBackEnd(final Query<ContextEntity, Void> query) {
        return contextService.count(null);
    }

}

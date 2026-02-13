package ai.philterd.philter.data;

import ai.philterd.philter.data.entities.PolicyEntity;
import ai.philterd.philter.services.PolicyDataService;
import com.vaadin.flow.data.provider.AbstractBackEndDataProvider;
import com.vaadin.flow.data.provider.Query;
import org.springframework.stereotype.Service;

import java.util.stream.Stream;

@Service
public class PoliciesDataProvider extends AbstractBackEndDataProvider<PolicyEntity, Void> {

    private final PolicyDataService policyDataService;

    public PoliciesDataProvider(final PolicyDataService policyDataService) {
        this.policyDataService = policyDataService;
    }

    @Override
    protected Stream<PolicyEntity> fetchFromBackEnd(Query<PolicyEntity, Void> query) {
        return policyDataService.get(query.getOffset(), query.getLimit()).stream();
    }

    @Override
    protected int sizeInBackEnd(Query<PolicyEntity, Void> query) {
        return policyDataService.count();
    }

}

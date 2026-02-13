package ai.philterd.philter.data.providers;

import ai.philterd.philter.data.entities.PolicyEntity;
import ai.philterd.philter.services.PolicyDataService;
import com.vaadin.flow.data.provider.AbstractBackEndDataProvider;
import com.vaadin.flow.data.provider.Query;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.util.stream.Stream;

@Service
public class PolicyEntityDataProvider extends AbstractBackEndDataProvider<PolicyEntity, Void> {

    private static final Logger LOGGER = LogManager.getLogger(PolicyEntityDataProvider.class);

    private final PolicyDataService policyDataService;

    public PolicyEntityDataProvider( final PolicyDataService policyDataService) {
        this.policyDataService = policyDataService;
    }

    @Override
    protected Stream<PolicyEntity> fetchFromBackEnd(final Query<PolicyEntity, Void> query) {

        final int offset = query.getOffset();
        final int limit = query.getLimit();

        return policyDataService.get(offset, limit).stream();

    }

    @Override
    protected int sizeInBackEnd(final Query<PolicyEntity, Void> query) {
        return policyDataService.count();
    }

}

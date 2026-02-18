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
        return policyDataService.findAll(query.getOffset(), query.getLimit(), false).stream();
    }

    @Override
    protected int sizeInBackEnd(Query<PolicyEntity, Void> query) {
        return policyDataService.count();
    }

}

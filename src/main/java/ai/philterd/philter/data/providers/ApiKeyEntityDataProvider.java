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

import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import com.vaadin.flow.data.provider.AbstractBackEndDataProvider;
import com.vaadin.flow.data.provider.Query;
import org.bson.types.ObjectId;

import java.util.stream.Stream;

/**
 * Used by the grid on the account view for paging API keys.
 */
public class ApiKeyEntityDataProvider extends AbstractBackEndDataProvider<ApiKeyEntity, Void> {

    private final ApiKeyDataService apiKeyService;

    public ApiKeyEntityDataProvider(final ApiKeyDataService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @Override
    protected Stream<ApiKeyEntity> fetchFromBackEnd(final Query<ApiKeyEntity, Void> query) {
        final int offset = query.getOffset();
        final int limit = query.getLimit();
        return apiKeyService.findAll(offset, limit).stream();
    }

    @Override
    protected int sizeInBackEnd(final Query<ApiKeyEntity, Void> query) {
        return apiKeyService.count();
    }

}

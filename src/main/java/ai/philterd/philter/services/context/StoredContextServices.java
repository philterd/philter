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
package ai.philterd.philter.services.context;

import ai.philterd.phileas.services.context.ContextService;
import ai.philterd.philter.data.services.StoredContextEntryDataService;
import ai.philterd.philter.services.cache.ContextCache;

/** Context services over the stored context entries, the same for every storage implementation. */
public class StoredContextServices implements ContextServices {

    private final StoredContextEntryDataService contextEntryService;

    public StoredContextServices(final StoredContextEntryDataService contextEntryService) {
        this.contextEntryService = contextEntryService;
    }

    @Override
    public ContextService forContext(final ContextCache contextCache, final String userId, final String contextName) {
        return new StoredContextService(contextCache, contextEntryService.withCache(contextCache), userId, contextName);
    }

}

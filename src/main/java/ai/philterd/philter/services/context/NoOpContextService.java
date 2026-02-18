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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NoOpContextService implements ContextService {

    private static final Logger LOGGER = LoggerFactory.getLogger(NoOpContextService.class);

    public NoOpContextService() {

        LOGGER.info("Initializing NoOp context service.");

    }

    @Override
    public boolean containsToken(final String token) {
        return false;
    }

    @Override
    public boolean containsReplacement(final String replacement) {
        return false;
    }

    @Override
    public String getReplacement(final String token) {
        return null;
    }

    @Override
    public void putReplacement(final String token, final String replacement, final String filterType) {

    }

}
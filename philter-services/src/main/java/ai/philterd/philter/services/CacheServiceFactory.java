/*
 *     Copyright 2024 Philterd, LLC @ https://www.philterd.ai
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
package ai.philterd.philter.services;

import ai.philterd.phileas.model.cache.InMemoryCache;
import ai.philterd.phileas.model.services.CacheService;
import ai.philterd.philter.PhilterConfiguration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

/**
 * Factory for creating caches.
 */
public class CacheServiceFactory {

    private static final Logger LOGGER = LogManager.getLogger(CacheServiceFactory.class);

    public static CacheService getCacheService(final PhilterConfiguration philterConfiguration) throws IOException {

        if(!philterConfiguration.cacheEnabled()) {

            LOGGER.info("Using an in-memory cache.");
            return new InMemoryCache();

        } else {

            LOGGER.info("Using a distributed cache.");
            return new DistributedCacheService(philterConfiguration);

        }

    }

}

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
package ai.philterd.philter.api.controllers;

import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.services.cache.ApiKeyCache;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public abstract class AbstractApiController {

    private static final Logger LOGGER = LogManager.getLogger(AbstractApiController.class);

    protected final ApiKeyDataService apiKeyService;
    protected final ApiKeyCache apiKeyCache;

    protected AbstractApiController(final ApiKeyDataService apiKeyService, final ApiKeyCache apiKeyCache) {
        this.apiKeyService = apiKeyService;
        this.apiKeyCache = apiKeyCache;
    }

    public ApiKeyEntity getApiKeyEntity(final String authorizationHeader) {

        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return null;
        }

        final String apiKey = authorizationHeader.substring(7);

        if(apiKeyCache.containsApiKey(apiKey)) {

            return apiKeyCache.get(apiKey);

        } else {

            // Get it from the database and cache it if not null.
            final ApiKeyEntity apiKeyEntity = apiKeyService.findOneByApiKey(apiKey);

            if(apiKeyEntity != null) {

                apiKeyCache.insert(apiKey, apiKeyEntity);
                return apiKeyEntity;

            } else {
                return null;
            }

        }

    }

    public static String getClientIpAddress(final HttpServletRequest httpServletRequest) {

        // With App Runner you can access the original source IPv4 and IPv6 addresses of the traffic entering your application.
        // The original source IP addresses are preserved by assigning the X-Forwarded-For request header to them.
        // This enables your applications to fetch the original source IP addresses when needed.

        String ipAddress = httpServletRequest.getHeader("X-Forwarded-For");

        if (ipAddress == null || ipAddress.isEmpty() || "unknown".equalsIgnoreCase(ipAddress)) {

            // Fallback to getRemoteAddr() if X-Forwarded-For is not present or invalid
            ipAddress = httpServletRequest.getRemoteAddr();

        } else {

            // X-Forwarded-For can contain multiple IPs if passing through multiple proxies
            // The first IP in the list is typically the client's original IP
            int commaIndex = ipAddress.indexOf(',');
            if (commaIndex > -1) {
                ipAddress = ipAddress.substring(0, commaIndex).trim();
            }

        }

        return ipAddress;

    }

}
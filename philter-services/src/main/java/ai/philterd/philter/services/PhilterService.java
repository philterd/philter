/*
 *     Copyright 2025 Philterd, LLC @ https://www.philterd.ai
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

import ai.philterd.phileas.metrics.PhilterMetricsService;
import ai.philterd.phileas.model.configuration.PhileasConfiguration;
import ai.philterd.phileas.model.enums.MimeType;
import ai.philterd.phileas.model.policy.Policy;
import ai.philterd.phileas.model.responses.BinaryDocumentFilterResponse;
import ai.philterd.phileas.model.responses.FilterResponse;
import ai.philterd.phileas.model.services.AlertService;
import ai.philterd.phileas.model.services.CacheService;
import ai.philterd.phileas.model.services.FilterService;
import ai.philterd.phileas.model.services.MetricsService;
import ai.philterd.phileas.services.PhileasFilterService;
import ai.philterd.philter.PhilterConfiguration;
import ai.philterd.philter.services.policies.InMemoryPolicyService;
import ai.philterd.philter.services.policies.LocalPolicyService;
import ai.philterd.philter.services.policies.OpenSearchPolicyService;
import ai.philterd.philter.services.policies.PolicyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class PhilterService implements FilterService {

    private final FilterService phileasFilterService;

    @Autowired
    private MetricsService metricsService;

    @Autowired
    private CacheService cacheService;

    @Autowired
    public PhilterService(PhileasConfiguration phileasConfiguration) throws Exception {
        this.phileasFilterService = new PhileasFilterService(phileasConfiguration, metricsService, cacheService);
    }

    @Override
    public FilterResponse filter(Policy policy, String context, String documentId, String input, MimeType mimeType) throws Exception {
        return phileasFilterService.filter(policy, context, documentId, input, mimeType);
    }

    @Override
    public BinaryDocumentFilterResponse filter(Policy policy, String context, String documentId, byte[] input, MimeType mimeType, MimeType outputMimeType) throws Exception {
        return phileasFilterService.filter(policy, context, documentId, input, mimeType, outputMimeType);
    }

    @Override
    public AlertService getAlertService() {
        return phileasFilterService.getAlertService();
    }

}

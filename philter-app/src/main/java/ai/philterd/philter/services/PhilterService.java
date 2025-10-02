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

import ai.philterd.phileas.PhileasConfiguration;
import ai.philterd.phileas.model.objects.BinaryDocumentFilterResponse;
import ai.philterd.phileas.model.objects.FilterResponse;
import ai.philterd.phileas.policy.Policy;
import ai.philterd.phileas.services.context.ContextService;
import ai.philterd.phileas.services.disambiguation.vector.VectorService;
import ai.philterd.phileas.services.filters.FilterService;
import ai.philterd.phileas.model.enums.MimeType;
import ai.philterd.phileas.services.PhileasFilterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class PhilterService implements FilterService {

    private final FilterService phileasFilterService;

    @Autowired
    public PhilterService(PhileasConfiguration phileasConfiguration, ContextService contextService, VectorService vectorService) {
        this.phileasFilterService = new PhileasFilterService(phileasConfiguration, contextService, vectorService);
    }

    @Override
    public FilterResponse filter(Policy policy, String context, String documentId, String input, MimeType mimeType) throws Exception {
        return phileasFilterService.filter(policy, context, documentId, input, mimeType);
    }

    @Override
    public BinaryDocumentFilterResponse filter(Policy policy, String context, String documentId, byte[] input, MimeType mimeType, MimeType outputMimeType) throws Exception {
        return phileasFilterService.filter(policy, context, documentId, input, mimeType, outputMimeType);
    }

}

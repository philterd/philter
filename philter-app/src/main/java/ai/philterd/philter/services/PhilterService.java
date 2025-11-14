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
import ai.philterd.phileas.model.filtering.ApplyResult;
import ai.philterd.phileas.model.filtering.BinaryDocumentFilterResult;
import ai.philterd.phileas.model.filtering.TextFilterResult;
import ai.philterd.phileas.model.filtering.MimeType;
import ai.philterd.phileas.model.filtering.Span;
import ai.philterd.phileas.policy.Policy;
import ai.philterd.phileas.services.context.ContextService;
import ai.philterd.phileas.services.disambiguation.vector.VectorService;
import ai.philterd.phileas.services.filters.FilterService;
import ai.philterd.phileas.services.PhileasFilterService;
import org.apache.commons.lang3.NotImplementedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PhilterService implements FilterService {

    private final FilterService phileasFilterService;

    @Autowired
    public PhilterService(PhileasConfiguration phileasConfiguration, ContextService contextService, VectorService vectorService) {
        this.phileasFilterService = new PhileasFilterService(phileasConfiguration, contextService, vectorService);
    }

    @Override
    public TextFilterResult filter(Policy policy, String context, String input) throws Exception {
        return phileasFilterService.filter(policy, context, input);
    }

    @Override
    public BinaryDocumentFilterResult filter(Policy policy, String context, byte[] input, MimeType mimeType, MimeType outputMimeType) throws Exception {
        return phileasFilterService.filter(policy, context, input, mimeType, outputMimeType);
    }

    @Override
    public ApplyResult apply(final List<Span> spans, final String input) {
        throw new NotImplementedException();
    }


}

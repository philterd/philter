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

import ai.philterd.phileas.metrics.PhilterMetricsService;
import ai.philterd.phileas.model.configuration.PhileasConfiguration;
import ai.philterd.phileas.model.enums.MimeType;
import ai.philterd.phileas.model.objects.Span;
import ai.philterd.phileas.model.policy.Policy;
import ai.philterd.phileas.model.responses.BinaryDocumentFilterResponse;
import ai.philterd.phileas.model.responses.FilterResponse;
import ai.philterd.phileas.model.services.AlertService;
import ai.philterd.phileas.model.services.FilterService;
import ai.philterd.phileas.model.services.PolicyService;
import ai.philterd.phileas.services.PhileasFilterService;
import ai.philterd.philter.PhilterConfiguration;
import ai.philterd.redactionhub.client.RedactionHubClient;
import ai.philterd.redactionhub.model.RedactedObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class PhilterService implements FilterService {

    private final FilterService phileasFilterService;

    private final RedactionHubClient redactionHubClient;
    private final String certificateName;
    private final boolean signingEnabled;
    private final boolean indexingEnabled;

    @Autowired
    public PhilterService(final PhileasConfiguration phileasConfiguration) throws Exception {

        final PhilterConfiguration philterConfiguration = new PhilterConfiguration("philter.properties", "Philter");
        final PhilterMetricsService philterMetricsService = new PhilterMetricsService(philterConfiguration);
        this.phileasFilterService = new PhileasFilterService(phileasConfiguration, philterMetricsService);

        if(philterConfiguration.redactionHubEnabled()) {
            this.redactionHubClient = new RedactionHubClient(philterConfiguration.redactionHubApiKey(), philterConfiguration.redactionHubBaseUrl(), philterConfiguration.redactionHubTimeOut(), philterConfiguration.redactionHubIgnoreSsl());
            this.certificateName = philterConfiguration.redactionHubCertificateName();
            this.signingEnabled = philterConfiguration.redactionHubSigningEnabled();
            this.indexingEnabled = philterConfiguration.redactionHubIndexingEnabled();
        } else {
            this.redactionHubClient = null;
            this.certificateName = null;
            this.signingEnabled = false;
            this.indexingEnabled = false;
        }

    }

    @Override
    public FilterResponse filter(final Policy policy, final String context, final String documentId, final String input, final MimeType mimeType) throws Exception {
        return filter(List.of(policy.getName()), context, documentId, input, mimeType);
    }

    @Override
    public FilterResponse filter(final List<String> policyNames, final String context, final String documentId, final String input, final MimeType mimeType) throws Exception {

        final FilterResponse filterResponse = phileasFilterService.filter(policyNames, context, documentId, input, mimeType);

        String indexedId = null;
        String signature = null;

        if(signingEnabled) {

            signature = redactionHubClient.sign(filterResponse.getFilteredText(), certificateName);

        }

        if(indexingEnabled) {

            final List<ai.philterd.redactionhub.model.Span> spans = new ArrayList<>();

            // Convert the Phileas spans to Redaction Hub spans.
            for(final Span span : filterResponse.getExplanation().appliedSpans()) {

                final ai.philterd.redactionhub.model.Span spanObject = new ai.philterd.redactionhub.model.Span(span.getCharacterStart(),
                        span.getCharacterEnd(), span.getFilterType().toString(), span.getReplacement(), span.getConfidence(),
                        span.getText(), span.getReplacement(), span.getSalt());

                spans.add(spanObject);
            }

            final RedactedObject redactedObject = new RedactedObject();
            redactedObject.setText(filterResponse.getFilteredText());
            redactedObject.setRedacted(filterResponse.getFilteredText());
            redactedObject.setSpans(spans);
            redactedObject.setContext(filterResponse.getContext());
            redactedObject.setDocumentId(filterResponse.getDocumentId());
            redactedObject.setCertificate(certificateName);
            redactedObject.setSignature(signature);

            indexedId = redactionHubClient.index(redactedObject);

        }

        if(signingEnabled || indexingEnabled) {

            return new RedactionHubFilterResponse(filterResponse.getFilteredText(), filterResponse.getContext(),
                    filterResponse.getDocumentId(), filterResponse.getPiece(), filterResponse.getExplanation(), filterResponse.getAttributes(),
                    signature, indexedId);

        } else {

            return filterResponse;

        }

    }

    @Override
    public BinaryDocumentFilterResponse filter(final List<String> policyNames, final String context, final String documentId, final byte[] input, final MimeType mimeType, final MimeType outputMimeType) throws Exception {
        return phileasFilterService.filter(policyNames, context, documentId, input, mimeType, outputMimeType);
    }

    @Override
    public PolicyService getPolicyService() {
        return phileasFilterService.getPolicyService();
    }

    @Override
    public AlertService getAlertService() {
        return phileasFilterService.getAlertService();
    }

}

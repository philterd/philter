package ai.philterd.philter.services;

import ai.philterd.phileas.model.configuration.PhileasConfiguration;
import ai.philterd.phileas.model.enums.MimeType;
import ai.philterd.phileas.model.policy.Policy;
import ai.philterd.phileas.model.responses.BinaryDocumentFilterResponse;
import ai.philterd.phileas.model.responses.FilterResponse;
import ai.philterd.phileas.model.services.AlertService;
import ai.philterd.phileas.model.services.FilterService;
import ai.philterd.phileas.model.services.PolicyService;
import ai.philterd.phileas.services.PhileasFilterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

@Component
public class PhilterService implements FilterService {

    private final FilterService phileasFilterService;

    @Autowired
    public PhilterService(PhileasConfiguration phileasConfiguration) throws IOException {
        this.phileasFilterService = new PhileasFilterService(phileasConfiguration);
    }

    @Override
    public FilterResponse filter(Policy policy, String context, String documentId, String input, MimeType mimeType) throws Exception {
        return phileasFilterService.filter(policy, context, documentId, input, mimeType);
    }

    @Override
    public FilterResponse filter(List<String> policyNames, String context, String documentId, String input, MimeType mimeType) throws Exception {
        return phileasFilterService.filter(policyNames, context, documentId, input, mimeType);
    }

    @Override
    public BinaryDocumentFilterResponse filter(List<String> policyNames, String context, String documentId, byte[] input, MimeType mimeType, MimeType outputMimeType) throws Exception {
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

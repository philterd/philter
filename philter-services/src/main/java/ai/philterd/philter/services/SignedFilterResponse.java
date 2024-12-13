package ai.philterd.philter.services;

import ai.philterd.phileas.model.objects.Explanation;
import ai.philterd.phileas.model.responses.FilterResponse;

import java.util.Map;

public class SignedFilterResponse extends FilterResponse {

    private String signature;

    public SignedFilterResponse(String filteredText, String context, String documentId, int piece, Explanation explanation, Map<String, String> attributes, String signature) {
        super(filteredText, context, documentId, piece, explanation, attributes);
        this.signature = signature;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

}

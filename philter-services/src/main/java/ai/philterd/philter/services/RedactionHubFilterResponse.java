package ai.philterd.philter.services;

import ai.philterd.phileas.model.objects.Explanation;
import ai.philterd.phileas.model.responses.FilterResponse;

import java.util.Map;

public class RedactionHubFilterResponse extends FilterResponse {

    private final String signature;
    private final String indexedId;

    public RedactionHubFilterResponse(String filteredText, String context, String documentId, int piece, Explanation explanation, Map<String, String> attributes, String signature, String indexedId) {
        super(filteredText, context, documentId, piece, explanation, attributes);
        this.signature = signature;
        this.indexedId = indexedId;
    }

    public String getSignature() {
        return signature;
    }

    public String getIndexedId() {
        return indexedId;
    }

}

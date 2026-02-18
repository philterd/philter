package ai.philterd.philter.api.filters.size;

/**
 * Constants for filter configuration.
 */
public final class FilterConstants {

    /**
     * Document redaction upload endpoint.
     */
    public static final String DOCUMENT_REDACTION_ENDPOINT = "/api/redact/documents";

    /**
     * Risk assessment upload endpoint.
     */
    public static final String RISK_ASSESSMENT_ENDPOINT = "/api/risk";

    private FilterConstants() {
        // Private constructor to prevent instantiation
    }

}

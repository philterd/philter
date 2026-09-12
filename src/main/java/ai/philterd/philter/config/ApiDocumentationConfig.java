package ai.philterd.philter.config;

import ai.philterd.philter.api.responses.*;
import ai.philterd.philter.api.requests.RedactListsRequest;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Describes JSON serialized through Gson and the media variants merged into POST /api/filter. */
@Configuration
public class ApiDocumentationConfig {
    @Bean
    public OpenApiCustomizer apiWireContracts() {
        return api -> {
            response(op(api, "/api/policies/{policyName}", "get"), "200", "Native policy JSON, preserving field names.",
                    content("application/json", new ObjectSchema().additionalProperties(true)));
            op(api, "/api/policies", "post").getRequestBody().setContent(content("application/json",
                    new ObjectSchema().additionalProperties(true).description("Native Phileas policy. identifiers is required; custom dictionaries use dictionaries.")));
            json(api, "/api/audit", "get", GetAuditResponse.class);
            json(api, "/api/contexts", "get", GetContextsResponse.class);
            json(api, "/api/contexts/{name}", "get", GetContextResponse.class);
            json(api, "/api/contexts/{name}/entries", "get", GetContextEntriesResponse.class);
            json(api, "/api/contexts/{name}/entries/export", "get", ContextEntriesExport.class);
            json(api, "/api/contexts/{name}/entries/import", "post", ImportContextEntriesResponse.class);
            op(api, "/api/contexts/{name}/entries/import", "post").getRequestBody()
                    .setContent(content("application/json", model(api, ContextEntriesExport.class)));
            json(api, "/api/documents", "get", GetDocumentsResponse.class);
            json(api, "/api/documents/{documentId}/status", "get", GetRedactionStatusResponse.class);
            json(api, "/api/ledger", "get", GetLedgerResponse.class);
            json(api, "/api/ledger/{documentId}", "get", LedgerChainResponse.class);
            json(api, "/api/ledger/{documentId}/valid", "get", LedgerChainResponse.class);
            json(api, "/api/ledger/{documentId}/export", "get", LedgerExport.class);
            json(api, "/api/redact-lists", "get", RedactListsResponse.class);
            for (String verb : new String[]{"post", "put"}) {
                op(api, "/api/redact-lists", verb).getRequestBody()
                        .setContent(content("application/json", model(api, RedactListsRequest.class)));
            }
            response(op(api, "/api/lists", "get"), "200", "List names.",
                    content("application/json", new ArraySchema().items(new StringSchema())));
            json(api, "/api/reidentify", "post", ReidentifyResponse.class);
            response(op(api, "/api/policies/compile", "post"), "200", "Compiled native policy; does not save it.",
                    content("application/json", object("name", new StringSchema(), "description", new StringSchema(),
                            "policy", new ObjectSchema().additionalProperties(true))));
            response(op(api, "/api/policies/{policyName}/versions/{revision}", "get"), "200", "Native policy JSON.",
                    content("application/json", new ObjectSchema().additionalProperties(true)));
            Schema<?> change = object("op", new StringSchema()._enum(java.util.List.of("add", "remove", "replace")),
                    "path", new StringSchema(), "value", new Schema<>());
            response(op(api, "/api/policies/{policyName}/diff", "get"), "200", "Revision diff; value is omitted for removals.",
                    content("application/json", object("from", new IntegerSchema(), "to", new IntegerSchema(),
                            "changes", new ArraySchema().items(change))));
            response(op(api, "/api/signing-key", "get"), "200", "Active public signing key.",
                    content("application/json", object("keyId", new StringSchema(), "pem", new StringSchema(),
                            "jwk", new ObjectSchema().additionalProperties(true), "fingerprint", new StringSchema())));
            response(op(api, "/api/signing-key/{keyId}", "get"), "200", "Retained public signing key.",
                    content("application/json", object("keyId", new StringSchema(), "pem", new StringSchema(), "active", new BooleanSchema())));
            // Explanation details are the Phileas result, with Philter's governing-policy metadata.
            response(op(api, "/api/explain", "post"), "200", "Full text filter result with governing policy metadata.",
                    content("application/json", object("filteredText", new StringSchema(), "context", new StringSchema(),
                            "explanation", new ObjectSchema().additionalProperties(true), "policyName", new StringSchema(),
                            "policyVersion", new IntegerSchema(), "policyContentHash", new StringSchema()).additionalProperties(true)));
            op(api, "/api/documents", "get").getResponses().putIfAbsent("404", new ApiResponse().description("Owner unavailable or inaccessible."));
            final Operation filter = op(api, "/api/filter", "post");
            filter.getResponses().get("400").setDescription("Invalid request, malformed or unparseable PDF, password-protected PDF, or PDF without pages.");
            filter.getResponses().get("413").setDescription("Request body, resolved configuration (1 MiB), or encrypted async record exceeds its size limit.");
            filter.setOperationId("filter");
            filter.setSummary("Redact text or a PDF.");
            filter.setDescription("Requires the `redact` scope. Text is synchronous and returns text/plain. "
                    + "For PDFs select application/pdf or application/zip with Accept. PDFs default to async=true, "
                    + "returning application/json with documentId regardless of the selected download format. "
                    + "Set async=false for inline binary output. Async submission captures resolved configuration.");
            filter.getRequestBody().setContent(content("text/plain", new StringSchema())
                    .addMediaType("application/pdf", new MediaType().schema(new BinarySchema())));
            response(filter, "200", "Redacted text or synchronous binary result.", content("text/plain", new StringSchema())
                    .addMediaType("application/pdf", new MediaType().schema(new BinarySchema()))
                    .addMediaType("application/zip", new MediaType().schema(new BinarySchema())));
            response(filter, "202", "PDF accepted; poll status and download the result.",
                    content("application/json", object("documentId", new StringSchema())));
            for (String status : new String[]{"200", "202"}) {
                headers(filter, status, "X-Philter-Policy-Name", "X-Philter-Policy-Version", "X-Philter-Policy-Hash");
            }
            headers(filter, "200", ai.philterd.philter.api.controllers.FilterApiController.DOCUMENT_ID_HEADER);
            headers(filter, "202", "Location", "X-Effective-Configuration-SHA256");
            retry(filter, "429", "Account async queue capacity reached.");
            retry(filter, "503", "Global queue capacity reached, admission contention wait (500 ms) exhausted, or recovery required.");
            retry(op(api, "/api/documents/{documentId}", "delete"), "409", "Notification reconciliation is pending.");
            final Operation download = op(api, "/api/documents/{documentId}", "get");
            response(download, "200", "Completed result bytes.", content("application/pdf", new BinarySchema())
                    .addMediaType("application/zip", new MediaType().schema(new BinarySchema())));
            headers(download, "200", "Content-Disposition");
            for (String path : new String[]{"/api/ledger/{documentId}/export", "/api/contexts/{name}/entries/export"}) {
                headers(op(api, path, "get"), "200", "Content-Disposition");
            }
            // These statuses belong to the shared request boundary, not individual handler return types.
            api.getPaths().forEach((path, item) -> item.readOperations().forEach(operation -> {
                if (operation.getDescription() != null && operation.getDescription().contains("Requires the `")) {
                    operation.getResponses().putIfAbsent("403", new ApiResponse().description("Insufficient scope, disabled API, or access denied."));
                }
                operation.getResponses().putIfAbsent("406", new ApiResponse().description("No representation matches Accept."));
                // Servlet request attributes are server-assigned, never client-supplied parameters.
                if (operation.getParameters() != null) operation.getParameters().removeIf(p -> "requestId".equals(p.getName()));
            }));
        };
    }

    private static Operation op(OpenAPI api, String path, String verb) {
        return api.getPaths().get(path).readOperationsMap()
                .get(io.swagger.v3.oas.models.PathItem.HttpMethod.valueOf(verb.toUpperCase(java.util.Locale.ROOT)));
    }
    private static Schema<?> model(OpenAPI api, Class<?> type) {
        ModelConverters.getInstance().readAll(type).forEach(api.getComponents()::addSchemas);
        return new Schema<>().$ref("#/components/schemas/" + type.getSimpleName());
    }
    private static void json(OpenAPI api, String path, String verb, Class<?> type) {
        response(op(api, path, verb), "200", "OK", content("application/json", model(api, type)));
    }
    private static Content content(String media, Schema<?> schema) {
        return new Content().addMediaType(media, new MediaType().schema(schema));
    }
    private static ObjectSchema object(Object... fields) {
        final ObjectSchema schema = new ObjectSchema();
        for (int i = 0; i < fields.length; i += 2) schema.addProperty((String) fields[i], (Schema<?>) fields[i + 1]);
        return schema;
    }
    private static void response(Operation operation, String status, String description, Content content) {
        operation.getResponses().addApiResponse(status, new ApiResponse().description(description).content(content));
    }
    private static void headers(Operation operation, String status, String... names) {
        for (String name : names) operation.getResponses().get(status).addHeaderObject(name, new Header().schema(new StringSchema()));
    }
    private static void retry(Operation operation, String status, String description) {
        operation.getResponses().addApiResponse(status, new ApiResponse().description(description)
                .content(content("text/plain", new StringSchema()))
                .addHeaderObject("Retry-After", new Header().schema(new IntegerSchema().example(5))));
    }
}

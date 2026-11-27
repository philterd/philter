package ai.philterd.philter.config;

import ai.philterd.philter.api.controllers.AbstractApiController;
import ai.philterd.philter.api.controllers.AuditApiController;
import ai.philterd.philter.api.responses.*;
import ai.philterd.philter.api.requests.RedactListRequest;
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
    private static final String SIGNATURE = ai.philterd.philter.services.signing.SigningService.SIGNATURE_HEADER;
    private static final String SIGNED = "An ES256 JWT binding the SHA-256 hash of the response body. Present when output signing is enabled or the request asked for it with sign=true; verify it with GET /api/signing-key.";
    private static final String REVIEW = "The reasons the request is flagged for human review, separated by \", \": sampled "
            + "when the policy's config.review.samplingRate selected it, and leftovers when the policy's leftover check found "
            + "something. Absent when neither applies. A queued document is selected when it is accepted, so the 202 reports "
            + "sampled; leftovers is on its status and download responses. A response without it has not been checked by "
            + "anyone: it still needs validating.";
    private static final String LEFTOVERS = ai.philterd.philter.services.filtering.ResultHeaders.LEFTOVERS;
    private static final String LEFTOVERS_DESCRIPTION = "Present when the policy's leftover check was called for: the number of "
            + "values still detectable in the redacted output, or not-checked when nothing was checked: the output has no "
            + "readable text, such as a PDF whose redacted pages were flattened to images, or the check failed. The "
            + "redacted output is returned either way. 0 does not mean the output is free of sensitive information. Never "
            + "contains a finding's text.";
    private static final String EMAIL_REPORT = ai.philterd.philter.services.filtering.ResultHeaders.EMAIL_REPORT;
    private static final String EMAIL_REPORT_DESCRIPTION = "Email messages only: counts of attachments and inline images "
            + "kept without being inspected, attachments removed (every attachment under attachments: remove, and those "
            + "attachments: redact could not redact), inline images removed, and dropped signatures, as "
            + "uninspected-attachments=N, uninspected-inline-images=N, removed-attachments=N, removed-inline-images=N, "
            + "dropped-signatures=N, followed by unchecked-attachments=N when the leftover check ran, counting redacted "
            + "attachments whose output could not be read or whose own check failed. No filenames.";

    @Bean
    public OpenApiCustomizer apiWireContracts() {
        return api -> {
            response(op(api, "/api/policies/{policyName}", "get"), "200", "Native policy JSON, preserving field names.",
                    content("application/json", new ObjectSchema().additionalProperties(true)));
            for (String[] write : new String[][]{{"/api/policies", "post"}, {"/api/policies/{policyName}", "put"}}) {
                op(api, write[0], write[1]).getRequestBody().setContent(content("application/json",
                        new ObjectSchema().additionalProperties(true).description("Native Phileas policy. identifiers is required; custom dictionaries use dictionaries.")));
            }
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
            response(op(api, "/api/ledger", "get"), "200", "Ledger chain heads; with all_users, each also names its owner.",
                    content("application/json", model(api, GetLedgerResponse.class)));
            json(api, "/api/ledger/{documentId}", "get", LedgerChainResponse.class);
            json(api, "/api/ledger/{documentId}/valid", "get", LedgerChainResponse.class);
            json(api, "/api/ledger/{documentId}/export", "get", LedgerExport.class);
            json(api, "/api/redact-lists", "get", RedactListsResponse.class);
            for (String verb : new String[]{"post", "put"}) {
                op(api, "/api/redact-lists", verb).getRequestBody()
                        .setContent(content("application/json", model(api, RedactListsRequest.class)));
            }
            op(api, "/api/redact-lists/{list}", "put").getRequestBody()
                    .setContent(content("application/json", model(api, RedactListRequest.class)));
            response(op(api, "/api/lists", "get"), "200", "A page of lists, each with its name, description, and size (with all_users, its owner too), and the total.",
                    content("application/json", model(api, GetCustomListsResponse.class)));
            json(api, "/api/reidentify", "post", ReidentifyResponse.class);
            json(api, "/api/reidentify/text", "post", ReidentifyTextResponse.class);
            response(op(api, "/api/policies/compile", "post"), "200", "Compiled native policy; does not save it.",
                    content("application/json", object("name", new StringSchema(), "description", new StringSchema(),
                            "policy", new ObjectSchema().additionalProperties(true))));
            response(op(api, "/api/policies/{policyName}/versions/{revision}", "get"), "200", "Native policy JSON.",
                    content("application/json", new ObjectSchema().additionalProperties(true)));
            response(op(api, "/api/policies/templates/{templateName}", "get"), "200", "The template's native policy JSON.",
                    content("application/json", new ObjectSchema().additionalProperties(true)));
            Schema<?> change = object("op", new StringSchema()._enum(java.util.List.of("add", "remove", "replace")),
                    "path", new StringSchema(), "value", new Schema<>(),
                    "oldValue", new Schema<>().description("The value before the change. Present on replace and remove."));
            response(op(api, "/api/policies/{policyName}/diff", "get"), "200", "Revision diff; value is omitted for removals.",
                    content("application/json", object("from", new IntegerSchema(), "to", new IntegerSchema(),
                            "changes", new ArraySchema().items(change))));
            // A JSON Web Key (RFC 7517) for the P-256 key Philter signs with.
            final Schema<?> jwk = object("kid", new StringSchema(), "kty", new StringSchema().example("EC"),
                    "crv", new StringSchema().example("P-256"), "x", new StringSchema(), "y", new StringSchema());
            response(op(api, "/api/signing-key", "get"), "200", "Active public signing key.",
                    content("application/json", object("keyId", new StringSchema(), "pem", new StringSchema(),
                            "jwk", jwk, "fingerprint", new StringSchema())));
            response(op(api, "/api/signing-key/regenerate", "post"), "200", "The key that is now active.",
                    content("application/json", object("keyId", new StringSchema())));
            response(op(api, "/api/signing-key/{keyId}", "get"), "200", "Retained public signing key.",
                    content("application/json", object("keyId", new StringSchema(), "pem", new StringSchema(), "active", new BooleanSchema())));
            // The Phileas result, with Philter's governing-policy metadata. A span's classification is left out when it
            // has none; the page and coordinate fields are zero for text.
            final Schema<?> span = object("characterStart", new IntegerSchema(), "characterEnd", new IntegerSchema(),
                    "filterType", new StringSchema().example("SSN"), "context", new StringSchema(),
                    "classification", new StringSchema(), "confidence", new NumberSchema(), "text", new StringSchema(),
                    "replacement", new StringSchema(), "salt", new StringSchema(), "ignored", new BooleanSchema(),
                    "applied", new BooleanSchema(), "priority", new IntegerSchema(), "lineNumber", new IntegerSchema(),
                    "pageNumber", new IntegerSchema(), "paragraphNumber", new IntegerSchema(),
                    "lowerLeftX", new NumberSchema(), "lowerLeftY", new NumberSchema(),
                    "upperRightX", new NumberSchema(), "upperRightY", new NumberSchema());
            api.getComponents().addSchemas("ExplanationSpan", span);
            final Schema<?> spanRef = new Schema<>().$ref("#/components/schemas/ExplanationSpan");
            response(op(api, "/api/explain", "post"), "200", "Full text filter result with governing policy metadata.",
                    content("application/json", object("filteredText", new StringSchema(), "piece", new IntegerSchema(),
                            "context", new StringSchema(),
                            "explanation", object("appliedSpans", new ArraySchema().items(spanRef),
                                    "identifiedSpans", new ArraySchema().items(spanRef)),
                            "tokens", new IntegerSchema(), "policyName", new StringSchema(),
                            "policyVersion", new IntegerSchema(), "policyContentHash", new StringSchema(),
                            "reviewSampled", new io.swagger.v3.oas.models.media.BooleanSchema(),
                            "leftoverCheck", object("performed", new BooleanSchema(), "reason", new StringSchema()
                                            .description("Why the check did not run: unreadableOutput (the output has no "
                                                    + "readable text) or checkFailed (the check failed with an error; "
                                                    + "the redacted output is returned unaffected)."),
                                    "findingCount", new IntegerSchema(),
                                    "findings", new ArraySchema().items(object("filterType", new StringSchema(),
                                            "classification", new StringSchema(), "characterStart", new IntegerSchema(),
                                            "characterEnd", new IntegerSchema(), "confidence", new NumberSchema())))
                                    .description("Present when the policy's leftover check ran. Each finding's position "
                                            + "is in filteredText. No finding carries its text."))));
            final Operation explain = op(api, "/api/explain", "post");
            headers(explain, "200", ai.philterd.philter.api.controllers.FilterApiController.DOCUMENT_ID_HEADER);
            header(explain, "200", ai.philterd.philter.api.controllers.FilterApiController.REVIEW_HEADER, REVIEW);
            header(explain, "200", LEFTOVERS, LEFTOVERS_DESCRIPTION);
            header(explain, "200", SIGNATURE, SIGNED);
            // CSV text, not a JSON object.
            op(api, "/api/audit/export", "get").getResponses().get("200").setContent(content("text/csv", new StringSchema()));
            op(api, "/api/documents", "get").getResponses().putIfAbsent("404", new ApiResponse().description("Owner unavailable or inaccessible."));
            final Operation filter = op(api, "/api/filter", "post");
            filter.getResponses().get("400").setDescription("Invalid request, malformed or unparseable PDF, password-protected PDF, PDF without pages, unreadable Word or Excel document, unreadable RTF document, unreadable or encrypted email message, sign=true on a PDF, Word, Excel, RTF, or email request, or an invalid page_policy: malformed, overlapping another, beyond the PDF's last page, or on a request that is not a PDF.");
            filter.getResponses().get("413").setDescription("Request body, resolved configuration (1 MiB), encrypted async record, a Word or Excel document's decompressed size or entry count, an RTF document's size or group nesting depth, or an email message's size, attachment size, attachment count, or nesting depth exceeds its limit.");
            filter.setOperationId("filter");
            filter.setSummary("Redact text, a PDF, a Word document, an Excel workbook, an RTF document, or an email message.");
            filter.getResponses().computeIfAbsent("415", status -> new ApiResponse())
                    .setDescription("The body contradicts its Content-Type, for example a ZIP that is not a Word document sent as a Word document, a legacy Word or Excel file sent as an Outlook message, a body that does not start with {\\rtf sent as RTF, an RTF document sent as text/plain, a body that is not a MIME message sent as message/rfc822, or an email message with base64, quoted-printable, or multipart content sent as text/plain.");
            filter.getResponses().computeIfAbsent("406", status -> new ApiResponse())
                    .setDescription("No representation matches Accept. An email message, .eml or .msg, is returned only as message/rfc822, and an RTF document only as application/rtf.");
            filter.setDescription("Requires the `redact` scope. Text is synchronous and returns text/plain. "
                    + "For PDFs select application/pdf or application/zip with Accept. A Word document or Excel "
                    + "workbook is returned in its own format. An RTF document, application/rtf or text/rtf, is returned "
                    + "as application/rtf. An email message, message/rfc822 (.eml) or "
                    + "application/vnd.ms-outlook (.msg), is returned as message/rfc822, so select message/rfc822 or */* "
                    + "with Accept. PDF, Word, Excel, RTF, and email default to async=true, "
                    + "returning application/json with documentId regardless of the selected download format. "
                    + "Set async=false for inline binary output. Async submission captures resolved configuration. "
                    + ai.philterd.philter.api.controllers.FilterApiController.SIGN_PARAMETER
                    + " It applies to the text path, which is the one that is signed; sign=true on a PDF, Word,"
                    + " Excel, RTF, or email request is refused with 400 rather than answered unsigned.");
            filter.getRequestBody().setContent(content("text/plain", new StringSchema())
                    .addMediaType("application/pdf", new MediaType().schema(new BinarySchema()))
                    .addMediaType(ai.philterd.philter.api.controllers.FilterApiController.DOCX, new MediaType().schema(new BinarySchema()))
                    .addMediaType(ai.philterd.philter.api.controllers.FilterApiController.XLSX, new MediaType().schema(new BinarySchema()))
                    .addMediaType(ai.philterd.philter.api.controllers.FilterApiController.RTF, new MediaType().schema(new BinarySchema()))
                    .addMediaType(ai.philterd.philter.api.controllers.FilterApiController.TEXT_RTF, new MediaType().schema(new BinarySchema()))
                    .addMediaType(ai.philterd.philter.api.controllers.FilterApiController.EML, new MediaType().schema(new BinarySchema()))
                    .addMediaType(ai.philterd.philter.api.controllers.FilterApiController.MSG, new MediaType().schema(new BinarySchema())));
            response(filter, "200", "Redacted text or synchronous binary result.", content("text/plain", new StringSchema())
                    .addMediaType("application/pdf", new MediaType().schema(new BinarySchema()))
                    .addMediaType("application/zip", new MediaType().schema(new BinarySchema()))
                    .addMediaType(ai.philterd.philter.api.controllers.FilterApiController.DOCX, new MediaType().schema(new BinarySchema()))
                    .addMediaType(ai.philterd.philter.api.controllers.FilterApiController.XLSX, new MediaType().schema(new BinarySchema()))
                    .addMediaType(ai.philterd.philter.api.controllers.FilterApiController.RTF, new MediaType().schema(new BinarySchema()))
                    .addMediaType(ai.philterd.philter.api.controllers.FilterApiController.EML, new MediaType().schema(new BinarySchema())));
            response(filter, "202", "Document accepted; poll status and download the result.",
                    content("application/json", object("documentId", new StringSchema())));
            for (String status : new String[]{"200", "202"}) {
                headers(filter, status, "X-Philter-Policy-Name", "X-Philter-Policy-Version", "X-Philter-Policy-Hash");
            }
            headers(filter, "200", ai.philterd.philter.api.controllers.FilterApiController.DOCUMENT_ID_HEADER);
            filter.addParametersItem(new io.swagger.v3.oas.models.parameters.QueryParameter()
                    .name(ai.philterd.philter.services.filtering.PagePolicyRequest.PARAMETER)
                    .description(ai.philterd.philter.api.controllers.FilterApiController.PAGE_POLICY_PARAMETER)
                    .schema(new ArraySchema().items(new StringSchema()))
                    .style(io.swagger.v3.oas.models.parameters.Parameter.StyleEnum.FORM).explode(true));
            for (String status : new String[]{"200", "202"}) {
                header(filter, status, ai.philterd.philter.api.controllers.FilterApiController.PAGE_POLICIES_HEADER,
                        "PDF only, when page_policy is given: the policy and version that redacted each page set, as "
                                + "pages=policy@version separated by \"; \".");
            }
            header(filter, "200", SIGNATURE, SIGNED + " Only text responses are signed.");
            for (String status : new String[]{"200", "202"}) {
                header(filter, status, ai.philterd.philter.api.controllers.FilterApiController.REVIEW_HEADER, REVIEW);
            }
            // A queued document's check runs after the 202, so these are on its status and download responses.
            header(filter, "200", LEFTOVERS, LEFTOVERS_DESCRIPTION);
            header(filter, "200", EMAIL_REPORT, EMAIL_REPORT_DESCRIPTION);
            headers(filter, "202", "Location", "X-Effective-Configuration-SHA256");
            retry(filter, "429", "Account async queue capacity reached.");
            retry(filter, "503", "Global queue capacity reached, admission contention wait (500 ms) exhausted, or recovery required.");
            retry(op(api, "/api/documents/{documentId}", "delete"), "409", "The document is processing, or notification reconciliation is pending.");
            final Operation download = op(api, "/api/documents/{documentId}", "get");
            response(download, "200", "Completed result bytes.", content("application/pdf", new BinarySchema())
                    .addMediaType("application/zip", new MediaType().schema(new BinarySchema()))
                    .addMediaType(ai.philterd.philter.api.controllers.FilterApiController.DOCX, new MediaType().schema(new BinarySchema()))
                    .addMediaType(ai.philterd.philter.api.controllers.FilterApiController.XLSX, new MediaType().schema(new BinarySchema()))
                    .addMediaType(ai.philterd.philter.api.controllers.FilterApiController.RTF, new MediaType().schema(new BinarySchema()))
                    .addMediaType(ai.philterd.philter.api.controllers.FilterApiController.EML, new MediaType().schema(new BinarySchema())));
            headers(download, "200", "Content-Disposition");
            for (final Operation completed : new Operation[]{op(api, "/api/documents/{documentId}/status", "get"),
                    op(api, "/api/documents/{documentId}", "get")}) {
                header(completed, "200", LEFTOVERS, LEFTOVERS_DESCRIPTION + " Only once the document is COMPLETE.");
                header(completed, "200", EMAIL_REPORT, EMAIL_REPORT_DESCRIPTION);
                header(completed, "200", ai.philterd.philter.api.controllers.FilterApiController.REVIEW_HEADER,
                        "leftovers when the document's leftover check found something.");
            }
            for (String path : new String[]{"/api/ledger/{documentId}/export", "/api/contexts/{name}/entries/export"}) {
                headers(op(api, path, "get"), "200", "Content-Disposition");
            }
            final Operation auditExport = op(api, "/api/audit/export", "get");
            headers(auditExport, "200", "Content-Disposition");
            header(auditExport, "200", AuditApiController.EXPORT_ROWS_HEADER, "How many events the export holds.");
            header(auditExport, "200", AuditApiController.EXPORT_TRUNCATED_HEADER, "Whether the row cap cut the export short.");
            header(auditExport, "200", AuditApiController.EXPORT_TIME_ZONE_HEADER, "The time zone the from and to dates were read in.");
            header(auditExport, "200", AuditApiController.EXPORT_NEXT_CURSOR_HEADER, "The cursor of the next page. Present only when the export was truncated.");
            header(auditExport, "200", AuditApiController.EXPORT_NEXT_OFFSET_HEADER, "The offset of the next page. Present only when an export read by offset was truncated.");
            // The revision a later If-Match names.
            for (String[] tagged : new String[][]{{"/api/policies", "post", "201"}, {"/api/policies/{policyName}", "get", "200"},
                    {"/api/policies/{policyName}", "put", "200"}, {"/api/policies/{policyName}/details", "get", "200"},
                    {"/api/policies/{policyName}/details", "put", "200"}, {"/api/policies/{policyName}/copy", "post", "201"},
                    {"/api/policies/{policyName}/rollback", "post", "201"}, {"/api/redact-lists/{list}", "get", "200"},
                    {"/api/redact-lists/{list}", "put", "200"}}) {
                headers(op(api, tagged[0], tagged[1]), tagged[2], "ETag");
            }
            for (String[] throttled : new String[][]{{"/api/sign-in", "post"}, {"/api/sign-in/mfa", "post"},
                    {"/api/users/me/password", "put"}, {"/api/users/me/mfa", "post"}}) {
                retryAfter(op(api, throttled[0], throttled[1]), "429", "Seconds to wait before retrying.");
            }
            for (String[] guarded : new String[][]{{"/api/users/{username}/role", "put"}, {"/api/users/{username}/deactivate", "post"}}) {
                retryAfter(op(api, guarded[0], guarded[1]), "409",
                        "Seconds to wait before retrying. Present only with reason admin_change_in_progress.");
            }
            api.getComponents().addHeaders(AbstractApiController.REQUEST_ID_HEADER, new Header().schema(new StringSchema())
                    .description("The id Philter gives the request. The audit events the request causes carry it as request_id."));
            final Header requestId = new Header().$ref("#/components/headers/" + AbstractApiController.REQUEST_ID_HEADER);
            // These statuses belong to the shared request boundary, not individual handler return types.
            api.getPaths().forEach((path, item) -> item.readOperations().forEach(operation -> {
                if (operation.getDescription() != null && operation.getDescription().contains("Requires the `")) {
                    operation.getResponses().putIfAbsent("403", new ApiResponse().description("Insufficient scope, disabled API, or access denied."));
                }
                operation.getResponses().putIfAbsent("406", new ApiResponse().description("No representation matches Accept."));
                // Set on every /api response, refusals included, before anything can answer the request.
                operation.getResponses().values().forEach(response -> response.addHeaderObject(AbstractApiController.REQUEST_ID_HEADER, requestId));
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
    private static void header(Operation operation, String status, String name, String description) {
        operation.getResponses().get(status).addHeaderObject(name, new Header().description(description).schema(new StringSchema()));
    }
    /** Declares Retry-After on a response whose description and body are already documented. */
    private static void retryAfter(Operation operation, String status, String description) {
        operation.getResponses().get(status).addHeaderObject("Retry-After", new Header()
                .description(description).schema(new IntegerSchema()));
    }
    private static void retry(Operation operation, String status, String description) {
        operation.getResponses().addApiResponse(status, new ApiResponse().description(description)
                .content(content("text/plain", new StringSchema()))
                .addHeaderObject("Retry-After", new Header().schema(new IntegerSchema().example(5))));
    }
}

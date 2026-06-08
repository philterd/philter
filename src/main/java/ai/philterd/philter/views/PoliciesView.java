/*
 *     Copyright 2026 Philterd, LLC @ https://www.philterd.ai
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
package ai.philterd.philter.views;

import ai.philterd.phileas.policy.PolicySchema;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.PolicyEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.providers.PolicyEntityDataProvider;
import ai.philterd.philter.config.AdminAccessConfig;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.model.Source;
import ai.philterd.philter.services.RequestIdGenerator;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.services.policies.DefaultPolicy;
import ai.philterd.philter.services.policies.PolicyValidation;
import ai.philterd.philter.views.widgets.CommonWidgets;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mongodb.client.MongoClient;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.TabSheet;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.bson.types.ObjectId;

@Route(value = "policies")
@PageTitle("Philter - Redaction Policies")
@PermitAll
public class PoliciesView extends AbstractRestrictedView {

    private static final Logger LOGGER = LogManager.getLogger(PoliciesView.class);

    /** Reusable pretty-printer for policy JSON; Gson is thread-safe, so a single shared instance suffices. */
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

    // URL of the hosted policy editor, shown as a link next to the policy JSON for building policies.
    // The version query parameter is the redaction policy schema version that this build of Phileas
    // supports, so the editor can target the correct schema.
    private static final String POLICY_EDITOR_URL =
            "https://policies.philterd.ai/?version=" + PolicySchema.getSupportedSchemaVersion();


    public PoliciesView(final MongoClient mongoClient, final EncryptionService encryptionService, final AuditEventPublisher auditEventPublisher, final PolicyDataService policyService) {
        super(mongoClient, encryptionService, auditEventPublisher);

        final UserEntity userEntity = getCurrentUser();
        final PolicyEntityDataProvider policiesDataProvider = new PolicyEntityDataProvider(userEntity.getId(), policyService);

        final Button newPolicyButton = new Button("New Policy", VaadinIcon.PLUS.create());
        newPolicyButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_PRIMARY);

        final Grid<PolicyEntity> policyGrid = new Grid<>(PolicyEntity.class, false);
        policyGrid.setDataProvider(policiesDataProvider);
        policyGrid.setSizeFull();
        policyGrid.addColumn(PolicyEntity::getName).setHeader("Name");
        policyGrid.addComponentColumn(policy -> {

            final Button editPolicyButton = new Button("Edit", VaadinIcon.EDIT.create());
            editPolicyButton.addThemeVariants(ButtonVariant.LUMO_SMALL);
            editPolicyButton.addClickListener(event -> {

                // Pretty-print the stored policy JSON for editing.
                final TextArea policyJsonTextArea = new TextArea();
                policyJsonTextArea.setWidthFull();
                policyJsonTextArea.setHeight("400px");
                policyJsonTextArea.setLabel("Policy (JSON)");
                policyJsonTextArea.setValue(prettyPrintJson(policy.getPolicy()));
                policyJsonTextArea.setHelperComponent(CommonWidgets.getLink("Build a policy in the policy editor, then paste the JSON here.", POLICY_EDITOR_URL, true));

                final TextField policyDescriptionTextField = new TextField();
                policyDescriptionTextField.setWidthFull();
                policyDescriptionTextField.setLabel("Policy Description");
                policyDescriptionTextField.setPlaceholder("optional description about the policy");
                policyDescriptionTextField.setMaxLength(200);
                policyDescriptionTextField.setValue(policy.getDescription() != null ? policy.getDescription() : "");

                final TextArea policyNotesTextArea = new TextArea();
                policyNotesTextArea.setWidthFull();
                policyNotesTextArea.setHeight("150px");
                policyNotesTextArea.setLabel("Policy Notes");
                policyNotesTextArea.setPlaceholder("optional notes about the policy");
                policyNotesTextArea.setMaxLength(1000);
                policyNotesTextArea.setValue(policy.getNotes() != null ? policy.getNotes() : "");

                final VerticalLayout policyDetailsVerticalLayout = new VerticalLayout();
                policyDetailsVerticalLayout.add(policyDescriptionTextField);
                policyDetailsVerticalLayout.add(policyNotesTextArea);

                final VerticalLayout policyJsonVerticalLayout = new VerticalLayout();
                policyJsonVerticalLayout.setSizeFull();
                policyJsonVerticalLayout.add(policyJsonTextArea);

                final TabSheet tabSheet = new TabSheet();
                tabSheet.add("Policy Details", policyDetailsVerticalLayout);
                tabSheet.add("Policy (JSON)", policyJsonVerticalLayout);

                final TextField policyNameTextField = new TextField();
                policyNameTextField.setWidthFull();
                policyNameTextField.setLabel("Policy Name");
                policyNameTextField.setPlaceholder("policy name");
                policyNameTextField.setRequired(true);
                policyNameTextField.setRequiredIndicatorVisible(true);
                policyNameTextField.setMaxLength(PolicyDataService.POLICY_NAME_MAX_LENGTH);
                policyNameTextField.setPattern(PolicyDataService.POLICY_NAME_REGEX);
                policyNameTextField.setHelperText("The policy name must only contain letters, numbers, dashes, and underscores.");
                policyNameTextField.setValue(policy.getName());
                policyNameTextField.setReadOnly(true);

                final Dialog editPolicyDialog = new Dialog();
                editPolicyDialog.setMinWidth("900px");
                editPolicyDialog.setMaxWidth("900px");
                editPolicyDialog.setMinHeight("700px");
                editPolicyDialog.setMaxHeight("700px");
                editPolicyDialog.add(new H3("Edit Policy"));
                editPolicyDialog.add(policyNameTextField);
                editPolicyDialog.add(tabSheet);

                final Button saveButton = new Button("Save", e -> {

                    final String policyJson = policyJsonTextArea.getValue();
                    final String policyDescription = policyDescriptionTextField.getValue();
                    final String policyNotes = policyNotesTextArea.getValue();

                    final boolean validJson = isValidJson(policyJson);
                    final PolicyValidation validation = validJson ? policyService.validatePolicy(policyJson) : null;

                    if(!validJson) {

                        policyJsonTextArea.setInvalid(true);
                        policyJsonTextArea.setErrorMessage("The policy is not valid JSON.");

                    } else if(!validation.isValid()) {

                        // Structurally-valid JSON that is not a valid redaction policy (for example, no
                        // identifiers): show the validation reason on the policy JSON field.
                        policyJsonTextArea.setInvalid(true);
                        policyJsonTextArea.setErrorMessage(validation.getMessage());

                    } else {

                        policyJsonTextArea.setInvalid(false);

                        final String requestId = RequestIdGenerator.generate();

                        final ServiceResponse serviceResponse = policyService.update(requestId, userEntity.getId(), policy.getId(), policyJson, policyDescription, policyNotes, getClientIpAddress());

                        if(serviceResponse.isSuccessful()) {

                            policiesDataProvider.refreshAll();
                            editPolicyDialog.close();
                            showSuccessNotification("Policy updated.");

                        } else {

                            // Surface the server-side validation message.
                            policyJsonTextArea.setInvalid(true);
                            policyJsonTextArea.setErrorMessage(serviceResponse.getMessage());

                        }

                    }

                });
                saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

                final Button cancelButton = new Button("Cancel", e -> editPolicyDialog.close());
                cancelButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

                editPolicyDialog.getFooter().add(cancelButton, saveButton);
                editPolicyDialog.open();

            });

            return editPolicyButton;

        }).setHeader("Edit").setAutoWidth(true).setFlexGrow(0);

        policyGrid.addComponentColumn(policy -> {

            final Button duplicatePolicyButton = new Button("Duplicate", VaadinIcon.COPY.create());
            duplicatePolicyButton.addThemeVariants(ButtonVariant.LUMO_SMALL);
            duplicatePolicyButton.addClickListener(event -> {

                final TextField policyNameTextField = new TextField();
                policyNameTextField.setWidthFull();
                policyNameTextField.setLabel("New Policy Name");
                policyNameTextField.setPlaceholder("new policy name");
                policyNameTextField.setRequired(true);
                policyNameTextField.setRequiredIndicatorVisible(true);
                policyNameTextField.setMaxLength(PolicyDataService.POLICY_NAME_MAX_LENGTH);
                policyNameTextField.setPattern(PolicyDataService.POLICY_NAME_REGEX);
                policyNameTextField.setHelperText("The policy name must only contain letters, numbers, dashes, and underscores.");

                final Dialog duplicatePolicyDialog = new Dialog();
                duplicatePolicyDialog.setMinWidth("400px");
                duplicatePolicyDialog.add(new H3("Duplicate Policy"));
                duplicatePolicyDialog.add(new Paragraph("Enter a name for the new duplicated policy."));
                duplicatePolicyDialog.add(policyNameTextField);

                final Button duplicateButton = new Button("Duplicate", e -> {

                    final String newName = policyNameTextField.getValue();
                    final String requestId = RequestIdGenerator.generate();

                    final ServiceResponse serviceResponse = policyService.duplicatePolicy(requestId, userEntity.getId(), policy.getName(), newName, Source.WEBUI.getSource());

                    if(serviceResponse.isSuccessful()) {
                        policiesDataProvider.refreshAll();
                        duplicatePolicyDialog.close();
                        showSuccessNotification(serviceResponse.getMessage());
                    } else {
                        policyNameTextField.setInvalid(true);
                        policyNameTextField.setErrorMessage(serviceResponse.getMessage());
                    }

                });
                duplicateButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

                final Button cancelButton = new Button("Cancel", e -> duplicatePolicyDialog.close());
                cancelButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

                duplicatePolicyDialog.getFooter().add(cancelButton, duplicateButton);
                duplicatePolicyDialog.open();

            });

            return duplicatePolicyButton;

        }).setHeader("Duplicate").setAutoWidth(true).setFlexGrow(0);

        policyGrid.addComponentColumn(policy -> {

            final Button deletePolicyButton = new Button("Delete", VaadinIcon.TRASH.create());
            deletePolicyButton.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_SMALL);
            deletePolicyButton.addClickListener(event -> {

                final ServiceResponse serviceResponse = policyService.deleteByName("", policy.getName(), userEntity.getId(), Source.WEBUI);

                if(serviceResponse.isSuccessful()) {
                    policiesDataProvider.refreshAll();
                    showSuccessNotification(serviceResponse.getMessage());
                } else {
                    showSuccessNotification(serviceResponse.getMessage());
                }

            });

            return deletePolicyButton;

        }).setHeader("Delete").setAutoWidth(true).setFlexGrow(0);

        newPolicyButton.addClickListener(event -> {

            // Start a new policy from the default template.
            final TextArea policyJsonTextArea = new TextArea();
            policyJsonTextArea.setWidthFull();
            policyJsonTextArea.setHeight("400px");
            policyJsonTextArea.setLabel("Policy (JSON)");
            policyJsonTextArea.setValue(DefaultPolicy.json());
            policyJsonTextArea.setHelperComponent(CommonWidgets.getLink("Build a policy in the policy editor, then paste the JSON here.", POLICY_EDITOR_URL, true));

            final TextField policyDescriptionTextField = new TextField();
            policyDescriptionTextField.setWidthFull();
            policyDescriptionTextField.setLabel("Policy Description");
            policyDescriptionTextField.setPlaceholder("optional description about the policy");
            policyDescriptionTextField.setMaxLength(200);

            final TextArea policyNotesTextArea = new TextArea();
            policyNotesTextArea.setWidthFull();
            policyNotesTextArea.setHeight("150px");
            policyNotesTextArea.setLabel("Policy Notes");
            policyNotesTextArea.setPlaceholder("optional notes about the policy");
            policyNotesTextArea.setMaxLength(1000);

            final VerticalLayout policyDetailsVerticalLayout = new VerticalLayout();
            policyDetailsVerticalLayout.add(policyDescriptionTextField);
            policyDetailsVerticalLayout.add(policyNotesTextArea);

            final VerticalLayout policyJsonVerticalLayout = new VerticalLayout();
            policyJsonVerticalLayout.setSizeFull();
            policyJsonVerticalLayout.add(policyJsonTextArea);

            final TabSheet tabSheet = new TabSheet();
            tabSheet.add("Policy Details", policyDetailsVerticalLayout);
            tabSheet.add("Policy (JSON)", policyJsonVerticalLayout);

            final TextField policyNameTextField = new TextField();
            policyNameTextField.setWidthFull();
            policyNameTextField.setLabel("Policy Name");
            policyNameTextField.setPlaceholder("policy name");
            policyNameTextField.setRequired(true);
            policyNameTextField.setRequiredIndicatorVisible(true);
            policyNameTextField.setMaxLength(PolicyDataService.POLICY_NAME_MAX_LENGTH);
            policyNameTextField.setPattern(PolicyDataService.POLICY_NAME_REGEX);
            policyNameTextField.setHelperText("The policy name must only contain letters, numbers, dashes, and underscores.");

            final Dialog createPolicyDialog = new Dialog();
            createPolicyDialog.setMinWidth("900px");
            createPolicyDialog.setMaxWidth("900px");
            createPolicyDialog.setMinHeight("700px");
            createPolicyDialog.setMaxHeight("700px");
            createPolicyDialog.add(new H3("Create Policy"));
            createPolicyDialog.add(policyNameTextField);
            createPolicyDialog.add(tabSheet);

            final Button saveButton = new Button("Save", e -> {

                final String policyName = policyNameTextField.getValue();
                final String policyJson = policyJsonTextArea.getValue();
                final String policyDescription = policyDescriptionTextField.getValue();
                final String policyNotes = policyNotesTextArea.getValue();

                final boolean validJson = isValidJson(policyJson);
                final PolicyValidation validation = validJson ? policyService.validatePolicy(policyJson) : null;

                if(!validJson) {

                    policyJsonTextArea.setInvalid(true);
                    policyJsonTextArea.setErrorMessage("The policy is not valid JSON.");

                } else if(!validation.isValid()) {

                    // Structurally-valid JSON that is not a valid redaction policy (for example, no
                    // identifiers): show the validation reason on the policy JSON field, not the name.
                    policyJsonTextArea.setInvalid(true);
                    policyJsonTextArea.setErrorMessage(validation.getMessage());

                } else {

                    policyJsonTextArea.setInvalid(false);

                    final String requestId = RequestIdGenerator.generate();

                    final ServiceResponse serviceResponse = policyService.create(requestId, userEntity.getId(), policyJson, policyDescription, policyNotes, policyName, getClientIpAddress());

                    if(serviceResponse.isSuccessful()) {

                        policiesDataProvider.refreshAll();
                        createPolicyDialog.close();
                        showSuccessNotification("Policy created.");

                    } else {

                        // Surface the server-side validation message (policy name or policy JSON).
                        policyNameTextField.setInvalid(true);
                        policyNameTextField.setErrorMessage(serviceResponse.getMessage());

                    }

                }

            });
            saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

            final Button cancelButton = new Button("Cancel", e -> createPolicyDialog.close());
            cancelButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

            createPolicyDialog.getFooter().add(cancelButton, saveButton);
            createPolicyDialog.open();

        });

        final VerticalLayout policiesVerticalLayout = new VerticalLayout();
        policiesVerticalLayout.add(newPolicyButton);
        policiesVerticalLayout.add(policyGrid);
        policiesVerticalLayout.setSizeFull();

        // Begin Managed Policies

        final Grid<PolicyEntity> managedPoliciesGrid = new Grid<>(PolicyEntity.class, false);
        managedPoliciesGrid.addColumn(PolicyEntity::getName).setHeader("Name").setResizable(true);
        managedPoliciesGrid.addColumn(PolicyEntity::getDescription).setHeader("Description").setResizable(true);
        managedPoliciesGrid.setPageSize(ALL_POLICIES_PAGE_SIZE);
        // Lazy paging: fetch one page at a time plus the total count for the scrollbar.
        managedPoliciesGrid.setItems(
                query -> policyService.findManagedPolicies(query.getOffset(), query.getLimit()).stream(),
                query -> policyService.countManagedPolicies());
        managedPoliciesGrid.setWidthFull();

        managedPoliciesGrid.addComponentColumn(managedPolicyEntity -> {
            final Button viewPolicyButton = new Button("View Policy", VaadinIcon.OPEN_BOOK.create());
            viewPolicyButton.setTooltipText("View Policy");
            viewPolicyButton.addClickListener(event ->
                    openPolicyJsonDialog(managedPolicyEntity.getName(), managedPolicyEntity.getPolicy()));
            return viewPolicyButton;
        }).setHeader("View Policy").setAutoWidth(true).setFlexGrow(0);

        managedPoliciesGrid.addComponentColumn(managedPolicyEntity -> {
            final Button createPolicyButton = new Button("Create Policy From", VaadinIcon.PLUS.create());
            createPolicyButton.setTooltipText("Create a new policy from " + managedPolicyEntity.getName());
            createPolicyButton.addClickListener(event -> {

                final Dialog createFromManagedDialog = new Dialog();
                createFromManagedDialog.add(new H3("Create Policy"));
                createFromManagedDialog.add(new Paragraph("Provide a name for the new policy."));
                createFromManagedDialog.setMinWidth("500px");
                createFromManagedDialog.setMaxWidth("500px");
                createFromManagedDialog.setMinHeight("300px");
                createFromManagedDialog.setMaxHeight("300px");

                final TextField newPolicyNameTextField = new TextField();
                newPolicyNameTextField.setLabel("Policy Name:");
                newPolicyNameTextField.setWidthFull();
                newPolicyNameTextField.setRequired(true);
                newPolicyNameTextField.setRequiredIndicatorVisible(true);
                newPolicyNameTextField.setMaxLength(PolicyDataService.POLICY_NAME_MAX_LENGTH);
                newPolicyNameTextField.setPattern(PolicyDataService.POLICY_NAME_REGEX);
                newPolicyNameTextField.setHelperText("The policy name must only contain letters, numbers, dashes, and underscores.");
                createFromManagedDialog.add(newPolicyNameTextField);

                final Button createButton = new Button("Create Policy", e -> {

                    final String requestId = RequestIdGenerator.generate();

                    final String newPolicyName = newPolicyNameTextField.getValue();
                    final String policyJson = managedPolicyEntity.getPolicy();
                    final String policyDescription = managedPolicyEntity.getDescription();

                    final ServiceResponse serviceResponse = policyService.create(requestId, userEntity.getId(), policyJson, policyDescription, "Created from managed policy " + managedPolicyEntity.getName(), newPolicyName, getClientIpAddress());

                    if (serviceResponse.isSuccessful()) {

                        policiesDataProvider.refreshAll();
                        showSuccessNotification("Policy created.");
                        createFromManagedDialog.close();

                    } else {

                        newPolicyNameTextField.setInvalid(true);
                        newPolicyNameTextField.setErrorMessage(serviceResponse.getMessage());

                    }

                });
                createButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

                final Button cancelButton = new Button("Cancel", e -> createFromManagedDialog.close());
                cancelButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

                createFromManagedDialog.getFooter().add(cancelButton, createButton);
                createFromManagedDialog.open();

            });
            return createPolicyButton;
        }).setHeader("Create Policy").setAutoWidth(true).setFlexGrow(0);

        final VerticalLayout managedPoliciesVerticalLayout = new VerticalLayout();
        managedPoliciesVerticalLayout.add(new Span("Managed policies are policies commonly used created for convenience. You can use them as you would any other policy but they cannot be edited or deleted."));
        managedPoliciesVerticalLayout.add(managedPoliciesGrid);
        managedPoliciesVerticalLayout.setSizeFull();

        // Create the tab sheet and page. (The always/never redact lists now live on their own page.)

        final TabSheet tabSheet = new TabSheet();
        tabSheet.add("My Policies", policiesVerticalLayout);
        tabSheet.add("Managed Policies", managedPoliciesVerticalLayout);

        // Admins get an additional read-only view of every user's policies.
        if (isAdmin() && AdminAccessConfig.isCrossUserAccessEnabled()) {
            tabSheet.add("All Policies", buildAllPoliciesLayout(policyService));
        }

        tabSheet.setSizeFull();

        final VerticalLayout pageVerticalLayout = new VerticalLayout();
        pageVerticalLayout.add(getTitle(("Redaction Policies")));
        pageVerticalLayout.add(tabSheet);
        pageVerticalLayout.add(CommonWidgets.getFooter());
        pageVerticalLayout.setSizeFull();

        final HorizontalLayout pageHorizontalLayout = new HorizontalLayout();
        pageHorizontalLayout.add(pageVerticalLayout);
        pageHorizontalLayout.setSizeFull();

        setContent(pageHorizontalLayout);

    }

    /** The page size for the admin "All Policies" lazy listing. */
    private static final int ALL_POLICIES_PAGE_SIZE = 25;

    /**
     * Builds the admin-only "All Policies" tab: every user's policies with the owner's email. The grid
     * is bound to a lazy {@link com.vaadin.flow.data.provider.DataProvider} via fetch/count callbacks,
     * so it loads {@value #ALL_POLICIES_PAGE_SIZE} rows at a time as the user scrolls rather than
     * loading every policy at once.
     */
    private VerticalLayout buildAllPoliciesLayout(final PolicyDataService policyService) {

        final Grid<AllPolicyRow> grid = new Grid<>();
        grid.setPageSize(ALL_POLICIES_PAGE_SIZE);
        grid.addColumn(AllPolicyRow::name).setHeader("Policy").setResizable(true);
        grid.addColumn(AllPolicyRow::owner).setHeader("Owner").setResizable(true);

        // A View Policy button identical to the one on the "Managed Policies" tab.
        grid.addComponentColumn(row -> {
            final Button viewPolicyButton = new Button("View Policy", VaadinIcon.OPEN_BOOK.create());
            viewPolicyButton.setTooltipText("View Policy");
            viewPolicyButton.addClickListener(event -> openPolicyJsonDialog(row.name(), row.policyJson()));
            return viewPolicyButton;
        }).setHeader("View Policy").setAutoWidth(true).setFlexGrow(0);

        grid.setSizeFull();

        // Lazy paging: the grid requests one page (offset/limit) at a time and the total count for the
        // scrollbar, reusing the owner-agnostic service methods. Owner emails for the page are resolved
        // in a single batched lookup rather than one query per row.
        grid.setItems(
                query -> {
                    final List<PolicyEntity> page = policyService.findAllAcrossUsers(query.getOffset(), query.getLimit());
                    final Map<ObjectId, String> ownerEmails = userService.findEmailsByIds(
                            page.stream().map(PolicyEntity::getUserId).filter(Objects::nonNull).collect(Collectors.toSet()));
                    return page.stream().map(policy -> toAllPolicyRow(policy, ownerEmails));
                },
                query -> policyService.countAllAcrossUsers());

        final VerticalLayout layout = new VerticalLayout();
        layout.setSizeFull();
        layout.add(new Span("All policies across all users."), grid);
        return layout;

    }

    /** Maps a policy to an "All Policies" row, resolving the owner's email from the prefetched map. */
    private AllPolicyRow toAllPolicyRow(final PolicyEntity policy, final Map<ObjectId, String> ownerEmails) {
        final String email = policy.getUserId() != null ? ownerEmails.getOrDefault(policy.getUserId(), "(none)") : "(none)";
        return new AllPolicyRow(policy.getName(), email, policy.getPolicy());
    }

    /** A row in the admin "All Policies" table: the policy name, the owner's email, and the policy JSON. */
    private record AllPolicyRow(String name, String owner, String policyJson) {}

    /**
     * Opens a read-only dialog showing a policy's JSON, pretty-printed. Shared by the "Managed Policies"
     * and admin "All Policies" tabs so both show the identical dialog.
     */
    private void openPolicyJsonDialog(final String name, final String policyJson) {

        final JsonElement jsonElement = JsonParser.parseString(policyJson);
        final String prettyJson = PRETTY_GSON.toJson(jsonElement);

        final TextArea policyTextArea = new TextArea();
        policyTextArea.setLabel(name);
        policyTextArea.setWidthFull();
        policyTextArea.setReadOnly(true);
        policyTextArea.setValue(prettyJson);

        final VerticalLayout viewPolicyVerticalLayout = new VerticalLayout();
        viewPolicyVerticalLayout.add(policyTextArea);

        final Dialog viewPolicyDialog = new Dialog();
        viewPolicyDialog.add(viewPolicyVerticalLayout);
        viewPolicyDialog.setMinWidth("600px");
        viewPolicyDialog.setMaxWidth("600px");
        viewPolicyDialog.setMinHeight("800px");
        viewPolicyDialog.setMaxHeight("800px");
        viewPolicyDialog.setCloseOnEsc(true);
        viewPolicyDialog.setCloseOnOutsideClick(true);

        final Button cancelButton = new Button("Close", e -> viewPolicyDialog.close());
        cancelButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        viewPolicyDialog.getFooter().add(cancelButton);
        viewPolicyDialog.open();

    }

    /**
     * Pretty-prints policy JSON for display in the editor. If the value cannot be parsed
     * it is returned unchanged so the user can still see and correct it.
     */
    private static String prettyPrintJson(final String json) {
        try {
            final JsonElement jsonElement = JsonParser.parseString(json);
            return PRETTY_GSON.toJson(jsonElement);
        } catch (final Exception ex) {
            return json != null ? json : "";
        }
    }

    /**
     * Returns true if the given string is non-blank, syntactically valid JSON. The server
     * performs the deeper policy validation; this is a fast client-side guard.
     */
    private static boolean isValidJson(final String json) {
        if(json == null || json.isBlank()) {
            return false;
        }
        try {
            JsonParser.parseString(json);
            return true;
        } catch (final Exception ex) {
            return false;
        }
    }

}

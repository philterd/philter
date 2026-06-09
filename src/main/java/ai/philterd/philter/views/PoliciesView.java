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
import ai.philterd.philter.data.entities.PolicyVersionEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.providers.PolicyEntityDataProvider;
import ai.philterd.philter.config.AdminAccessConfig;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.PolicyVersionDataService;
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
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mongodb.client.MongoClient;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
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
import org.bson.types.ObjectId;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

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


    public PoliciesView(final MongoClient mongoClient, final EncryptionService encryptionService,
                        final AuditEventPublisher auditEventPublisher, final PolicyDataService policyService,
                        final PolicyVersionDataService policyVersionDataService) {
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

        policyGrid.addComponentColumn(policy -> {

            final Button historyButton = new Button("History", VaadinIcon.CLOCK.create());
            historyButton.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_CONTRAST);
            historyButton.addClickListener(event ->
                    openVersionHistoryDialog(policy, policyService, policyVersionDataService,
                            userEntity, policiesDataProvider));
            return historyButton;

        }).setHeader("History").setAutoWidth(true).setFlexGrow(0);

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

    // -------------------------------------------------------------------------
    // Version History
    // -------------------------------------------------------------------------

    /**
     * Opens the Version History dialog for the given policy. Shows a grid of retained revisions
     * with per-row View and Rollback actions, and a footer button to open the diff dialog.
     */
    private void openVersionHistoryDialog(final PolicyEntity policy,
                                           final PolicyDataService policyService,
                                           final PolicyVersionDataService policyVersionDataService,
                                           final UserEntity userEntity,
                                           final PolicyEntityDataProvider policiesDataProvider) {

        final List<PolicyVersionEntity> versions =
                policyVersionDataService.findAllByName(policy.getName(), userEntity.getId(), 0, 50);

        final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        final int latestRevision = versions.isEmpty() ? -1 : versions.get(0).getRevision();

        final Grid<PolicyVersionEntity> versionGrid = new Grid<>(PolicyVersionEntity.class, false);
        versionGrid.setSizeFull();
        versionGrid.addColumn(PolicyVersionEntity::getRevision).setHeader("Revision").setWidth("100px").setFlexGrow(0);
        versionGrid.addColumn(v -> v.getCapturedTimestamp() != null
                        ? dateFormat.format(v.getCapturedTimestamp()) : "")
                .setHeader("Captured").setAutoWidth(true);
        versionGrid.addColumn(v -> v.getContentHash() != null
                        ? v.getContentHash().substring(0, Math.min(8, v.getContentHash().length())) + "…" : "")
                .setHeader("Hash").setAutoWidth(true);
        versionGrid.addComponentColumn(v -> {
            final Button viewBtn = new Button("View", VaadinIcon.EYE.create());
            viewBtn.addThemeVariants(ButtonVariant.LUMO_SMALL);
            viewBtn.addClickListener(e ->
                    openPolicyJsonDialog("Revision " + v.getRevision() + " — " + policy.getName(), v.getPolicy()));
            return viewBtn;
        }).setHeader("View").setAutoWidth(true).setFlexGrow(0);

        // Create the dialog before adding the Rollback column so the lambda can capture it.
        final Dialog historyDialog = new Dialog();

        versionGrid.addComponentColumn(v -> {
            final Button rollbackBtn = new Button("Rollback", VaadinIcon.BACKWARDS.create());
            rollbackBtn.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR);
            // The current head revision cannot be rolled back to itself.
            rollbackBtn.setEnabled(v.getRevision() != latestRevision);
            rollbackBtn.addClickListener(e ->
                    openRollbackConfirmDialog(policy, v.getRevision(), policyService,
                            userEntity, policiesDataProvider, historyDialog));
            return rollbackBtn;
        }).setHeader("Rollback").setAutoWidth(true).setFlexGrow(0);

        versionGrid.setItems(versions);

        historyDialog.setMinWidth("900px");
        historyDialog.setMaxWidth("900px");
        historyDialog.setMinHeight("700px");
        historyDialog.setMaxHeight("700px");
        historyDialog.add(new H3("Version History — " + policy.getName()));

        if (versions.isEmpty()) {
            historyDialog.add(new Paragraph("No retained versions found for this policy."));
        } else {
            historyDialog.add(versionGrid);
        }

        final Button compareButton = new Button("Compare Revisions", VaadinIcon.SPLIT.create());
        compareButton.setEnabled(versions.size() >= 2);
        compareButton.addClickListener(e ->
                openDiffDialog(policy.getName(), versions, userEntity.getId(), policyVersionDataService));

        final Button closeButton = new Button("Close", e -> historyDialog.close());
        closeButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        historyDialog.getFooter().add(compareButton, closeButton);
        historyDialog.open();
    }

    /**
     * Opens a confirmation dialog before rolling back a policy, then performs the rollback and
     * refreshes the grid on success.
     */
    private void openRollbackConfirmDialog(final PolicyEntity policy, final int targetRevision,
                                            final PolicyDataService policyService,
                                            final UserEntity userEntity,
                                            final PolicyEntityDataProvider policiesDataProvider,
                                            final Dialog parentDialog) {

        final Dialog confirmDialog = new Dialog();
        confirmDialog.setMinWidth("450px");
        confirmDialog.add(new H3("Confirm Rollback"));
        confirmDialog.add(new Paragraph("Roll back \"" + policy.getName() + "\" to revision "
                + targetRevision + "? A new revision will be created with the prior content. "
                + "This action is audited and cannot be undone."));

        final Button confirmButton = new Button("Roll Back", e -> {
            final ServiceResponse response = policyService.rollback(
                    RequestIdGenerator.generate(), policy.getName(), userEntity.getId(), targetRevision);
            confirmDialog.close();
            if (response.isSuccessful()) {
                policiesDataProvider.refreshAll();
                parentDialog.close();
                showSuccessNotification("\"" + policy.getName() + "\" rolled back to revision " + targetRevision + ".");
            } else {
                showFailureNotification(response.getMessage());
            }
        });
        confirmButton.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_PRIMARY);

        final Button cancelButton = new Button("Cancel", e -> confirmDialog.close());
        cancelButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        confirmDialog.getFooter().add(cancelButton, confirmButton);
        confirmDialog.open();
    }

    /**
     * Opens the diff dialog for a policy. Pre-selects the two most recent revisions and immediately
     * computes the diff so the user sees results without an extra click.
     */
    private void openDiffDialog(final String policyName, final List<PolicyVersionEntity> versions,
                                 final ObjectId userId,
                                 final PolicyVersionDataService policyVersionDataService) {

        final List<Integer> revisionNumbers = versions.stream()
                .map(PolicyVersionEntity::getRevision)
                .sorted(Comparator.reverseOrder())
                .toList();

        final ComboBox<Integer> fromCombo = new ComboBox<>("From Revision");
        fromCombo.setItems(revisionNumbers);
        fromCombo.setWidth("160px");
        if (revisionNumbers.size() >= 2) fromCombo.setValue(revisionNumbers.get(1));

        final ComboBox<Integer> toCombo = new ComboBox<>("To Revision");
        toCombo.setItems(revisionNumbers);
        toCombo.setWidth("160px");
        if (!revisionNumbers.isEmpty()) toCombo.setValue(revisionNumbers.get(0));

        final VerticalLayout diffResultLayout = new VerticalLayout();
        diffResultLayout.setPadding(false);
        diffResultLayout.setSpacing(false);
        diffResultLayout.setSizeFull();

        final Button diffButton = new Button("Diff", VaadinIcon.EXCHANGE.create());
        diffButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        diffButton.addClickListener(e -> {
            final Integer fromRev = fromCombo.getValue();
            final Integer toRev = toCombo.getValue();
            if (fromRev == null || toRev == null) {
                showWarningNotification("Select both revisions to compare.");
                return;
            }
            final PolicyVersionEntity fromVersion =
                    policyVersionDataService.findByNameAndRevision(policyName, userId, fromRev);
            final PolicyVersionEntity toVersion =
                    policyVersionDataService.findByNameAndRevision(policyName, userId, toRev);
            if (fromVersion == null || toVersion == null) {
                showFailureNotification("One or both revisions could not be found.");
                return;
            }
            renderDiff(diffResultLayout, fromVersion, toVersion);
        });

        final HorizontalLayout selectionBar = new HorizontalLayout(fromCombo, toCombo, diffButton);
        selectionBar.setAlignItems(FlexComponent.Alignment.END);

        final Dialog diffDialog = new Dialog();
        diffDialog.setMinWidth("950px");
        diffDialog.setMaxWidth("950px");
        diffDialog.setMinHeight("650px");
        diffDialog.setMaxHeight("650px");
        diffDialog.add(new H3("Compare Revisions — " + policyName));
        diffDialog.add(selectionBar);
        diffDialog.add(diffResultLayout);

        final Button closeButton = new Button("Close", e -> diffDialog.close());
        closeButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        diffDialog.getFooter().add(closeButton);

        diffDialog.open();

        // Auto-run the diff on the default selection immediately.
        diffButton.click();
    }

    /**
     * Computes the diff between two retained versions and populates {@code container} with either
     * a change-table grid or a "no differences" message.
     */
    private static void renderDiff(final VerticalLayout container,
                                    final PolicyVersionEntity fromVersion,
                                    final PolicyVersionEntity toVersion) {
        container.removeAll();

        final JsonObject fromJson = new Gson().fromJson(fromVersion.getPolicy(), JsonObject.class);
        final JsonObject toJson = new Gson().fromJson(toVersion.getPolicy(), JsonObject.class);

        final List<DiffRow> rows = new ArrayList<>();
        collectDiffRows(fromJson, toJson, "", rows);

        if (rows.isEmpty()) {
            container.add(new Span("Revisions " + fromVersion.getRevision() + " and "
                    + toVersion.getRevision() + " have identical content."));
            return;
        }

        final Grid<DiffRow> diffGrid = new Grid<>();
        diffGrid.addColumn(DiffRow::operation).setHeader("Operation").setWidth("110px").setFlexGrow(0);
        diffGrid.addColumn(DiffRow::path).setHeader("Path").setFlexGrow(2);
        diffGrid.addColumn(DiffRow::before).setHeader("Before").setFlexGrow(1);
        diffGrid.addColumn(DiffRow::after).setHeader("After").setFlexGrow(1);
        diffGrid.setItems(rows);
        diffGrid.setAllRowsVisible(true);
        diffGrid.setSizeFull();
        container.add(diffGrid);
    }

    /**
     * Recursively walks two JSON objects and appends a {@link DiffRow} for every field that was
     * added, removed, or changed. Arrays and primitives that differ are reported as a single
     * replace at their path — consistent with the RFC 6902 patch the API returns.
     */
    private static void collectDiffRows(final JsonElement from, final JsonElement to,
                                         final String path, final List<DiffRow> rows) {
        if (from.equals(to)) return;

        if (from.isJsonObject() && to.isJsonObject()) {
            final JsonObject fromObj = from.getAsJsonObject();
            final JsonObject toObj = to.getAsJsonObject();
            for (final String key : fromObj.keySet()) {
                final String childPath = path + "/" + key;
                if (!toObj.has(key)) {
                    rows.add(new DiffRow("remove", childPath, jsonValueToString(fromObj.get(key)), ""));
                } else {
                    collectDiffRows(fromObj.get(key), toObj.get(key), childPath, rows);
                }
            }
            for (final String key : toObj.keySet()) {
                if (!fromObj.has(key)) {
                    rows.add(new DiffRow("add", path + "/" + key, "", jsonValueToString(toObj.get(key))));
                }
            }
        } else {
            rows.add(new DiffRow("replace", path.isEmpty() ? "/" : path,
                    jsonValueToString(from), jsonValueToString(to)));
        }
    }

    private static String jsonValueToString(final JsonElement el) {
        if (el == null || el.isJsonNull()) return "";
        if (el.isJsonPrimitive()) return el.getAsString();
        return el.toString();
    }

    /** A single row in the diff table shown inside the Compare Revisions dialog. */
    private record DiffRow(String operation, String path, String before, String after) {}

}

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

import ai.philterd.philter.data.entities.GlobalTermsEntity;
import ai.philterd.philter.data.entities.PolicyEntity;
import ai.philterd.philter.data.providers.PoliciesDataProvider;
import ai.philterd.philter.data.services.GlobalTermsDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.services.RequestIdGenerator;
import ai.philterd.philter.services.policies.SimplifiedPolicy;
import ai.philterd.philter.views.components.policyeditor.PolicyEditorComponents;
import ai.philterd.philter.views.widgets.CommonWidgets;
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
import com.vaadin.flow.data.provider.ListDataProvider;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.Gson;
import java.util.stream.Collectors;

@Route(value = "policies")
@PageTitle("Philter - Policies")
@AnonymousAllowed
public class PoliciesView extends AbstractView {

    private static final Logger LOGGER = LogManager.getLogger(PoliciesView.class);

    @Override
    public String getHelpMarkdownText() {
        return "Placeholder for policies help text.";
    }

    public PoliciesView(final PolicyDataService policyService,
                        final GlobalTermsDataService globalTermsService, final PoliciesDataProvider policiesDataProvider) {
        super(true);

        final Button newPolicyButton = new Button("New Policy", VaadinIcon.PLUS.create());
        newPolicyButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL);

        final Grid<PolicyEntity> policyGrid = new Grid<>(PolicyEntity.class, false);
        policyGrid.setDataProvider(policiesDataProvider);
        policyGrid.setSizeFull();
        policyGrid.addColumn(PolicyEntity::getName).setHeader("Name");
        policyGrid.addComponentColumn(policy -> {

            final Button editPolicyButton = new Button("Edit", VaadinIcon.EDIT.create());
            editPolicyButton.addThemeVariants(ButtonVariant.LUMO_SMALL);
            editPolicyButton.addClickListener(event -> {

                final PolicyEditorComponents policyEditorComponents = new PolicyEditorComponents();

                // Get the policy JSON and deserialize it into a SimplifiedPolicy.
                final Gson gson = new Gson();
                final SimplifiedPolicy simplifiedPolicy = gson.fromJson(policy.getPolicy(), SimplifiedPolicy.class);

                final VerticalLayout filtersVerticalLayout = policyEditorComponents.getFilters(simplifiedPolicy);
                final VerticalLayout termsToAlwaysRedactVerticalLayout = policyEditorComponents.getTermsToAlwaysRedact(simplifiedPolicy);
                final VerticalLayout ignoredTermsVerticalLayout = policyEditorComponents.getIgnored(simplifiedPolicy);
                final VerticalLayout optionsVerticalLayout = policyEditorComponents.getOptions(simplifiedPolicy);

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

                final TabSheet tabSheet = new TabSheet();
                tabSheet.add("Policy Details", policyDetailsVerticalLayout);
                tabSheet.add("PII/PHI Filters", filtersVerticalLayout);
                tabSheet.add("Always Redact", termsToAlwaysRedactVerticalLayout);
                tabSheet.add("Never Redact", ignoredTermsVerticalLayout);
                tabSheet.add("Options", optionsVerticalLayout);

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

                    final String policyName = policyNameTextField.getValue();
                    final String policyJson = policyEditorComponents.buildPolicy(policyName);
                    final String policyDescription = policyDescriptionTextField.getValue();
                    final String policyNotes = policyNotesTextArea.getValue();

                    if(policyJson == null) {

                        // The policy builder did not validate.
                        LOGGER.warn("The policy could not be validated.");

                    } else {

                        final String requestId = RequestIdGenerator.generate();

                        final ServiceResponse serviceResponse = policyService.update(requestId, null, policy.getId(), policyJson, policyDescription, policyNotes, getClientIpAddress());

                        if(serviceResponse.isSuccessful()) {

                            policiesDataProvider.refreshAll();
                            editPolicyDialog.close();
                            showSuccessNotification("Policy updated.");

                        } else {

                            policyNameTextField.setInvalid(true);
                            policyNameTextField.setErrorMessage(serviceResponse.getMessage());

                        }

                    }

                });
                saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

                final Button cancelButton = new Button("Cancel", e -> editPolicyDialog.close());
                cancelButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

                editPolicyDialog.getFooter().add(cancelButton, saveButton);
                editPolicyDialog.open();

            });

            final Button deletePolicyButton = new Button("Delete", VaadinIcon.TRASH.create());
            deletePolicyButton.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_SMALL);
            deletePolicyButton.addClickListener(event -> {
                try {
                    // TODO: Wire up policy deletion.
                   // policyDataService.deleteByName(policy.getName());
                    policiesDataProvider.refreshAll();
                    showSuccessNotification("Policy deleted.");
                } catch (Exception ex) {
                    LOGGER.error("Unable to delete policy.", ex);
                    showFailureNotification("Unable to delete the policy.");
                }
            });

            final HorizontalLayout actions = new HorizontalLayout(editPolicyButton, deletePolicyButton);
            return actions;

        });

        newPolicyButton.addClickListener(event -> {

            final PolicyEditorComponents policyEditorComponents = new PolicyEditorComponents();

            // We are creating a new policy, so this is just a new instance of a SimplifiedPolicy.
            final SimplifiedPolicy simplifiedPolicy = new SimplifiedPolicy();

            final VerticalLayout filtersVerticalLayout = policyEditorComponents.getFilters(simplifiedPolicy);
            final VerticalLayout termsToAlwaysRedactVerticalLayout = policyEditorComponents.getTermsToAlwaysRedact(simplifiedPolicy);
            final VerticalLayout ignoredTermsVerticalLayout = policyEditorComponents.getIgnored(simplifiedPolicy);
            final VerticalLayout optionsVerticalLayout = policyEditorComponents.getOptions(simplifiedPolicy);

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

            final TabSheet tabSheet = new TabSheet();
            tabSheet.add("Policy Details", policyDetailsVerticalLayout);
            tabSheet.add("PII/PHI Filters", filtersVerticalLayout);
            tabSheet.add("Always Redact", termsToAlwaysRedactVerticalLayout);
            tabSheet.add("Never Redact", ignoredTermsVerticalLayout);
            tabSheet.add("Options", optionsVerticalLayout);

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
                final String policyJson = policyEditorComponents.buildPolicy(policyName);
                final String policyDescription = policyDescriptionTextField.getValue();
                final String policyNotes = policyNotesTextArea.getValue();

                if(policyJson == null) {

                    // The policy builder did not validate.
                    LOGGER.warn("The policy could not be validated.");

                } else {

                    final String requestId = RequestIdGenerator.generate();

                    final ServiceResponse serviceResponse = policyService.create(requestId, null, policyJson, policyDescription, policyNotes, policyName, getClientIpAddress());

                    if(serviceResponse.isSuccessful()) {

                        policiesDataProvider.refreshAll();
                        createPolicyDialog.close();
                        showSuccessNotification("Policy created.");

                    } else {

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
        policiesVerticalLayout.add(new Span("Policies control what types of PII to redact and how to redact each type."));
        policiesVerticalLayout.add(newPolicyButton);
        policiesVerticalLayout.add(policyGrid);
        policiesVerticalLayout.add(CommonWidgets.getLink("Learn more about policies.", "https://docs.philterd.ai/policies.html", true));
        policiesVerticalLayout.setSizeFull();

        // Begin Managed Policies

        final Grid<PolicyEntity> managedPoliciesGrid = new Grid<>(PolicyEntity.class, false);
        managedPoliciesGrid.addColumn(PolicyEntity::getName).setHeader("Name").setResizable(true).setSortable(true);
        managedPoliciesGrid.addColumn(PolicyEntity::getDescription).setHeader("Description").setResizable(true).setSortable(true);
        managedPoliciesGrid.setDataProvider(new ListDataProvider<>(policyService.findManagedPolicies()));
        managedPoliciesGrid.setWidthFull();

        managedPoliciesGrid.addComponentColumn(managedPolicyEntity -> {
            final Button viewPolicyButton = new Button(VaadinIcon.OPEN_BOOK.create());
            viewPolicyButton.setTooltipText("View Policy");
            viewPolicyButton.addClickListener(event -> {

                final TextArea policyTextArea = new TextArea();
                policyTextArea.setLabel(managedPolicyEntity.getName());
                policyTextArea.setWidthFull();
                policyTextArea.setReadOnly(true);
                policyTextArea.setValue(managedPolicyEntity.getPolicy());

                final VerticalLayout viewPolicyVerticalLayout = new VerticalLayout();
                viewPolicyVerticalLayout.add(policyTextArea);

                final Dialog viewManagedPolicyDialog = new Dialog();
                viewManagedPolicyDialog.add(viewPolicyVerticalLayout);
                viewManagedPolicyDialog.setMinWidth("600px");
                viewManagedPolicyDialog.setMaxWidth("600px");
                viewManagedPolicyDialog.setMinHeight("800px");
                viewManagedPolicyDialog.setMaxHeight("800px");
                viewManagedPolicyDialog.setCloseOnEsc(true);
                viewManagedPolicyDialog.setCloseOnOutsideClick(true);

                final Button cancelButton = new Button("Close", e -> viewManagedPolicyDialog.close());
                cancelButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

                viewManagedPolicyDialog.getFooter().add(cancelButton);
                viewManagedPolicyDialog.open();

            });
            return viewPolicyButton;
        }).setHeader("View Policy").setAutoWidth(true).setFlexGrow(0);

        managedPoliciesGrid.addComponentColumn(managedPolicyEntity -> {
            final Button createPolicyButton = new Button(VaadinIcon.PLUS.create());
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

                    final ServiceResponse serviceResponse = policyService.create(requestId, null, policyJson, policyDescription, "Created from managed policy " + managedPolicyEntity.getName(), newPolicyName, getClientIpAddress());

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

        // Begin Global Terms

        final GlobalTermsEntity globalTermsEntity = globalTermsService.find(null);

        final TextArea termsToAlwaysRedactTextArea = new TextArea();
        termsToAlwaysRedactTextArea.setSizeFull();
        if(globalTermsEntity != null) {
            termsToAlwaysRedactTextArea.setValue(String.join("\n", globalTermsEntity.getTermsToAlwaysRedact()));
        }

        final TextArea termsToNeverRedactTextArea = new TextArea();
        termsToNeverRedactTextArea.setSizeFull();
        if(globalTermsEntity != null) {
            termsToNeverRedactTextArea.setValue(String.join("\n", globalTermsEntity.getTermsToNeverRedact()));
        }

        final Button saveGlobalTermsButton = new Button("Save Terms");
        saveGlobalTermsButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        saveGlobalTermsButton.addClickListener(event -> {
            globalTermsService.saveOrUpdate(null, termsToAlwaysRedactTextArea.getValue().lines().collect(Collectors.toList()), termsToNeverRedactTextArea.getValue().lines().collect(Collectors.toList()));
            showSuccessNotification("Terms saved.");
        });

        final VerticalLayout termsToAlwaysRedactVerticalLayout = new VerticalLayout();
        termsToAlwaysRedactVerticalLayout.add(new H3("Terms to Always Redact"));
        termsToAlwaysRedactVerticalLayout.add(new Span("These terms, one per line, will always be redacted regardless of the selected policy."));
        termsToAlwaysRedactVerticalLayout.add(termsToAlwaysRedactTextArea);
        termsToAlwaysRedactVerticalLayout.add(new H3("Terms to Never Redact"));
        termsToAlwaysRedactVerticalLayout.add(new Span("These terms, one per line, will never be redacted regardless of the selected policy."));
        termsToAlwaysRedactVerticalLayout.add(termsToNeverRedactTextArea);
        termsToAlwaysRedactVerticalLayout.add(CommonWidgets.getLink("Learn about the options available for fuzzy-matching and other options.", "https://docs.philterd.ai/redaction/global_terms.html", true));
        termsToAlwaysRedactVerticalLayout.add(saveGlobalTermsButton);
        termsToAlwaysRedactVerticalLayout.setSizeFull();

        // Create the tab sheet and page.

        final TabSheet tabSheet = new TabSheet();
        tabSheet.add("Policies", policiesVerticalLayout);
        tabSheet.add("Managed Policies", managedPoliciesVerticalLayout);
        tabSheet.add("Global Terms", termsToAlwaysRedactVerticalLayout);
        tabSheet.setSizeFull();

        final VerticalLayout pageVerticalLayout = new VerticalLayout();
        pageVerticalLayout.add(getTitle(("Policies")));
        pageVerticalLayout.add(tabSheet);
        pageVerticalLayout.setSizeFull();

        final HorizontalLayout pageHorizontalLayout = new HorizontalLayout();
        pageHorizontalLayout.add(pageVerticalLayout);
        pageHorizontalLayout.add(helpWindowVerticalLayout);
        pageHorizontalLayout.setSizeFull();

        setContent(pageHorizontalLayout);

    }

}

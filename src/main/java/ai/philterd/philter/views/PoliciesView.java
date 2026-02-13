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

import ai.philterd.philter.data.PoliciesDataProvider;
import ai.philterd.philter.data.entities.PolicyEntity;
import ai.philterd.philter.services.PolicyDataService;
import com.google.gson.Gson;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Route(value = "policies")
@PageTitle("Philter - Policies")
@AnonymousAllowed
public class PoliciesView extends AbstractView {

    private static final Logger LOGGER = LogManager.getLogger(PoliciesView.class);

    public PoliciesView(final PolicyDataService policyDataService, final PoliciesDataProvider policiesDataProvider, final Gson gson) {

        final VerticalLayout pageVerticalLayout = new VerticalLayout();
        pageVerticalLayout.add(new H1("Policies"));
        pageVerticalLayout.add(new Paragraph("Policies control what types of PII to redact and how to redact each type."));
        pageVerticalLayout.setSizeFull();

        final Button newPolicyButton = new Button("New Policy", VaadinIcon.PLUS.create());
        newPolicyButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL);

        final Grid<PolicyEntity> policyGrid = new Grid<>(PolicyEntity.class, false);
        policyGrid.setDataProvider(policiesDataProvider);
        policyGrid.setSizeFull();
        policyGrid.addColumn(PolicyEntity::getName).setHeader("Name");
        policyGrid.addComponentColumn(policy -> {

            final Button deletePolicyButton = new Button("Delete", VaadinIcon.TRASH.create());
            deletePolicyButton.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_SMALL);
            deletePolicyButton.addClickListener(event -> {
                try {
                    policyDataService.delete(policy.getName());
                    policiesDataProvider.refreshAll();
                    showSuccessNotification("Policy deleted.");
                } catch (Exception ex) {
                    LOGGER.error("Unable to delete policy.", ex);
                    showFailureNotification("Unable to delete the policy.");
                }
            });

            return deletePolicyButton;

        });

        newPolicyButton.addClickListener(event -> {

            final Dialog dialog = new Dialog();
            dialog.setHeaderTitle("New Policy");

            final TextField policyNameTextField = new TextField("Policy Name");
            policyNameTextField.setWidthFull();
            policyNameTextField.setRequiredIndicatorVisible(true);

            final TextArea jsonTextArea = new TextArea("Policy JSON");
            jsonTextArea.setWidthFull();
            jsonTextArea.setHeight("300px");
            jsonTextArea.setWidth("600px");
            jsonTextArea.setRequired(true);

            final Button saveButton = new Button("Save", e -> {

                if(policyNameTextField.isEmpty()) {
                    policyNameTextField.setInvalid(true);
                    policyNameTextField.setErrorMessage("A name is required.");
                    return;
                }

                if(jsonTextArea.isEmpty()) {
                    jsonTextArea.setInvalid(true);
                    jsonTextArea.setErrorMessage("A policy is required.");
                    return;
                }

                try {

                    // TODO: Determine if a policy with this name already exists.

                    final PolicyEntity policyEntity = new PolicyEntity();
                    policyEntity.setName(policyNameTextField.getValue());
                    policyEntity.setPolicy(jsonTextArea.getValue());
                    policyDataService.save(policyEntity);

                    showSuccessNotification("Policy saved.");
                    policiesDataProvider.refreshAll();

                    dialog.close();

                } catch (Exception ex) {
                    showFailureNotification("Unable to save the policy.");
                }

            });
            saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

            final Button cancelButton = new Button("Cancel", e -> dialog.close());

            dialog.add(new VerticalLayout(policyNameTextField, jsonTextArea));
            dialog.getFooter().add(cancelButton, saveButton);
            dialog.open();

        });

        pageVerticalLayout.add(newPolicyButton);
        pageVerticalLayout.add(policyGrid);
        pageVerticalLayout.add(getFooter());
        pageVerticalLayout.setSizeFull();

        setContent(pageVerticalLayout);

    }

}

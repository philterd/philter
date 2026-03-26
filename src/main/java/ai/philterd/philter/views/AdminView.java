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

import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.providers.UserEntityDataProvider;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.views.widgets.CommonWidgets;
import com.mongodb.client.MongoClient;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.TabSheet;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Route(value = "admin")
@PageTitle("Philter - Admin")
@RolesAllowed("ADMIN")
public class AdminView extends AbstractRestrictedView {

    private static final Logger LOGGER = LogManager.getLogger(AdminView.class);

    @Override
    public String getHelpMarkdownText() {
        return "Placeholder for users help text.";
    }

    public AdminView(final MongoClient mongoClient, final EncryptionService encryptionService, final AuditEventPublisher auditEventPublisher,
                     final UserService userService, final ContextDataService contextService, final PolicyDataService policyService) {

        super(mongoClient, encryptionService, auditEventPublisher, true);

        final UserEntityDataProvider userEntityDataProvider = new UserEntityDataProvider(userService);

        final VerticalLayout pageVerticalLayout = new VerticalLayout();
        pageVerticalLayout.add(getTitle(("Admin")));
        pageVerticalLayout.setSizeFull();

        final HorizontalLayout pageHorizontalLayout = new HorizontalLayout();
        pageHorizontalLayout.setSizeFull();

        // Button to create a new API key
        final Button createUserButton = new Button("New User", VaadinIcon.PLUS.create());
        createUserButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        createUserButton.addClickListener(event -> {

            final Dialog createUserDialog = new Dialog();
            createUserDialog.setHeaderTitle("New User");

            final TextField emailTextField = new TextField("Email Address");
            emailTextField.setWidthFull();
            emailTextField.setRequired(true);

            final PasswordField passwordField = new PasswordField("Password");
            passwordField.setWidthFull();
            passwordField.setRequired(true);

            final ComboBox<String> roleComboBox = new ComboBox<>("Role");
            roleComboBox.setItems("admin", "user");
            roleComboBox.setValue("user");
            roleComboBox.setWidthFull();
            roleComboBox.setRequired(true);

            final VerticalLayout dialogVerticalLayout = new VerticalLayout();
            dialogVerticalLayout.add(emailTextField, passwordField, roleComboBox);
            createUserDialog.add(dialogVerticalLayout);

            final Button saveButton = new Button("Create", e -> {

                final String email = emailTextField.getValue();
                final String password = passwordField.getValue();
                final String role = roleComboBox.getValue();

                if (email == null || email.isEmpty()) {
                    emailTextField.setErrorMessage("Email address is required.");

                } else if (password == null || password.isEmpty()) {
                    passwordField.setErrorMessage("Password is required.");

                } else if (role == null || role.isEmpty()) {
                    roleComboBox.setErrorMessage("Role is required.");

                } else {

                    final ServiceResponse serviceResponse = userService.createUser(email, password, role, contextService, policyService);

                    if (serviceResponse.isSuccessful()) {
                        showSuccessNotification(serviceResponse.getMessage());
                        createUserDialog.close();
                        userEntityDataProvider.refreshAll();
                    } else {
                        showFailureNotification(serviceResponse.getMessage());
                    }

                }

            });

            saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

            final Button cancelButton = new Button("Cancel", e -> createUserDialog.close());

            createUserDialog.getFooter().add(cancelButton, saveButton);
            createUserDialog.open();

        });

        final Grid<UserEntity> usersGrid = new Grid<>(UserEntity.class, false);
        usersGrid.addColumn(UserEntity::getEmail).setHeader("Email Address").setResizable(true).setSortable(true);
        usersGrid.addColumn(UserEntity::getRole).setHeader("Role").setResizable(true).setSortable(true);

        usersGrid.addComponentColumn(user -> {
            final Button deleteButton = new Button(VaadinIcon.TRASH.create());
            deleteButton.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);
            deleteButton.setTooltipText("Delete User");
            deleteButton.addClickListener(clickEvent -> {

                final Dialog confirmDialog = new Dialog();
                confirmDialog.add(new H3("Confirm Deletion"));
                confirmDialog.add(new Paragraph("Are you sure you want to delete the user " + user.getEmail() + "?"));
                confirmDialog.add(new Paragraph("This will delete all of the user's data: API keys, contexts, custom lists, policies, and ledger entries."));

                final Button confirmButton = new Button("Delete", e -> {
                    userService.deleteUser(user);
                    userEntityDataProvider.refreshAll();
                    confirmDialog.close();
                    showSuccessNotification("User deleted.");
                });
                confirmButton.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_PRIMARY);

                final Button cancelButton = new Button("Cancel", e -> confirmDialog.close());

                confirmDialog.getFooter().add(cancelButton, confirmButton);
                confirmDialog.open();

            });
            return deleteButton;
        }).setHeader("Delete").setAutoWidth(true).setFlexGrow(0);

        usersGrid.setDataProvider(userEntityDataProvider);
        usersGrid.setWidthFull();

        final VerticalLayout usersVerticalLayout = new VerticalLayout();
        usersVerticalLayout.add(createUserButton);
        usersVerticalLayout.add(usersGrid);
        usersVerticalLayout.setSizeFull();

        final TabSheet tabSheet = new TabSheet();
        tabSheet.add("Users", usersVerticalLayout);
        tabSheet.setSizeFull();

        pageVerticalLayout.add(tabSheet);
        pageVerticalLayout.add(CommonWidgets.getFooter());

        pageHorizontalLayout.add(pageVerticalLayout);
        pageHorizontalLayout.add(helpWindowVerticalLayout);

        setContent(pageHorizontalLayout);

    }

}

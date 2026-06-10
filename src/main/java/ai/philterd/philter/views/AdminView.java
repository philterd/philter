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
import ai.philterd.philter.audit.AuditLogService;
import ai.philterd.philter.data.entities.AdminSettingsEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.services.AdminSettingsDataService;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.SigningKeyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.model.Source;
import ai.philterd.philter.services.RequestIdGenerator;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.views.widgets.CommonWidgets;
import com.mongodb.client.MongoClient;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.server.streams.DownloadHandler;
import com.vaadin.flow.server.streams.DownloadResponse;

import java.io.ByteArrayInputStream;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

    /** Minimum password length, matching the rule enforced on the other change-password screens. */
    private static final int MIN_PASSWORD_LENGTH = 16;

    /** Documentation describing the password requirements, linked from every new-password dialog. */
    private static final String PASSWORD_DOCS_URL = "/public/docs/login_security.html#password-requirements";

    /** Helper text shown under password fields, describing the requirements. */
    private static final String PASSWORD_HELPER_TEXT = "At least " + MIN_PASSWORD_LENGTH + " characters. Use a mix of "
            + "upper and lowercase letters, numbers, and symbols, or a passphrase of 5 to 7 unrelated words.";

    // Character classes for the "Generate" password helper. Ambiguous-looking characters (l, 1, O, 0,
    // and similar) are omitted so a generated password is easier to read and transcribe.
    private static final String PW_LOWER = "abcdefghijkmnopqrstuvwxyz";
    private static final String PW_UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String PW_DIGITS = "23456789";
    private static final String PW_SYMBOLS = "!@#$%^&*-_=+?";
    private static final int GENERATED_PASSWORD_LENGTH = 20;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();


    public AdminView(final MongoClient mongoClient, final EncryptionService encryptionService, final AuditEventPublisher auditEventPublisher,
                     final UserService userService, final PolicyDataService policyService,
                     final ContextDataService contextService,
                     final AdminSettingsDataService adminSettingsDataService,
                     final AuditLogService auditLogService,
                     final SigningKeyDataService signingKeyDataService) {

        super(mongoClient, encryptionService, auditEventPublisher);

        AdminSettingsEntity adminSettingsEntity = adminSettingsDataService.findAdminSettings();

        if (adminSettingsEntity == null) {
            adminSettingsEntity = new AdminSettingsEntity();
        }

        final AdminSettingsEntity finalAdminSettingsEntity = adminSettingsEntity;

        final VerticalLayout pageVerticalLayout = new VerticalLayout();
        pageVerticalLayout.add(getTitle(("Admin")));
        pageVerticalLayout.setSizeFull();

        final HorizontalLayout pageHorizontalLayout = new HorizontalLayout();
        pageHorizontalLayout.setSizeFull();

        // Declared before the create-user button so its click handler can refresh the grid.
        final Grid<UserEntity> usersGrid = new Grid<>(UserEntity.class, false);

        // Button to create a new API key
        final Button createUserButton = new Button("New User", VaadinIcon.PLUS.create());
        createUserButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        createUserButton.addClickListener(event -> {

            final Dialog createUserDialog = new Dialog();
            createUserDialog.setHeaderTitle("New User");
            createUserDialog.setWidth("600px");

            final TextField usernameTextField = new TextField("Username");
            usernameTextField.setWidthFull();
            usernameTextField.setRequired(true);

            // Optional email. Intentionally unvalidated: it is meant to be an email address, but an
            // operator may put anything here.
            final TextField emailTextField = new TextField("Email Address (optional)");
            emailTextField.setWidthFull();

            final PasswordField passwordField = new PasswordField("Password");
            passwordField.setWidthFull();
            passwordField.setRequired(true);
            passwordField.setHelperText(PASSWORD_HELPER_TEXT);

            // Generate a random password that meets the requirements and put it in the field. It is
            // revealed so the admin can copy it to give to the new user.
            final Button generatePasswordButton = new Button("Generate", VaadinIcon.MAGIC.create(), ev -> {
                passwordField.setValue(generateRandomPassword());
                passwordField.setRevealButtonVisible(true);
            });
            generatePasswordButton.setTooltipText("Generate a random password that meets the requirements");

            final HorizontalLayout passwordRow = new HorizontalLayout(passwordField, generatePasswordButton);
            passwordRow.setWidthFull();
            passwordRow.setFlexGrow(1, passwordField);
            passwordRow.setAlignItems(HorizontalLayout.Alignment.BASELINE);

            final ComboBox<String> roleComboBox = new ComboBox<>("Role");
            roleComboBox.setItems("admin", "user");
            roleComboBox.setValue("user");
            roleComboBox.setWidthFull();
            roleComboBox.setRequired(true);

            final VerticalLayout dialogVerticalLayout = new VerticalLayout();
            dialogVerticalLayout.add(usernameTextField, emailTextField, passwordRow, roleComboBox,
                    CommonWidgets.getLink("Password requirements", PASSWORD_DOCS_URL, true));
            createUserDialog.add(dialogVerticalLayout);

            final Button saveButton = new Button("Create", e -> {

                final String username = usernameTextField.getValue();
                final String email = emailTextField.getValue();
                final String password = passwordField.getValue();
                final String role = roleComboBox.getValue();

                if (username == null || username.isEmpty()) {
                    usernameTextField.setErrorMessage("Username is required.");

                } else if (password == null || password.isEmpty()) {
                    passwordField.setErrorMessage("Password is required.");

                } else if (password.length() < MIN_PASSWORD_LENGTH) {
                    passwordField.setErrorMessage("Password must be at least " + MIN_PASSWORD_LENGTH + " characters.");

                } else if (role == null || role.isEmpty()) {
                    roleComboBox.setErrorMessage("Role is required.");

                } else {

                    final ServiceResponse serviceResponse = userService.createUser(RequestIdGenerator.generate(), username, email, password, role, policyService, contextService, Source.WEBUI.getSource());

                    if (serviceResponse.isSuccessful()) {
                        showSuccessNotification(serviceResponse.getMessage());
                        createUserDialog.close();
                        usersGrid.getDataProvider().refreshAll();
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

        usersGrid.addColumn(UserEntity::getUsername).setHeader("Username").setResizable(true).setSortable(true);
        usersGrid.addColumn(UserEntity::getEmail).setHeader("Email").setResizable(true).setSortable(true);
        usersGrid.addColumn(UserEntity::getRole).setHeader("Role").setResizable(true).setSortable(true);

        usersGrid.addComponentColumn(user -> {
            final Button changePasswordButton = new Button("Change Password", VaadinIcon.KEY.create());
            changePasswordButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
            changePasswordButton.setTooltipText("Change Password");

            // A deactivated user cannot sign in, so changing its password has no effect until it is
            // reactivated.
            if (user.isDeactivated()) {
                changePasswordButton.setEnabled(false);
                changePasswordButton.setTooltipText("User is deactivated.");
            }

            changePasswordButton.addClickListener(clickEvent -> {

                final Dialog changePasswordDialog = new Dialog();
                changePasswordDialog.setHeaderTitle("Change Password");

                final PasswordField passwordField = new PasswordField("New Password");
                passwordField.setWidthFull();
                passwordField.setRequired(true);
                passwordField.setHelperText(PASSWORD_HELPER_TEXT);

                final PasswordField confirmPasswordField = new PasswordField("Confirm New Password");
                confirmPasswordField.setWidthFull();
                confirmPasswordField.setRequired(true);

                final VerticalLayout dialogVerticalLayout = new VerticalLayout();
                dialogVerticalLayout.add(new Paragraph("Enter a new password for the user " + user.getUsername()));
                dialogVerticalLayout.add(passwordField, confirmPasswordField);
                dialogVerticalLayout.add(CommonWidgets.getLink("Password requirements", PASSWORD_DOCS_URL, true));
                changePasswordDialog.add(dialogVerticalLayout);

                final Button saveButton = new Button("Submit", e -> {

                    final String password = passwordField.getValue();
                    final String confirm = confirmPasswordField.getValue();

                    passwordField.setInvalid(false);
                    confirmPasswordField.setInvalid(false);

                    if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
                        passwordField.setInvalid(true);
                        passwordField.setErrorMessage("Password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
                        return;
                    }

                    if (!password.equals(confirm)) {
                        confirmPasswordField.setInvalid(true);
                        confirmPasswordField.setErrorMessage("Passwords do not match.");
                        return;
                    }

                    final ServiceResponse serviceResponse = userService.changePassword(RequestIdGenerator.generate(), user, password, Source.WEBUI.getSource());

                    if (serviceResponse.isSuccessful()) {
                        changePasswordDialog.close();
                        showSuccessNotification(serviceResponse.getMessage());
                    } else {
                        showFailureNotification(serviceResponse.getMessage());
                    }

                });

                saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

                final Button cancelButton = new Button("Cancel", e -> changePasswordDialog.close());

                changePasswordDialog.getFooter().add(cancelButton, saveButton);
                changePasswordDialog.open();

            });
            return changePasswordButton;
        }).setHeader("Change Password").setAutoWidth(true).setFlexGrow(0);

        usersGrid.addComponentColumn(user -> {

            // Unlock clears the failed-attempt lock while keeping the user's enrollment. It is the admin
            // action required to recover a user locked out after too many incorrect MFA codes.
            final Button unlockMfaButton = new Button("Unlock", VaadinIcon.UNLOCK.create());
            unlockMfaButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
            if (user.isMfaLocked()) {
                unlockMfaButton.setTooltipText("Unlock MFA for this user (clears the failed-attempt lock)");
            } else {
                unlockMfaButton.setEnabled(false);
                unlockMfaButton.setTooltipText("User is not locked.");
            }

            unlockMfaButton.addClickListener(clickEvent -> {

                final Dialog confirmDialog = new Dialog();
                confirmDialog.add(new H3("Unlock MFA"));
                confirmDialog.add(new Paragraph("Unlock multi-factor authentication for " + user.getUsername()
                        + "? The failed-attempt lock is cleared and they can enter a code again. Their enrollment "
                        + "is unchanged."));

                final Button confirmButton = new Button("Unlock", e -> {
                    userService.unlockMfa(RequestIdGenerator.generate(), user, Source.WEBUI.getSource());
                    usersGrid.getDataProvider().refreshAll();
                    confirmDialog.close();
                    showSuccessNotification("MFA unlocked for " + user.getUsername() + ".");
                });
                confirmButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

                final Button cancelButton = new Button("Cancel", e -> confirmDialog.close());

                confirmDialog.getFooter().add(cancelButton, confirmButton);
                confirmDialog.open();

            });

            final Button disableMfaButton = new Button("Disable MFA", VaadinIcon.BAN.create());
            disableMfaButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
            disableMfaButton.setTooltipText("Disable MFA for this user");

            // This is the admin reset path for a user who has lost their authenticator, so it is only
            // meaningful when the user actually has MFA enrolled.
            if (!user.isMfaEnabled()) {
                disableMfaButton.setEnabled(false);
                disableMfaButton.setTooltipText("User has not enabled MFA.");
            }

            disableMfaButton.addClickListener(clickEvent -> {

                final Dialog confirmDialog = new Dialog();
                confirmDialog.add(new H3("Disable MFA"));
                confirmDialog.add(new Paragraph("Disable multi-factor authentication for " + user.getUsername()
                        + "? Their enrolled authenticator is removed and they can sign in with just their password "
                        + "until they enroll again. Use this to reset a user who has lost their authenticator."));

                final Button confirmButton = new Button("Disable MFA", e -> {
                    userService.disableMfa(RequestIdGenerator.generate(), user, Source.WEBUI.getSource());
                    usersGrid.getDataProvider().refreshAll();
                    confirmDialog.close();
                    showSuccessNotification("MFA disabled for " + user.getUsername() + ".");
                });
                confirmButton.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_PRIMARY);

                final Button cancelButton = new Button("Cancel", e -> confirmDialog.close());

                confirmDialog.getFooter().add(cancelButton, confirmButton);
                confirmDialog.open();

            });

            final HorizontalLayout mfaActions = new HorizontalLayout(unlockMfaButton, disableMfaButton);
            mfaActions.setSpacing(true);
            return mfaActions;
        }).setHeader("MFA").setAutoWidth(true).setFlexGrow(0);

        usersGrid.addComponentColumn(user -> {

            final Button setRoleButton = new Button("Set Role", VaadinIcon.USER_CHECK.create());
            setRoleButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
            setRoleButton.setTooltipText("Set Role");

            // Don't allow the current user to change their own role.
            final UserEntity currentUser = getCurrentUser();
            if (currentUser != null && user.getId().equals(currentUser.getId())) {
                setRoleButton.setEnabled(false);
                setRoleButton.setTooltipText("You cannot change your own role.");
            }

            // A deactivated user holds no active access, so its role cannot be changed until it is
            // reactivated.
            if (user.isDeactivated()) {
                setRoleButton.setEnabled(false);
                setRoleButton.setTooltipText("User is deactivated.");
            }

            setRoleButton.addClickListener(clickEvent -> {

                final Dialog setRoleDialog = new Dialog();
                setRoleDialog.setHeaderTitle("Set User Role");

                final ComboBox<String> roleComboBox = new ComboBox<>("Role");
                roleComboBox.setItems("admin", "user");
                roleComboBox.setValue(user.getRole());
                roleComboBox.setWidthFull();
                roleComboBox.setRequired(true);
                roleComboBox.setAllowCustomValue(false);

                final VerticalLayout dialogVerticalLayout = new VerticalLayout();
                dialogVerticalLayout.add(new Paragraph("Select a new role for the user " + user.getUsername()));
                dialogVerticalLayout.add(roleComboBox);
                setRoleDialog.add(dialogVerticalLayout);

                final Button saveButton = new Button("Submit", e -> {

                    final String role = roleComboBox.getValue();

                    if (role == null || role.isEmpty()) {
                        roleComboBox.setErrorMessage("Role is required.");

                    } else {

                        final ServiceResponse serviceResponse = userService.setUserRole(RequestIdGenerator.generate(), user, role, Source.WEBUI.getSource());

                        if (serviceResponse.isSuccessful()) {
                            setRoleDialog.close();
                            usersGrid.getDataProvider().refreshAll();
                            showSuccessNotification(serviceResponse.getMessage());
                        } else {
                            showFailureNotification(serviceResponse.getMessage());
                        }

                    }

                });

                saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

                final Button cancelButton = new Button("Cancel", e -> setRoleDialog.close());

                setRoleDialog.getFooter().add(cancelButton, saveButton);
                setRoleDialog.open();

            });
            return setRoleButton;
        }).setHeader("Set Role").setAutoWidth(true).setFlexGrow(0);

        usersGrid.addComponentColumn(user -> {

            final UserEntity currentUser = getCurrentUser();
            final boolean isSelf = currentUser != null && user.getId().equals(currentUser.getId());

            if (user.isDeactivated()) {

                // Reactivate a deactivated user: restores sign-in and API access; all of the user's
                // data was retained, so the account returns to its prior state.
                final Button reactivateButton = new Button("Reactivate User", VaadinIcon.USER_CHECK.create());
                reactivateButton.addThemeVariants(ButtonVariant.LUMO_SUCCESS, ButtonVariant.LUMO_TERTIARY);
                reactivateButton.setTooltipText("Reactivate User");

                reactivateButton.addClickListener(_ -> {
                    final Dialog confirmDialog = new Dialog();
                    confirmDialog.add(new H3("Reactivate User"));
                    confirmDialog.add(new Paragraph("Reactivate the user " + user.getUsername() + "? The account will be able to sign in and its API keys will work again."));

                    final Button confirmButton = new Button("Reactivate", e -> {
                        userService.reactivateUser(RequestIdGenerator.generate(), user, Source.WEBUI.getSource());
                        usersGrid.getDataProvider().refreshAll();
                        confirmDialog.close();
                        showSuccessNotification("User reactivated.");
                    });
                    confirmButton.addThemeVariants(ButtonVariant.LUMO_SUCCESS, ButtonVariant.LUMO_PRIMARY);

                    final Button cancelButton = new Button("Cancel", e -> confirmDialog.close());
                    confirmDialog.getFooter().add(cancelButton, confirmButton);
                    confirmDialog.open();
                });
                return reactivateButton;

            }

            // Deactivate an active user: revokes sign-in and API access while retaining the account and
            // all of its data so it can be reactivated later.
            final Button deactivateButton = new Button("Deactivate User", VaadinIcon.BAN.create());
            deactivateButton.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);
            deactivateButton.setTooltipText("Deactivate User");

            // Don't allow the current user to deactivate themselves.
            if (isSelf) {
                deactivateButton.setEnabled(false);
                deactivateButton.setTooltipText("You cannot deactivate yourself.");
            }

            deactivateButton.addClickListener(_ -> {

                final Dialog confirmDialog = new Dialog();
                confirmDialog.add(new H3("Deactivate User"));
                confirmDialog.add(new Paragraph("Are you sure you want to deactivate the user " + user.getUsername() + "?"));
                confirmDialog.add(new Paragraph("The account can no longer sign in and its API keys stop working. The user record and all of the user's data (API keys, contexts, custom lists, policies, always/never redact lists, and ledger entries) are retained, and you can reactivate the account at any time."));

                final Button confirmButton = new Button("Deactivate", e -> {
                    userService.deactivateUser(RequestIdGenerator.generate(), user, Source.WEBUI.getSource());
                    usersGrid.getDataProvider().refreshAll();
                    confirmDialog.close();
                    showSuccessNotification("User deactivated.");
                });
                confirmButton.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_PRIMARY);

                final Button cancelButton = new Button("Cancel", e -> confirmDialog.close());

                confirmDialog.getFooter().add(cancelButton, confirmButton);
                confirmDialog.open();

            });
            return deactivateButton;
        }).setHeader("Status").setAutoWidth(true).setFlexGrow(0);

        // Lazy paging: fetch one page at a time plus the total count for the scrollbar. The grid shows
        // all users, including deactivated ones (marked by the Reactivate User action).
        usersGrid.setItems(
                query -> userService.findAll(query.getOffset(), query.getLimit()).stream(),
                query -> userService.count());
        usersGrid.setWidthFull();

        final VerticalLayout usersVerticalLayout = new VerticalLayout();
        usersVerticalLayout.add(createUserButton);
        usersVerticalLayout.add(usersGrid);
        usersVerticalLayout.setSizeFull();

        final VerticalLayout adminSettingsVerticalLayout = new VerticalLayout();
        adminSettingsVerticalLayout.setSizeFull();

        final Span auditAlwaysOnNote = new Span(
                "Audit logging is always on and cannot be disabled. Every security-relevant action is "
                        + "recorded to the audit log; review and export it from the Audit Log tab.");

        final Checkbox diffuseCountsEnabledCheckbox = new Checkbox("Record PII count statistics for differential-privacy reporting");
        diffuseCountsEnabledCheckbox.setValue(finalAdminSettingsEntity.isDiffuseCountsEnabled());

        // Phield (PII drift monitoring): enable + endpoint configuration.
        final Checkbox phieldEnabledCheckbox = new Checkbox("Publish PII count statistics to Phield for drift monitoring");
        phieldEnabledCheckbox.setValue(finalAdminSettingsEntity.isPhieldEnabled());

        final TextField phieldUrlField = new TextField("Phield URL");
        phieldUrlField.setPlaceholder("http://phield:8080");
        phieldUrlField.setWidth("480px");
        phieldUrlField.setValue(finalAdminSettingsEntity.getPhieldUrl() != null ? finalAdminSettingsEntity.getPhieldUrl() : "");

        final TextField phieldSourceIdField = new TextField("Phield Source ID");
        phieldSourceIdField.setValue(finalAdminSettingsEntity.getPhieldSourceId() != null ? finalAdminSettingsEntity.getPhieldSourceId() : "philter");

        final TextField phieldOrganizationField = new TextField("Phield Organization");
        phieldOrganizationField.setValue(finalAdminSettingsEntity.getPhieldOrganization() != null ? finalAdminSettingsEntity.getPhieldOrganization() : "philter");

        // The Phield endpoint fields are only relevant when publishing to Phield is enabled, so keep
        // them enabled/disabled in lockstep with the checkbox (initially and as it is toggled).
        final boolean phieldInitiallyEnabled = phieldEnabledCheckbox.getValue();
        phieldUrlField.setEnabled(phieldInitiallyEnabled);
        phieldSourceIdField.setEnabled(phieldInitiallyEnabled);
        phieldOrganizationField.setEnabled(phieldInitiallyEnabled);
        phieldEnabledCheckbox.addValueChangeListener(e -> {
            final boolean enabled = e.getValue();
            phieldUrlField.setEnabled(enabled);
            phieldSourceIdField.setEnabled(enabled);
            phieldOrganizationField.setEnabled(enabled);
        });

        // Output signing section.
        final Checkbox signingEnabledCheckbox = new Checkbox("Enable output signing (ES256 JWT on X-Philter-Signature response header)");
        signingEnabledCheckbox.setValue(finalAdminSettingsEntity.isSigningEnabled());

        final TextField fingerprintField = new TextField("Public Key SHA-256 Fingerprint");
        fingerprintField.setValue(signingKeyDataService.getPublicKeyFingerprint());
        fingerprintField.setReadOnly(true);
        fingerprintField.setWidth("640px");

        final Button regenerateKeyButton = new Button("Regenerate Signing Key", VaadinIcon.REFRESH.create());
        regenerateKeyButton.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);
        regenerateKeyButton.addClickListener(event -> {
            final Dialog confirmDialog = new Dialog();
            confirmDialog.setHeaderTitle("Regenerate Signing Key");
            confirmDialog.add(new Paragraph(
                    "This will generate a new ES256 keypair and discard the current one. "
                            + "Any consumer that cached the old public key will no longer be able to "
                            + "verify signatures until they fetch the new key from GET /api/signing-key. "
                            + "Continue?"));
            final Button confirmButton = new Button("Regenerate", e -> {
                final ai.philterd.philter.data.entities.UserEntity adminUser = getCurrentUser();
                signingKeyDataService.regenerate(adminUser != null ? adminUser.getId() : null);
                fingerprintField.setValue(signingKeyDataService.getPublicKeyFingerprint());
                confirmDialog.close();
                showSuccessNotification("Signing key regenerated.");
            });
            confirmButton.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_PRIMARY);
            final Button cancelButton = new Button("Cancel", e -> confirmDialog.close());
            confirmDialog.getFooter().add(cancelButton, confirmButton);
            confirmDialog.open();
        });

        // Multi-factor authentication: when enabled, users can enroll a TOTP authenticator from the MFA
        // tab of My Account, and enrolled users are prompted for a code at login.
        final Checkbox mfaEnabledCheckbox = new Checkbox("Enable multi-factor authentication (TOTP) for dashboard logins");
        mfaEnabledCheckbox.setValue(finalAdminSettingsEntity.isMfaEnabled());

        final Span mfaNote = new Span(
                "When enabled, MFA is offered to users but not required: each user can optionally enroll an "
                        + "authenticator app from the MFA tab of My Account, and only enrolled users are prompted "
                        + "for a code at login. Existing enrollments are unaffected if this is later turned off. To "
                        + "reset a user who has lost their authenticator, use Disable MFA on the Users tab.");

        final Button saveLoggingSettingsButton = new Button("Save", e -> {
            adminSettingsDataService.saveDiffuseCountsEnabled(diffuseCountsEnabledCheckbox.getValue());
            adminSettingsDataService.savePhieldSettings(phieldEnabledCheckbox.getValue(), phieldUrlField.getValue(),
                    phieldSourceIdField.getValue(), phieldOrganizationField.getValue());
            adminSettingsDataService.saveSigningEnabled(signingEnabledCheckbox.getValue());
            adminSettingsDataService.saveMfaEnabled(mfaEnabledCheckbox.getValue());
            showSuccessNotification("Admin settings saved.");
        });
        saveLoggingSettingsButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        adminSettingsVerticalLayout.add(auditAlwaysOnNote, diffuseCountsEnabledCheckbox,
                phieldEnabledCheckbox, phieldUrlField, phieldSourceIdField, phieldOrganizationField,
                new H3("Output Signing"), signingEnabledCheckbox, fingerprintField, regenerateKeyButton,
                new H3("Multi-Factor Authentication"), mfaEnabledCheckbox, mfaNote,
                saveLoggingSettingsButton);

        final TabSheet tabSheet = new TabSheet();
        tabSheet.add("Users", usersVerticalLayout);
        tabSheet.add("Admin Settings", adminSettingsVerticalLayout);
        tabSheet.add("Audit Log", buildAuditLogLayout(auditLogService));
        tabSheet.setSizeFull();

        pageVerticalLayout.add(tabSheet);
        pageVerticalLayout.add(CommonWidgets.getFooter());

        pageHorizontalLayout.add(pageVerticalLayout);

        setContent(pageHorizontalLayout);

    }

    /** Builds the "Audit Log" tab: a from/to date range and a button to download that range as CSV. */
    private VerticalLayout buildAuditLogLayout(final AuditLogService auditLogService) {

        final VerticalLayout layout = new VerticalLayout();
        layout.setSizeFull();

        layout.add(new Paragraph("Download the audit log of security-relevant actions (redactions, ledger "
                + "queries and deletions, policy/key/user changes, admin cross-user access, and more) as a CSV file. "
                + "Choose a date range of up to " + AuditLogService.MAX_EXPORT_WINDOW_DAYS + " days; the export "
                + "contains up to " + AuditLogService.MAX_EXPORT_ROWS + " events in that range, newest first."));

        final LocalDate today = LocalDate.now();

        final DatePicker fromPicker = new DatePicker("From");
        fromPicker.setMax(today);
        fromPicker.setValue(today.minusDays(AuditLogService.MAX_EXPORT_WINDOW_DAYS - 1));

        final DatePicker toPicker = new DatePicker("To");
        toPicker.setMax(today);
        toPicker.setValue(today);

        final HorizontalLayout dateRow = new HorizontalLayout(fromPicker, toPicker);
        dateRow.setAlignItems(HorizontalLayout.Alignment.BASELINE);

        final Span error = new Span();
        error.getStyle().set("color", "var(--lumo-error-text-color)");

        final Button downloadButton = new Button("Download Audit Log (CSV)", VaadinIcon.DOWNLOAD.create());
        downloadButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        final Anchor downloadAnchor = new Anchor();
        downloadAnchor.getElement().setAttribute("download", true);
        downloadAnchor.add(downloadButton);

        // Re-validate the range and rebuild the download whenever a date changes. The CSV is generated
        // when the link is requested (StreamResource factory), so it reflects the chosen range and the
        // log at download time. The download is enabled only for a valid range within the window limit.
        final Runnable refresh = () -> {
            final LocalDate from = fromPicker.getValue();
            final LocalDate to = toPicker.getValue();

            if (from == null || to == null) {
                error.setText("Select both a From and To date.");
                downloadButton.setEnabled(false);
                downloadAnchor.removeHref();
                return;
            }
            if (from.isAfter(to)) {
                error.setText("\"From\" must be on or before \"To\".");
                downloadButton.setEnabled(false);
                downloadAnchor.removeHref();
                return;
            }
            if (ChronoUnit.DAYS.between(from, to) > AuditLogService.MAX_EXPORT_WINDOW_DAYS) {
                error.setText("The date range cannot exceed " + AuditLogService.MAX_EXPORT_WINDOW_DAYS + " days.");
                downloadButton.setEnabled(false);
                downloadAnchor.removeHref();
                return;
            }

            error.setText("");
            downloadButton.setEnabled(true);

            // The service validates the window and converts the dates (server time zone, 'to' day
            // included in full); this UI check just mirrors it to enable/disable the download.
            downloadAnchor.setHref(DownloadHandler.fromInputStream(downloadEvent -> {
                final byte[] csv = auditLogService.exportCsv(from, to);
                return new DownloadResponse(new ByteArrayInputStream(csv), "philter-audit-log.csv", "text/csv", csv.length);
            }));
        };

        fromPicker.addValueChangeListener(e -> refresh.run());
        toPicker.addValueChangeListener(e -> refresh.run());
        refresh.run();

        layout.add(dateRow, error, downloadAnchor);
        return layout;

    }

    /**
     * Generates a random password that satisfies the requirements: {@link #GENERATED_PASSWORD_LENGTH}
     * characters with at least one lowercase letter, uppercase letter, digit, and symbol.
     */
    private static String generateRandomPassword() {
        final String all = PW_LOWER + PW_UPPER + PW_DIGITS + PW_SYMBOLS;
        final List<Character> chars = new ArrayList<>(GENERATED_PASSWORD_LENGTH);
        // Guarantee at least one character from each class.
        chars.add(PW_LOWER.charAt(SECURE_RANDOM.nextInt(PW_LOWER.length())));
        chars.add(PW_UPPER.charAt(SECURE_RANDOM.nextInt(PW_UPPER.length())));
        chars.add(PW_DIGITS.charAt(SECURE_RANDOM.nextInt(PW_DIGITS.length())));
        chars.add(PW_SYMBOLS.charAt(SECURE_RANDOM.nextInt(PW_SYMBOLS.length())));
        while (chars.size() < GENERATED_PASSWORD_LENGTH) {
            chars.add(all.charAt(SECURE_RANDOM.nextInt(all.length())));
        }
        // Shuffle so the guaranteed characters are not always in the same leading positions.
        Collections.shuffle(chars, SECURE_RANDOM);
        final StringBuilder sb = new StringBuilder(chars.size());
        for (final char c : chars) {
            sb.append(c);
        }
        return sb.toString();
    }

}

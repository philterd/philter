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
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.providers.ApiKeyEntityDataProvider;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.model.Source;
import ai.philterd.philter.services.RequestIdGenerator;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.views.widgets.CommonWidgets;
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
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

import java.net.URI;
import java.security.SecureRandom;

@Route(value = "my-account")
@PageTitle("Philter - My Account")
@PermitAll
public class AccountView extends AbstractRestrictedView {

    private static final int MIN_PASSWORD_LENGTH = 8;

    private static final String SECRET_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int GENERATED_SECRET_LENGTH = 48;
    private static final int MIN_SECRET_LENGTH = 16;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserEntity accountUser;

    public AccountView(final MongoClient mongoClient, final EncryptionService encryptionService,
                       final AuditEventPublisher auditEventPublisher, final ApiKeyDataService apiKeyService) {
        super(mongoClient, encryptionService, auditEventPublisher);

        this.accountUser = getCurrentUser();

        final TabSheet tabSheet = new TabSheet();
        tabSheet.add("Account", buildAccountSection());
        tabSheet.add("API Keys", buildApiKeysSection(apiKeyService));
        tabSheet.add("Webhook", buildWebhookSection());
        tabSheet.setSizeFull();

        final VerticalLayout div = new VerticalLayout();
        div.add(getTitle("My Account"));
        div.add(tabSheet);
        div.add(CommonWidgets.getFooter());
        div.setSizeFull();

        final HorizontalLayout pageHorizontalLayout = new HorizontalLayout();
        pageHorizontalLayout.add(div);
        pageHorizontalLayout.setSizeFull();

        setContent(pageHorizontalLayout);

    }

    /** The Account tab: the user's email and a change-password form. */
    private VerticalLayout buildAccountSection() {

        final TextField emailField = new TextField("Email Address");
        emailField.setValue(accountUser != null && accountUser.getEmail() != null ? accountUser.getEmail() : "");
        emailField.setReadOnly(true);
        emailField.setWidth("480px");

        final PasswordField newPassword = new PasswordField("New Password");
        newPassword.setWidth("480px");

        final PasswordField confirmPassword = new PasswordField("Confirm New Password");
        confirmPassword.setWidth("480px");

        final Button changePasswordButton = new Button("Change Password", e -> {

            final String password = newPassword.getValue();
            final String confirm = confirmPassword.getValue();

            if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
                newPassword.setInvalid(true);
                newPassword.setErrorMessage("Password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
                return;
            }
            if (!password.equals(confirm)) {
                confirmPassword.setInvalid(true);
                confirmPassword.setErrorMessage("Passwords do not match.");
                return;
            }
            if (userService.passwordMatches(accountUser, password)) {
                newPassword.setInvalid(true);
                newPassword.setErrorMessage("The new password must be different from the current password.");
                return;
            }

            final ServiceResponse response = userService.changePassword(
                    RequestIdGenerator.generate(), accountUser, password, Source.WEBUI.getSource());

            if (response.isSuccessful()) {
                newPassword.clear();
                confirmPassword.clear();
                showSuccessNotification("Password changed.");
            } else {
                showFailureNotification(response.getMessage());
            }

        });
        changePasswordButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        final VerticalLayout layout = new VerticalLayout();
        layout.setSizeFull();
        layout.add(emailField);
        layout.add(new H3("Change Password"));
        layout.add(newPassword, confirmPassword, changePasswordButton);
        return layout;

    }

    /** The API Keys tab: list, create, and delete the user's API keys. */
    private VerticalLayout buildApiKeysSection(final ApiKeyDataService apiKeyService) {

        final ApiKeyEntityDataProvider apiKeyDataProvider = new ApiKeyEntityDataProvider(accountUser.getId(), apiKeyService);

        final Grid<ApiKeyEntity> apiKeysGrid = new Grid<>(ApiKeyEntity.class, false);
        apiKeysGrid.addColumn(ApiKeyEntity::getApiKeyPrefix).setHeader("Key").setResizable(true).setSortable(true);
        apiKeysGrid.setDataProvider(apiKeyDataProvider);
        apiKeysGrid.setWidthFull();

        // Deleted keys are revoked and retained in the database for audit resolution, but they are not
        // shown here: the list shows only usable keys.
        apiKeysGrid.addComponentColumn(apiKeyEntity -> {
            final Button deleteButton = new Button("Delete", VaadinIcon.TRASH.create());
            deleteButton.setTooltipText("Delete this API key");

            deleteButton.addClickListener(event -> {

                final Dialog confirmDialog = new Dialog();
                confirmDialog.setWidth("450px");
                confirmDialog.add(new H3("Confirm Deletion"));
                confirmDialog.add(new Paragraph("Are you sure you want to delete the selected API key? It is revoked immediately and stops working right away. This cannot be undone; the key cannot be reactivated. The key record is retained (marked deleted) so audit entries that reference it still resolve."));

                final Button confirmButton = new Button("Delete", e -> {
                    apiKeyService.deleteByApiKey(RequestIdGenerator.generate(), apiKeyEntity, getClientIpAddress());
                    apiKeysGrid.getDataProvider().refreshAll();
                    showSuccessNotification("API key deleted.");
                    confirmDialog.close();
                });
                confirmButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR);

                final Button cancelButton = new Button("Cancel", e -> confirmDialog.close());
                cancelButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

                confirmDialog.getFooter().add(cancelButton, confirmButton);
                confirmDialog.open();
            });
            return deleteButton;
        }).setHeader("Delete").setAutoWidth(true).setFlexGrow(0);

        final Button createApiKeyButton = new Button("New API Key", VaadinIcon.PLUS.create());
        createApiKeyButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        createApiKeyButton.addClickListener(event -> {

            final ServiceResponse serviceResponse = apiKeyService.createApiKey(
                    RequestIdGenerator.generate(), accountUser.getId(), getClientIpAddress());

            if (serviceResponse.isSuccessful()) {

                final Dialog newApiKeyDialog = new Dialog("New API Key");
                newApiKeyDialog.add(new Paragraph("Please save your API key in a safe place since you won't be able to view it again. Keep it secure, as anyone with your API key can make requests on your behalf. If you lose it, you'll need to generate a new one."));

                final TextField apiKeyTextField = new TextField("API Key");
                apiKeyTextField.setValue(serviceResponse.getMessage());
                apiKeyTextField.setReadOnly(true);
                apiKeyTextField.setWidthFull();

                // Copy the key to the clipboard. The value is passed as a JS parameter (not interpolated)
                // and never logged. Requires a secure context (HTTPS or localhost) for navigator.clipboard.
                final Button copyButton = new Button("Copy", VaadinIcon.COPY.create());
                copyButton.setTooltipText("Copy the API key to the clipboard");
                copyButton.addClickListener(e -> {
                    apiKeyTextField.getElement().executeJs("navigator.clipboard.writeText($0)", serviceResponse.getMessage());
                    showSuccessNotification("API key copied to the clipboard.");
                });

                final HorizontalLayout keyRow = new HorizontalLayout(apiKeyTextField, copyButton);
                keyRow.setAlignItems(HorizontalLayout.Alignment.BASELINE);
                keyRow.setWidthFull();
                keyRow.setFlexGrow(1, apiKeyTextField);

                newApiKeyDialog.add(keyRow);

                final Button cancelButton = new Button("Close", e -> newApiKeyDialog.close());
                cancelButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

                newApiKeyDialog.setCloseOnOutsideClick(false);
                newApiKeyDialog.getFooter().add(cancelButton);
                newApiKeyDialog.setWidth("450px");
                newApiKeyDialog.open();

                apiKeysGrid.getDataProvider().refreshAll();
                showSuccessNotification("New API key created.");

            }

        });

        final VerticalLayout layout = new VerticalLayout();
        layout.add(createApiKeyButton);
        layout.add(apiKeysGrid);
        layout.setSizeFull();
        return layout;

    }

    /** The Webhook tab: configure or remove the per-user delivery webhook. */
    private VerticalLayout buildWebhookSection() {

        final VerticalLayout layout = new VerticalLayout();
        layout.setPadding(false);
        layout.setSpacing(true);

        layout.add(new Span("Receive an HTTP POST when an asynchronous redaction completes or fails. "
                + "Requests are signed with HMAC-SHA256 over \"<timestamp>.<body>\" using the secret below. "
                + "See the release notes for the full receiver contract."));

        final TextField urlField = new TextField("Webhook URL");
        urlField.setPlaceholder("https://example.com/philter-webhook");
        urlField.setWidth("480px");
        urlField.setValue(accountUser.getWebhookUrl() != null ? accountUser.getWebhookUrl() : "");

        final PasswordField secretField = new PasswordField("Webhook Secret");
        secretField.setHelperText("Minimum " + MIN_SECRET_LENGTH + " characters. Click the eye icon to reveal.");
        secretField.setWidth("480px");
        secretField.setValue(accountUser.getWebhookSecret() != null ? accountUser.getWebhookSecret() : "");

        final Button generateButton = new Button("Generate", e -> secretField.setValue(generateSecret()));

        // Align the Generate button to the secret input's baseline so it sits beside the field rather
        // than dropping below it (the field is taller because of its helper text).
        final HorizontalLayout secretRow = new HorizontalLayout(secretField, generateButton);
        secretRow.setAlignItems(HorizontalLayout.Alignment.BASELINE);

        final Button saveButton = new Button("Save Webhook", e -> {

            final String url = urlField.getValue() != null ? urlField.getValue().trim() : "";
            final String secret = secretField.getValue() != null ? secretField.getValue() : "";

            if (url.isEmpty() || secret.isEmpty()) {
                showFailureNotification("Both URL and secret are required. Use Remove to clear the webhook.");
                return;
            }

            try {
                final URI parsed = URI.create(url);
                if (parsed.getScheme() == null || (!parsed.getScheme().equalsIgnoreCase("http") && !parsed.getScheme().equalsIgnoreCase("https"))) {
                    showFailureNotification("URL must start with http:// or https://");
                    return;
                }
            } catch (final Exception ex) {
                showFailureNotification("Invalid URL: " + ex.getMessage());
                return;
            }

            if (secret.length() < MIN_SECRET_LENGTH) {
                showFailureNotification("Secret must be at least " + MIN_SECRET_LENGTH + " characters.");
                return;
            }

            accountUser.setWebhookUrl(url);
            accountUser.setWebhookSecret(secret);
            userService.update(accountUser);

            // Audit the webhook configuration, but never record the URL or secret themselves.
            auditEventPublisher.auditEvent(RequestIdGenerator.generate(), AuditLogEvent.WEBHOOK_CONFIGURED,
                    accountUser.getId(), accountUser.getId(), Source.WEBUI.getSource(), null);

            showSuccessNotification("Webhook saved.");
        });
        saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        final Button removeButton = new Button("Remove Webhook", e -> {
            urlField.setValue("");
            secretField.setValue("");
            accountUser.setWebhookUrl(null);
            accountUser.setWebhookSecret(null);
            userService.update(accountUser);

            auditEventPublisher.auditEvent(RequestIdGenerator.generate(), AuditLogEvent.WEBHOOK_REMOVED,
                    accountUser.getId(), accountUser.getId(), Source.WEBUI.getSource(), null);

            showSuccessNotification("Webhook removed.");
        });
        removeButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        final HorizontalLayout buttonRow = new HorizontalLayout(saveButton, removeButton);

        layout.add(urlField, secretRow, buttonRow);
        return layout;

    }

    private static String generateSecret() {
        final StringBuilder sb = new StringBuilder(GENERATED_SECRET_LENGTH);
        for (int i = 0; i < GENERATED_SECRET_LENGTH; i++) {
            sb.append(SECRET_ALPHABET.charAt(SECURE_RANDOM.nextInt(SECRET_ALPHABET.length())));
        }
        return sb.toString();
    }

}

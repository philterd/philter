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
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.model.Source;
import ai.philterd.philter.services.RequestIdGenerator;
import ai.philterd.philter.data.entities.AdminSettingsEntity;
import ai.philterd.philter.data.entities.SettingsEntity;
import ai.philterd.philter.data.services.SettingsDataService;
import ai.philterd.philter.data.services.AdminSettingsDataService;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.views.widgets.CommonWidgets;
import com.mongodb.client.MongoClient;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Hr;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.security.SecureRandom;

@Route(value = "settings")
@PageTitle("Philter - Settings")
@PermitAll
public class SettingsView extends AbstractRestrictedView {

    private static final Logger LOGGER = LogManager.getLogger(SettingsView.class);

    private static final String SECRET_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int GENERATED_SECRET_LENGTH = 48;
    private static final int MIN_SECRET_LENGTH = 16;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Override
    public String getHelpMarkdownText() {
        return """
            ## Settings

            Settings specific to your account. Enable redaction ledgers, and configure a webhook
            URL and secret to receive a signed HTTP POST when an asynchronous redaction completes
            or fails.
            """;
    }

    public SettingsView(final MongoClient mongoClient, final EncryptionService encryptionService, final AuditEventPublisher auditEventPublisher, final SettingsDataService settingsDataService, final AdminSettingsDataService adminSettingsDataService) {

        super(mongoClient, encryptionService, auditEventPublisher, true);

        final VerticalLayout mySettingsVerticalLayout = new VerticalLayout();
        mySettingsVerticalLayout.setSizeFull();

        SettingsEntity settingsEntity = settingsDataService.findByUserId(userEntity.getId());
        if (settingsEntity == null) {
            settingsEntity = new SettingsEntity();
            settingsEntity.setUserId(userEntity.getId());
            settingsEntity.setRedactionLedgerEnabled(true);
        }

        final Checkbox redactionLedgerEnabledCheckbox = new Checkbox("Enable Redaction Ledgers");
        redactionLedgerEnabledCheckbox.setValue(settingsEntity.isRedactionLedgerEnabled());

        final AdminSettingsEntity adminSettingsEntity = adminSettingsDataService.findAdminSettings();
        if (adminSettingsEntity != null) {
            redactionLedgerEnabledCheckbox.setEnabled(adminSettingsEntity.isRedactionLedgerOptionEnabled());
            redactionLedgerEnabledCheckbox.setTooltipText("This option is disabled by the admin.");
            redactionLedgerEnabledCheckbox.setLabel("Enable Redaction Ledgers (disabled by admin)");
        }

        final SettingsEntity finalSettingsEntity = settingsEntity;

        final Button saveMySettingsButton = new Button("Save", e -> {
            finalSettingsEntity.setRedactionLedgerEnabled(redactionLedgerEnabledCheckbox.getValue());
            if (finalSettingsEntity.getId() == null) {
                settingsDataService.save(finalSettingsEntity);
            } else {
                settingsDataService.update(finalSettingsEntity);
            }

            auditEventPublisher.auditEvent(RequestIdGenerator.generate(), AuditLogEvent.SETTINGS_UPDATED,
                    userEntity.getId(), userEntity.getId(), Source.WEBUI.getSource(),
                    "redactionLedgerEnabled: " + redactionLedgerEnabledCheckbox.getValue());

            showSuccessNotification("Settings saved.");
        });
        saveMySettingsButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        mySettingsVerticalLayout.add(new Span("These settings are specific to your user account."));
        mySettingsVerticalLayout.add(redactionLedgerEnabledCheckbox, saveMySettingsButton);

        final VerticalLayout webhookVerticalLayout = buildWebhookSection();

        final VerticalLayout pageVerticalLayout = new VerticalLayout();
        pageVerticalLayout.add(getTitle("Settings"));
        pageVerticalLayout.add(mySettingsVerticalLayout);
        pageVerticalLayout.add(new Hr());
        pageVerticalLayout.add(webhookVerticalLayout);
        pageVerticalLayout.add(CommonWidgets.getFooter());
        pageVerticalLayout.setSizeFull();

        final HorizontalLayout pageHorizontalLayout = new HorizontalLayout();
        pageHorizontalLayout.add(pageVerticalLayout);
        pageHorizontalLayout.add(helpWindowVerticalLayout);
        pageHorizontalLayout.setSizeFull();

        setContent(pageHorizontalLayout);

    }

    private VerticalLayout buildWebhookSection() {

        final VerticalLayout layout = new VerticalLayout();
        layout.setPadding(false);
        layout.setSpacing(true);

        layout.add(new H4("Webhook"));
        layout.add(new Span("Receive an HTTP POST when an asynchronous redaction completes or fails. "
                + "Requests are signed with HMAC-SHA256 over \"<timestamp>.<body>\" using the secret below. "
                + "See the release notes for the full receiver contract."));

        final TextField urlField = new TextField("Webhook URL");
        urlField.setPlaceholder("https://example.com/philter-webhook");
        urlField.setWidth("480px");
        urlField.setValue(userEntity.getWebhookUrl() != null ? userEntity.getWebhookUrl() : "");

        final PasswordField secretField = new PasswordField("Webhook Secret");
        secretField.setHelperText("Minimum " + MIN_SECRET_LENGTH + " characters. Click the eye icon to reveal.");
        secretField.setWidth("480px");
        secretField.setValue(userEntity.getWebhookSecret() != null ? userEntity.getWebhookSecret() : "");

        final Button generateButton = new Button("Generate", e -> secretField.setValue(generateSecret()));

        final HorizontalLayout secretRow = new HorizontalLayout(secretField, generateButton);
        secretRow.setAlignItems(HorizontalLayout.Alignment.END);

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
            } catch (Exception ex) {
                showFailureNotification("Invalid URL: " + ex.getMessage());
                return;
            }

            if (secret.length() < MIN_SECRET_LENGTH) {
                showFailureNotification("Secret must be at least " + MIN_SECRET_LENGTH + " characters.");
                return;
            }

            userEntity.setWebhookUrl(url);
            userEntity.setWebhookSecret(secret);
            userService.update(userEntity);

            // Audit the webhook configuration, but never record the URL or secret themselves.
            auditEventPublisher.auditEvent(RequestIdGenerator.generate(), AuditLogEvent.WEBHOOK_CONFIGURED,
                    userEntity.getId(), userEntity.getId(), Source.WEBUI.getSource(), null);

            showSuccessNotification("Webhook saved.");
        });
        saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        final Button removeButton = new Button("Remove Webhook", e -> {
            urlField.setValue("");
            secretField.setValue("");
            userEntity.setWebhookUrl(null);
            userEntity.setWebhookSecret(null);
            userService.update(userEntity);

            auditEventPublisher.auditEvent(RequestIdGenerator.generate(), AuditLogEvent.WEBHOOK_REMOVED,
                    userEntity.getId(), userEntity.getId(), Source.WEBUI.getSource(), null);

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

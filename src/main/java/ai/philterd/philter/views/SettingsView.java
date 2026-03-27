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
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Route(value = "settings")
@PageTitle("Philter - Settings")
@PermitAll
public class SettingsView extends AbstractRestrictedView {

    private static final Logger LOGGER = LogManager.getLogger(SettingsView.class);

    @Override
    public String getHelpMarkdownText() {
        return "Placeholder for settings help text.";
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
            showSuccessNotification("Settings saved.");
        });
        saveMySettingsButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        mySettingsVerticalLayout.add(new Span("These settings are specific to your user account."));
        mySettingsVerticalLayout.add(redactionLedgerEnabledCheckbox, saveMySettingsButton);

        final VerticalLayout pageVerticalLayout = new VerticalLayout();
        pageVerticalLayout.add(getTitle("Settings"));
        pageVerticalLayout.add(mySettingsVerticalLayout);
        pageVerticalLayout.add(CommonWidgets.getFooter());
        pageVerticalLayout.setSizeFull();

        final HorizontalLayout pageHorizontalLayout = new HorizontalLayout();
        pageHorizontalLayout.add(pageVerticalLayout);
        pageHorizontalLayout.add(helpWindowVerticalLayout);
        pageHorizontalLayout.setSizeFull();

        setContent(pageHorizontalLayout);

    }

}

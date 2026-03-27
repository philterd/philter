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
import ai.philterd.philter.data.entities.SettingsEntity;
import ai.philterd.philter.data.services.SettingsDataService;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.views.widgets.CommonWidgets;
import com.mongodb.client.MongoClient;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.TabSheet;
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

    public SettingsView(final MongoClient mongoClient, final EncryptionService encryptionService, final AuditEventPublisher auditEventPublisher, final SettingsDataService settingsDataService) {

        super(mongoClient, encryptionService, auditEventPublisher, true);

        final VerticalLayout mySettingsVerticalLayout = new VerticalLayout();
        mySettingsVerticalLayout.setSizeFull();

        final VerticalLayout generalVerticalLayout = new VerticalLayout();
        generalVerticalLayout.setSizeFull();

        final VerticalLayout loggingVerticalLayout = new VerticalLayout();
        loggingVerticalLayout.setSizeFull();

        SettingsEntity settingsEntity = settingsDataService.findByUserId(userEntity.getId());
        if (settingsEntity == null) {
            settingsEntity = new SettingsEntity();
            settingsEntity.setUserId(userEntity.getId());
            settingsEntity.setLoggingEnabled(false);
        }

        final Checkbox loggingEnabledCheckbox = new Checkbox("Enable Logging");
        loggingEnabledCheckbox.setValue(settingsEntity.isLoggingEnabled());

        final SettingsEntity finalSettingsEntity = settingsEntity;
        final Button saveLoggingSettingsButton = new Button("Save", e -> {
            finalSettingsEntity.setLoggingEnabled(loggingEnabledCheckbox.getValue());
            if (finalSettingsEntity.getId() == null) {
                settingsDataService.save(finalSettingsEntity);
            } else {
                settingsDataService.update(finalSettingsEntity);
            }
            showSuccessNotification("Logging settings saved.");
        });
        saveLoggingSettingsButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        loggingVerticalLayout.add(loggingEnabledCheckbox, saveLoggingSettingsButton);

        final TabSheet tabSheet = new TabSheet();
        tabSheet.add("My Settings", mySettingsVerticalLayout);

        if(userEntity.getRole().equalsIgnoreCase("ADMIN")) {
            tabSheet.add("General", generalVerticalLayout);
            tabSheet.add("Logging", loggingVerticalLayout);
        }

        tabSheet.setSizeFull();

        final VerticalLayout pageVerticalLayout = new VerticalLayout();
        pageVerticalLayout.add(getTitle("Settings"));
        pageVerticalLayout.add(tabSheet);
        pageVerticalLayout.add(CommonWidgets.getFooter());
        pageVerticalLayout.setSizeFull();

        final HorizontalLayout pageHorizontalLayout = new HorizontalLayout();
        pageHorizontalLayout.add(pageVerticalLayout);
        pageHorizontalLayout.add(helpWindowVerticalLayout);
        pageHorizontalLayout.setSizeFull();

        setContent(pageHorizontalLayout);

    }

}

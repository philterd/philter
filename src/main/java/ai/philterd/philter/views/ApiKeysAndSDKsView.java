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
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.services.RequestIdGenerator;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.views.widgets.CommonWidgets;
import com.mongodb.client.MongoClient;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.H5;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.TabSheet;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Route(value = "api")
@PageTitle("Philter - API")
@PermitAll
public class ApiKeysAndSDKsView extends AbstractRestrictedView {

    private static final Logger LOGGER = LogManager.getLogger(ApiKeysAndSDKsView.class);

    @Override
    public String getHelpMarkdownText() {
        return "Placeholder for API help text.";
    }

    public ApiKeysAndSDKsView(final MongoClient mongoClient, final EncryptionService encryptionService, final AuditEventPublisher auditEventPublisher, final ApiKeyDataService apiKeyService) {

        super(mongoClient, encryptionService, auditEventPublisher, true);

        final UserEntity userEntity = getCurrentUser();
        final ApiKeyEntityDataProvider apiKeyDataProvider = new ApiKeyEntityDataProvider(userEntity.getId(), apiKeyService);

        final Grid<ApiKeyEntity> apiKeysGrid = new Grid<>(ApiKeyEntity.class, false);
        apiKeysGrid.addColumn(ApiKeyEntity::getApiKeyPrefix).setHeader("Key").setResizable(true).setSortable(true);
        apiKeysGrid.setDataProvider(apiKeyDataProvider);
        apiKeysGrid.setWidthFull();

        apiKeysGrid.addComponentColumn(apiKeyEntity -> {
            final Button deleteButton = new Button(VaadinIcon.TRASH.create());
            deleteButton.setTooltipText("Delete this API key");
            deleteButton.setText("Delete");
            deleteButton.addClickListener(event -> {

                final Dialog confirmDialog = new Dialog();
                confirmDialog.setWidth("450px");
                confirmDialog.add(new H3("Confirm Deletion"));
                confirmDialog.add(new Paragraph("Are you sure you want to delete the selected API key?"));

                final Button confirmButton = new Button("Delete", e -> {

                    final String requestId = RequestIdGenerator.generate();
                    apiKeyService.deleteByApiKey(requestId, apiKeyEntity, getClientIpAddress());

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

        // Button to create a new API key
        final Button createApiKeyButton = new Button("New API Key", VaadinIcon.PLUS.create());
        createApiKeyButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        createApiKeyButton.addClickListener(event -> {

            final String requestId = RequestIdGenerator.generate();
            final ServiceResponse serviceResponse = apiKeyService.createApiKey(requestId, userEntity.getId(), getClientIpAddress());

            if(serviceResponse.isSuccessful()) {

                final Dialog newApiKeyDialog = new Dialog("New API Key");
                newApiKeyDialog.add(new Paragraph("Please save your API key in a safe place since you won't be able to view it again. Keep it secure, as anyone with your API key can make requests on your behalf. If you lose it, you'll need to generate a new one."));

                final TextField apiKeyTextField = new TextField("API Key");
                apiKeyTextField.setValue(serviceResponse.getMessage());
                apiKeyTextField.setReadOnly(true);
                apiKeyTextField.setWidthFull();

//                final Button copyButton = new Button("Copy");
//
//                copyButton.addClickListener(e -> {
//                    // Write the text field's value to the clipboard
//                    Clipboard.copyToClipboard(apiKeyTextField.getValue());
//                    showSuccessNotification("API key copied to clipboard.");
//                });

                newApiKeyDialog.add(apiKeyTextField);
                //newApiKeyDialog.add(copyButton);

                final Button cancelButton = new Button("Close", e -> newApiKeyDialog.close());
                cancelButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

                newApiKeyDialog.setCloseOnOutsideClick(false);
                newApiKeyDialog.getFooter().add(cancelButton);
                newApiKeyDialog.setWidth("450px");
                newApiKeyDialog.open();

                apiKeysGrid.getDataProvider().refreshAll();

                // Not caching the new API key because we don't have super easy access to the ApiKeyEntity.
                // And because looking up the API key on the first response and caching it then.

                showSuccessNotification("New API key created.");

            }

        });

        final VerticalLayout apiKeysVerticalLayout = new VerticalLayout();
        apiKeysVerticalLayout.add(createApiKeyButton);
        apiKeysVerticalLayout.add(apiKeysGrid);
        apiKeysVerticalLayout.setSizeFull();

        final HorizontalLayout sdkRow = new HorizontalLayout();
        sdkRow.setWidthFull();
        sdkRow.add(createSdkItem("CLI", "Command Line", "https://github.com/philterd/philter-cli", "Filter text from the command line."));
        sdkRow.add(createSdkItem("SDK", "Java", "https://github.com/philterd/philter-sdk-java", "Filter text from your Java apps"));
        sdkRow.add(createSdkItem("SDK", ".NET", "https://github.com/philterd/philter-sdk-net", "Filter text from your .NET apps"));
        sdkRow.add(createSdkItem("SDK", "Golang", "https://github.com/philterd/philter-sdk-golang", "Filter text from your Golang apps"));

        final VerticalLayout sdksVerticalLayout = new VerticalLayout();
        sdksVerticalLayout.add(sdkRow);
        sdksVerticalLayout.setSizeFull();

        final TabSheet tabSheet = new TabSheet();
        tabSheet.add("API Keys", apiKeysVerticalLayout);
        tabSheet.add("Client SDKs", sdksVerticalLayout);
        tabSheet.setSizeFull();

        final VerticalLayout pageVerticalLayout = new VerticalLayout();
        pageVerticalLayout.add(getTitle(("API")));
        pageVerticalLayout.add(tabSheet);
        pageVerticalLayout.add(CommonWidgets.getFooter());
        pageVerticalLayout.setSizeFull();

        final HorizontalLayout pageHorizontalLayout = new HorizontalLayout();
        pageHorizontalLayout.add(pageVerticalLayout);
        pageHorizontalLayout.add(helpWindowVerticalLayout);
        pageHorizontalLayout.setSizeFull();

        setContent(pageHorizontalLayout);

    }

    private Component createSdkItem(final String type, final String name, final String url, final String description) {

        VerticalLayout item = new VerticalLayout();
        item.setPadding(false);
        item.setSpacing(false);

        Span typeSpan = new Span(type);
        typeSpan.getStyle().set("font-size", "0.7rem");
        typeSpan.getStyle().set("font-weight", "bold");
        typeSpan.getStyle().set("color", "#4e73df");

        H5 nameH5 = new H5(name);
        nameH5.getStyle().set("margin", "0");

        Anchor link = new Anchor(url, url);
        link.setTarget("_blank");
        link.getStyle().set("font-size", "0.8rem");

        Paragraph desc = new Paragraph(description);
        desc.getStyle().set("font-size", "0.8rem");

        item.add(typeSpan, nameH5, link, desc);

        return item;

    }

}

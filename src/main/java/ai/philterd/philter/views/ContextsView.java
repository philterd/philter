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
import ai.philterd.philter.data.entities.ContextEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.providers.ApiKeyEntityDataProvider;
import ai.philterd.philter.data.providers.ContextEntityDataProvider;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.ContextEntryDataService;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.views.widgets.CommonWidgets;
import com.mongodb.client.MongoClient;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H3;
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

import java.util.Map;

@Route(value = "contexts")
@PageTitle("Philter - Contexts")
@PermitAll
public class ContextsView extends AbstractRestrictedView {

    private static final Logger LOGGER = LogManager.getLogger(ContextsView.class);

    @Override
    public String getHelpMarkdownText() {

        return """
            ## Contexts
            
            Contexts allow for grouping documents during redaction and provides features such as referential integrity. When a document is redacted, it can be assigned to a context. If that context has been used previously, the same replacements are used for the same sensitive information, ensuring consistency across your documents.
            
            When the **coref** option is enabled for a context, anonymized PII is co-referenced in the documents. This ensures that names remain consistent even when only parts of the name are used. For example, if "John Smith" is anonymized as "Daniel Jones", then subsequent references to "John" will be consistently anonymized as "Daniel".
            
            When the **disambiguation** option is enabled for a context, entity types are disambiguated during redaction to provide more precise identification of sensitive information.
            
            ### Create a New Context
            
            To create a new context:
            
            1. Click **New Context**.
            2. Enter a unique **Context** name.
            3. Optionally, check **Enable coref** to enable co-referencing.
            4. Optionally, check **Enable disambiguation** to enable entity type disambiguation.
            5. Click **Save** to create the context.
            
            ### Edit a Context
            
            To edit a context:
            
            1. Click **Edit** in the row of the context you wish to edit.
            2. Toggle the **Enable coref** checkbox.
            3. Toggle the **Enable disambiguation** checkbox.
            4. Click **Save** to update the context.
            
            ### View Context Details
            
            To view a preview of the entries within a context:
            
            1. Click **View Context** in the row of the context you wish to view.
            2. A dialog will appear showing a preview of up to 20 context entries, including the token hash and its replacement.
            3. Click **Close** to return to the contexts list.
            
            ### Clear a Context
            
            To remove all entries from a context while keeping the context itself:
            
            1. Click **Clear** in the row of the context you wish to clear.
            2. Review the confirmation message, noting that this action cannot be undone.
            3. Click **Clear** to confirm.
            
            ### Delete a Context
            
            To permanently remove a context and all its entries:
            
            1. Click **Delete** in the row of the context you wish to remove.
            2. Review the confirmation message, noting that this action cannot be undone but will not affect already redacted documents.
            3. Click **Delete** to confirm the removal.
            
            Note: The **default** context cannot be deleted.
            """;

    }

    public ContextsView(final MongoClient mongoClient, final EncryptionService encryptionService, final AuditEventPublisher auditEventPublisher,
                        final ContextDataService contextService, final ContextEntryDataService contextEntryService) {
        super(mongoClient, encryptionService, auditEventPublisher, true);

        final UserEntity userEntity = getCurrentUser();

        final Grid<ContextEntity> grid = new Grid<>(ContextEntity.class, false);

        // Create the data provider
        final ContextEntityDataProvider dataProvider = new ContextEntityDataProvider(userEntity.getId(), contextService);
        grid.setDataProvider(dataProvider);

        // Button to create a new context.
        final Button createContextButton = new Button("New Context", VaadinIcon.DOCTOR_BRIEFCASE.create());
        createContextButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        createContextButton.getStyle().set("margin-left", "auto");
        createContextButton.setTooltipText("Create a new redaction context.");
        createContextButton.addClickShortcut(Key.ENTER);
        createContextButton.addClickListener(event -> {

            final TextField contextNameTextField = new TextField();
            contextNameTextField.setWidthFull();
            contextNameTextField.setLabel("Context");
            contextNameTextField.setPlaceholder("context");
            contextNameTextField.setRequired(true);
            contextNameTextField.setRequiredIndicatorVisible(true);

            final Checkbox corefCheckbox = new Checkbox();
            corefCheckbox.setLabel("Enable entity co-referencing for this context.");
            corefCheckbox.setValue(false);

            final Checkbox disambiguationCheckbox = new Checkbox();
            disambiguationCheckbox.setLabel("Enable entity type disambiguation for this context.");
            disambiguationCheckbox.setValue(false);

            final VerticalLayout contextVerticalLayout = new VerticalLayout();
            contextVerticalLayout.add(contextNameTextField);
            contextVerticalLayout.add(corefCheckbox);
            contextVerticalLayout.add(CommonWidgets.getLink("Learn more about entity co-referencing.", "https://docs.philterd.ai/redaction/contexts.html#co-referencing-coref", true));
            contextVerticalLayout.add(disambiguationCheckbox);
            contextVerticalLayout.add(CommonWidgets.getLink("Learn more about entity type disambiguation.", "https://docs.philterd.ai/redaction/contexts.html#disambiguation", true));

            final Dialog confirmDialog = new Dialog();
            confirmDialog.setWidth("500px");
            confirmDialog.setHeight("500px");
            confirmDialog.add(new H3("New Context"));
            confirmDialog.add(contextVerticalLayout);

            final Button confirmButton = new Button("Save", e -> {

                final String contextName = contextNameTextField.getValue();
                final boolean coref = corefCheckbox.getValue();
                final boolean disambiguation = disambiguationCheckbox.getValue();
                final ServiceResponse serviceResponse = contextService.create(contextName, userEntity.getId(), coref, disambiguation);

                if(serviceResponse.isSuccessful()) {

                    confirmDialog.close();
                    dataProvider.refreshAll();
                    showSuccessNotification("Context created.");

                } else {

                    showFailureNotification(serviceResponse.getMessage());
                    contextNameTextField.setInvalid(true);
                    contextNameTextField.setErrorMessage(serviceResponse.getMessage());

                }

            });

            confirmButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

            final Button cancelButton = new Button("Cancel", e -> confirmDialog.close());
            cancelButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

            confirmDialog.getFooter().add(cancelButton, confirmButton);
            confirmDialog.open();

        });

        final HorizontalLayout titleHorizontalLayout = new HorizontalLayout();
        titleHorizontalLayout.setWidthFull();
        titleHorizontalLayout.add(getTitle("Contexts"));

        grid.addColumn(ContextEntity::getContextName).setHeader("Context").setResizable(true).setSortable(true).setKey("context_name");
        grid.setSizeFull();

        grid.addComponentColumn(contextEntity -> {

            final Button viewContextButton = new Button(VaadinIcon.DOCTOR_BRIEFCASE.create());
            viewContextButton.setTooltipText("View context " + contextEntity.getContextName());
            viewContextButton.setText("View");
            viewContextButton.addClickListener(event -> {

                final Dialog viewContextDialog = new Dialog();
                viewContextDialog.setWidth("500px");
                viewContextDialog.setHeight("750px");

                final VerticalLayout verticalLayout = new VerticalLayout();
                verticalLayout.add(new H3("Context"));
                verticalLayout.add(new Paragraph("Filter type counts for context: " + contextEntity.getContextName()));

                // Get filter type counts instead of showing grid
                final Map<String, Long> filterTypeCounts = contextEntryService.getFilterTypeCounts(contextEntity.getContextName(), userEntity.getId());

                if(filterTypeCounts != null && !filterTypeCounts.isEmpty()) {

                    final Grid<Map.Entry<String, Long>> countsGrid = new Grid<>();
                    countsGrid.setItems(filterTypeCounts.entrySet());
                    countsGrid.addColumn(Map.Entry::getKey).setHeader("Filter Type").setSortable(true);
                    countsGrid.addColumn(Map.Entry::getValue).setHeader("Count").setSortable(true);
                    countsGrid.setHeight("500px");

                    verticalLayout.add(countsGrid);

                } else {

                    verticalLayout.add(new Paragraph("No entries found in this context."));

                }

                viewContextDialog.add(verticalLayout);

                final Button cancelButton = new Button("Close", e -> viewContextDialog.close());
                cancelButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

                viewContextDialog.getFooter().add(cancelButton);
                viewContextDialog.open();

            });

            return viewContextButton;

        }).setHeader("View").setAutoWidth(true).setFlexGrow(0);

        grid.addComponentColumn(contextEntity -> {

            final Button editContextButton = new Button(VaadinIcon.EDIT.create());
            editContextButton.setTooltipText("Edit context " + contextEntity.getContextName());
            editContextButton.addClickListener(event -> {

                final TextField contextNameTextField = new TextField();
                contextNameTextField.setWidthFull();
                contextNameTextField.setLabel("Context");
                contextNameTextField.setValue(contextEntity.getContextName());
                contextNameTextField.setReadOnly(true);

                final Checkbox corefCheckbox = new Checkbox();
                corefCheckbox.setLabel("Enable co-referencing for this context.");
                corefCheckbox.setValue(contextEntity.isCoref());

                final Checkbox disambiguationCheckbox = new Checkbox();
                disambiguationCheckbox.setLabel("Enable disambiguation for this context.");
                disambiguationCheckbox.setValue(contextEntity.isDisambiguation());

                final VerticalLayout contextVerticalLayout = new VerticalLayout();
                contextVerticalLayout.add(contextNameTextField);
                contextVerticalLayout.add(corefCheckbox);
                contextVerticalLayout.add(CommonWidgets.getLink("Learn more about entity co-referencing.", "https://docs.philterd.ai/redaction/contexts.html#co-referencing-coref", true));
                contextVerticalLayout.add(disambiguationCheckbox);
                contextVerticalLayout.add(CommonWidgets.getLink("Learn more about entity type disambiguation.", "https://docs.philterd.ai/redaction/contexts.html#disambiguation", true));

                final Dialog editDialog = new Dialog();
                editDialog.setWidth("500px");
                editDialog.setHeight("500px");
                editDialog.add(new H3("Edit Context"));
                editDialog.add(contextVerticalLayout);

                final Button saveButton = new Button("Save", e -> {

                    final boolean coref = corefCheckbox.getValue();
                    final boolean disambiguation = disambiguationCheckbox.getValue();

                    contextEntity.setCoref(coref);
                    contextEntity.setDisambiguation(disambiguation);
                    contextService.update(contextEntity);

                    editDialog.close();
                    dataProvider.refreshAll();
                    showSuccessNotification("Context updated.");

                });
                saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

                final Button cancelButton = new Button("Cancel", e -> editDialog.close());
                cancelButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

                editDialog.getFooter().add(cancelButton, saveButton);
                editDialog.open();

            });

            return editContextButton;

        }).setHeader("Edit").setAutoWidth(true).setFlexGrow(0);

        grid.addComponentColumn(contextEntity -> {

            final Button clearContextButton = new Button(VaadinIcon.RECYCLE.create());
            clearContextButton.setTooltipText("Clear context " + contextEntity.getContextName());
            //clearContextButton.setText("Clear");
            clearContextButton.addClickListener(event -> {

                final Dialog confirmDialog = new Dialog();
                confirmDialog.add(new H3("Confirm Clear"));
                confirmDialog.add(new Paragraph("Are you sure you want to clear the " + contextEntity.getContextName() + " context? This will remove all entries from the context and this cannot be undone."));

                final Button confirmButton = new Button("Clear", e -> {

                    final ServiceResponse serviceResponse = contextService.emptyByName(contextEntity.getContextName(), userEntity.getId());

                    if(serviceResponse.isSuccessful()) {

                        showSuccessNotification(serviceResponse.getMessage());
                        confirmDialog.close();

                    } else {

                        showFailureNotification(serviceResponse.getMessage());

                    }

                });
                confirmButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR);

                final Button cancelButton = new Button("Cancel", e -> confirmDialog.close());

                confirmDialog.getFooter().add(cancelButton, confirmButton);
                confirmDialog.open();
            });

            return clearContextButton;

        }).setHeader("Clear").setAutoWidth(true).setFlexGrow(0);

        grid.addComponentColumn(contextEntity -> {

            if(contextEntity.getContextName().equalsIgnoreCase("default")) {
                return null;
            } else {

                final Button deleteContextButton = new Button(VaadinIcon.TRASH.create());
                deleteContextButton.setTooltipText("Delete context " + contextEntity.getContextName());
                //deleteContextButton.setText("Delete");
                deleteContextButton.addClickListener(event -> {

                    final Dialog confirmDialog = new Dialog();
                    confirmDialog.add(new H3("Confirm Deletion"));
                    confirmDialog.add(new Paragraph("Are you sure you want to delete the context " + contextEntity.getContextName() + "? This cannot be undone."));

                    final Button confirmButton = new Button("Delete", e -> {

                        final ServiceResponse serviceResponse = contextService.deleteByName(contextEntity.getContextName(), userEntity.getId());

                        if(serviceResponse.isSuccessful()) {

                            dataProvider.refreshAll();

                            showSuccessNotification(serviceResponse.getMessage());
                            confirmDialog.close();

                        } else {
                            showFailureNotification(serviceResponse.getMessage());
                        }

                    });
                    confirmButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR);

                    final Button cancelButton = new Button("Cancel", e -> confirmDialog.close());

                    confirmDialog.getFooter().add(cancelButton, confirmButton);
                    confirmDialog.open();
                });

                return deleteContextButton;

            }

        }).setHeader("Delete").setAutoWidth(true).setFlexGrow(0);

        final Span label = new Span("Contexts allow for grouping documents during redaction and provides features such as referential integrity.");

        final VerticalLayout contextsVerticalLayout = new VerticalLayout();
        contextsVerticalLayout.setSizeFull();
        contextsVerticalLayout.add(label);
        contextsVerticalLayout.add(grid);

        final TabSheet tabSheet = new  TabSheet();
        tabSheet.add("My Contexts", contextsVerticalLayout);
        tabSheet.setSizeFull();

        final HorizontalLayout suffixHorizontalLayout = new HorizontalLayout();
        suffixHorizontalLayout.add(createContextButton);
        tabSheet.setSuffixComponent(suffixHorizontalLayout);

        final VerticalLayout div = new VerticalLayout();
        div.add(titleHorizontalLayout);
        div.add(tabSheet);
        div.add(CommonWidgets.getFooter());
        div.setSizeFull();

        final HorizontalLayout pageHorizontalLayout = new HorizontalLayout();
        pageHorizontalLayout.add(div);
        pageHorizontalLayout.add(helpWindowVerticalLayout);
        pageHorizontalLayout.setSizeFull();

        setContent(pageHorizontalLayout);

    }

}

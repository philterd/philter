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
import ai.philterd.philter.data.entities.CustomListEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.providers.CustomListEntityDataProvider;
import ai.philterd.philter.data.services.CustomListDataService;
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
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Route(value = "lists")
@PageTitle("Philter - Custom Lists")
public class CustomListsView extends AbstractRestrictedView {

    private static final Logger LOGGER = LogManager.getLogger(CustomListsView.class);

    @Override
    public String getHelpMarkdownText() {
        return "Placeholder for custom lists help text.";
    }

    public CustomListsView(final MongoClient mongoClient, final EncryptionService encryptionService, final AuditEventPublisher auditEventPublisher,
                           final CustomListDataService customListService, final CustomListEntityDataProvider dataProvider) {
        super(mongoClient, encryptionService, auditEventPublisher, true);

        final UserEntity userEntity = getCurrentUser();

        final Grid<CustomListEntity> grid = new Grid<>(CustomListEntity.class, false);
        grid.addColumn(CustomListEntity::getName).setHeader("Name").setResizable(true).setSortable(true).setSortProperty("name");
        grid.addColumn(CustomListEntity::getDescription).setHeader("Description").setResizable(true).setSortable(true).setSortProperty("description");
        grid.addColumn(customList -> customList.getItems() != null ? customList.getItems().size() : 0)
                .setHeader("Terms")
                .setResizable(true)
                .setSortable(true)
                .setComparator((list1, list2) -> {
                    final int size1 = list1.getItems() != null ? list1.getItems().size() : 0;
                    final int size2 = list2.getItems() != null ? list2.getItems().size() : 0;
                    return Integer.compare(size1, size2);
                });

        grid.setDataProvider(dataProvider);
        grid.setSizeFull();

        grid.addComponentColumn(originalListEntity -> {

            // Get the most up-to-date list object from the database.
            final CustomListEntity listEntity = customListService.findOneById(originalListEntity.getId(), userEntity.getId());

            final Button editListButton = new Button(VaadinIcon.EDIT.create());
            editListButton.setTooltipText("Edit list");
            //editListButton.setText("Edit");
            editListButton.addClickListener(event -> {

                final TextField listNameTextField = new TextField();
                listNameTextField.setWidthFull();
                listNameTextField.setLabel("List Name");
                listNameTextField.setPlaceholder("list name");
                listNameTextField.setRequired(true);
                listNameTextField.setRequiredIndicatorVisible(true);
                listNameTextField.setValue(listEntity.getName());
                listNameTextField.setReadOnly(true);

                final TextField descriptionTextField = new TextField();
                descriptionTextField.setWidthFull();
                descriptionTextField.setLabel("Description");
                descriptionTextField.setPlaceholder("Optional short description");
                descriptionTextField.setMaxLength(250);
                descriptionTextField.setHelperText("Maximum 250 characters");
                if (listEntity.getDescription() != null) {
                    descriptionTextField.setValue(listEntity.getDescription());
                }

                final TextArea listTextArea = new TextArea();
                listTextArea.setWidthFull();
                listTextArea.setLabel("List Items (one per line)");
                listTextArea.setRequired(true);
                listTextArea.setRequiredIndicatorVisible(true);
                listTextArea.setMaxRows(25);
                listTextArea.setMinRows(10);
                listTextArea.setHeightFull();
                listTextArea.setHelperText("Each item must be 50 characters or less");
                listTextArea.setValue(String.join("\n", listEntity.getItems()));

                final Span errorSpan = new Span();
                errorSpan.getStyle().set("color", "red");
                errorSpan.setVisible(false);

                final Dialog confirmDialog = new Dialog();
                confirmDialog.setMinWidth("500px");
                confirmDialog.setMaxWidth("500px");
                confirmDialog.setMinHeight("600px");
                confirmDialog.setMaxHeight("600px");
                confirmDialog.add(new H3("Edit List"));
                confirmDialog.add(errorSpan);
                confirmDialog.add(listNameTextField);
                confirmDialog.add(descriptionTextField);
                confirmDialog.add(listTextArea);

                final Button confirmButton = new Button("Save", e -> {

                    final String requestId = RequestIdGenerator.generate();

                    final List<String> listItems = new ArrayList<>(Arrays.asList(listTextArea.getValue().split("\n")));

                    final ServiceResponse serviceResponse = customListService.saveOrUpdate(requestId, userEntity.getId(), listEntity.getName(), descriptionTextField.getValue(), listItems, false, Source.WEBUI.getSource());

                    if(serviceResponse.isSuccessful()) {

                        confirmDialog.close();

                        dataProvider.refreshAll();
                        showSuccessNotification("List updated");

                    } else {

                        errorSpan.setText(serviceResponse.getMessage());
                        errorSpan.setVisible(true);

                    }

                });
                confirmButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR);

                final Button cancelButton = new Button("Cancel", e -> confirmDialog.close());

                confirmDialog.getFooter().add(cancelButton, confirmButton);
                confirmDialog.open();

            });

            return editListButton;

        }).setHeader("Edit List").setAutoWidth(true).setFlexGrow(0);

        grid.addComponentColumn(listEntity -> {

            final Button deleteListButton = new Button(VaadinIcon.TRASH.create());
            deleteListButton.setTooltipText("Delete custom list " + listEntity.getName());
            //deleteListButton.setText("Delete");
            deleteListButton.addClickListener(event -> {

                final Dialog confirmDialog = new Dialog();
                confirmDialog.add(new H3("Confirm Deletion"));
                confirmDialog.add(new Paragraph("Are you sure you want to delete the list " + listEntity.getName() + "? This cannot be undone."));

                final Button confirmButton = new Button("Delete", e -> {

                    customListService.deleteByName(listEntity.getName(), userEntity.getId());
                    confirmDialog.close();

                    dataProvider.refreshAll();
                    showSuccessNotification("List deleted.");

                });
                confirmButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR);

                final Button cancelButton = new Button("Cancel", e -> confirmDialog.close());

                confirmDialog.getFooter().add(cancelButton, confirmButton);
                confirmDialog.open();
            });

            return deleteListButton;

        }).setHeader("Delete").setAutoWidth(true).setFlexGrow(0);

        // Button to create a new list
        final Button createListButton = new Button("New Custom List", VaadinIcon.LIST.create());
        createListButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        createListButton.getStyle().set("margin-left", "auto");
        createListButton.setTooltipText("Create a new custom list.");
        createListButton.addClickListener(event -> {

            final TextField listNameTextField = new TextField();
            listNameTextField.setWidthFull();
            listNameTextField.setLabel("List Name");
            listNameTextField.setPlaceholder("list name");
            listNameTextField.setRequired(true);
            listNameTextField.setRequiredIndicatorVisible(true);

            final TextField descriptionTextField = new TextField();
            descriptionTextField.setWidthFull();
            descriptionTextField.setLabel("Description");
            descriptionTextField.setPlaceholder("Optional short description");
            descriptionTextField.setMaxLength(250);
            descriptionTextField.setHelperText("Maximum 250 characters");

            final TextArea listTextArea = new TextArea();
            listTextArea.setWidthFull();
            listTextArea.setLabel("List Items (one per line)");
            listTextArea.setRequired(true);
            listTextArea.setRequiredIndicatorVisible(true);
            listTextArea.setMaxRows(15);
            listTextArea.setMinRows(10);
            listTextArea.setHeightFull();
            listTextArea.setHelperText("Each item must be 50 characters or less");

            final Span errorSpan = new Span();
            errorSpan.getStyle().set("color", "red");
            errorSpan.setVisible(false);

            final Dialog confirmDialog = new Dialog();
            confirmDialog.setMinWidth("500px");
            confirmDialog.setMaxWidth("500px");
            confirmDialog.setMinHeight("700px");
            confirmDialog.setMaxHeight("700px");
            confirmDialog.add(new H3("Create List"));
            confirmDialog.add(errorSpan);
            confirmDialog.add(listNameTextField);
            confirmDialog.add(descriptionTextField);
            confirmDialog.add(listTextArea);

            final Button confirmButton = new Button("Save", e -> {

                final String requestId = RequestIdGenerator.generate();

                final String listName = listNameTextField.getValue();
                final String description = descriptionTextField.getValue();
                final List<String> listItems = new ArrayList<>(Arrays.asList(listTextArea.getValue().split("\n")));

                final ServiceResponse serviceResponse = customListService.saveOrUpdate(requestId, userEntity.getId(), listName, description, listItems, false, Source.WEBUI.getSource());

                if(serviceResponse.isSuccessful()) {

                    dataProvider.refreshAll();
                    confirmDialog.close();
                    showSuccessNotification("List created.");

                } else {

                    errorSpan.setText(serviceResponse.getMessage());
                    errorSpan.setVisible(true);

                }

            });

            confirmButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR);

            final Button cancelButton = new Button("Cancel", e -> confirmDialog.close());
            cancelButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

            confirmDialog.getFooter().add(cancelButton, confirmButton);
            confirmDialog.open();

        });

        final Span label = new Span("Custom lists can be referenced by policies to include a list of terms to always or never redact.");

        final HorizontalLayout titleHorizontalLayout = new HorizontalLayout();
        titleHorizontalLayout.setWidthFull();
        titleHorizontalLayout.add(getTitle("Custom Lists"));

        final VerticalLayout contextsVerticalLayout = new VerticalLayout();
        contextsVerticalLayout.setSizeFull();
        contextsVerticalLayout.add(label);
        contextsVerticalLayout.add(grid);

        final TabSheet tabSheet = new  TabSheet();
        tabSheet.add("My Custom Lists", contextsVerticalLayout);
        tabSheet.setSizeFull();

        final HorizontalLayout suffixHorizontalLayout = new HorizontalLayout();
        suffixHorizontalLayout.add(createListButton);
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

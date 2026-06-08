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
import ai.philterd.philter.data.providers.ContextEntityDataProvider;
import ai.philterd.philter.config.AdminAccessConfig;
import org.bson.types.ObjectId;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Route(value = "contexts")
@PageTitle("Philter - Contexts")
@PermitAll
public class ContextsView extends AbstractRestrictedView {

    private static final Logger LOGGER = LogManager.getLogger(ContextsView.class);


    public ContextsView(final MongoClient mongoClient, final EncryptionService encryptionService, final AuditEventPublisher auditEventPublisher,
                        final ContextDataService contextService, final ContextEntryDataService contextEntryService) {
        super(mongoClient, encryptionService, auditEventPublisher);

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

            final Checkbox disambiguationCheckbox = new Checkbox();
            disambiguationCheckbox.setLabel("Enable entity type disambiguation for this context.");
            disambiguationCheckbox.setValue(false);

            final Checkbox ledgerCheckbox = new Checkbox();
            ledgerCheckbox.setLabel("Enable the redaction ledger for this context.");
            ledgerCheckbox.setValue(false);

            final VerticalLayout contextVerticalLayout = new VerticalLayout();
            contextVerticalLayout.add(contextNameTextField);
            contextVerticalLayout.add(disambiguationCheckbox);
            contextVerticalLayout.add(CommonWidgets.getLink("Learn more about entity type disambiguation.", "/public/docs/redaction/contexts.html", true));
            contextVerticalLayout.add(ledgerCheckbox);
            contextVerticalLayout.add(CommonWidgets.getLink("Learn more about the redaction ledger.", "/public/docs/redaction/ledgers.html", true));

            final Dialog confirmDialog = new Dialog();
            confirmDialog.setWidth("500px");
            confirmDialog.setHeight("500px");
            confirmDialog.add(new H3("New Context"));
            confirmDialog.add(contextVerticalLayout);

            final Button confirmButton = new Button("Save", e -> {

                final String contextName = contextNameTextField.getValue();
                final boolean disambiguation = disambiguationCheckbox.getValue();
                final boolean ledger = ledgerCheckbox.getValue();
                final ServiceResponse serviceResponse = contextService.create(contextName, userEntity.getId(), disambiguation, ledger);

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
            viewContextButton.addClickListener(event ->
                    openContextCountsDialog(contextEntryService, contextEntity.getContextName(), userEntity.getId()));

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

                final Checkbox disambiguationCheckbox = new Checkbox();
                disambiguationCheckbox.setLabel("Enable disambiguation for this context.");
                disambiguationCheckbox.setValue(contextEntity.isDisambiguation());

                final Checkbox ledgerCheckbox = new Checkbox();
                ledgerCheckbox.setLabel("Enable the redaction ledger for this context.");
                ledgerCheckbox.setValue(contextEntity.isLedger());

                final VerticalLayout contextVerticalLayout = new VerticalLayout();
                contextVerticalLayout.add(contextNameTextField);
                contextVerticalLayout.add(disambiguationCheckbox);
                contextVerticalLayout.add(CommonWidgets.getLink("Learn more about entity type disambiguation.", "/public/docs/redaction/contexts.html", true));
                contextVerticalLayout.add(ledgerCheckbox);
                contextVerticalLayout.add(CommonWidgets.getLink("Learn more about the redaction ledger.", "/public/docs/redaction/ledgers.html", true));

                final Dialog editDialog = new Dialog();
                editDialog.setWidth("500px");
                editDialog.setHeight("500px");
                editDialog.add(new H3("Edit Context"));
                editDialog.add(contextVerticalLayout);

                final Button saveButton = new Button("Save", e -> {

                    final boolean disambiguation = disambiguationCheckbox.getValue();
                    final boolean ledger = ledgerCheckbox.getValue();

                    contextEntity.setDisambiguation(disambiguation);
                    contextEntity.setLedger(ledger);
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

                final Button deleteContextButton = new Button(VaadinIcon.TRASH.create());
                deleteContextButton.setTooltipText("Delete context " + contextEntity.getContextName());
                deleteContextButton.addClickListener(event -> {

                    final Dialog confirmDialog = new Dialog();
                    confirmDialog.add(new H3("Confirm Deletion"));
                    confirmDialog.add(new Paragraph("Are you sure you want to delete the context " + contextEntity.getContextName() + "? This cannot be undone."));

                    final Button confirmButton = new Button("Delete", e -> {

                        final ServiceResponse serviceResponse = contextService.deleteByName(contextEntity.getContextName(), userEntity.getId(), isAdmin());

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

        }).setHeader("Delete").setAutoWidth(true).setFlexGrow(0);

        final Span label = new Span("Contexts allow for grouping documents during redaction and provides features such as referential integrity.");

        final VerticalLayout contextsVerticalLayout = new VerticalLayout();
        contextsVerticalLayout.setSizeFull();
        contextsVerticalLayout.add(label);
        contextsVerticalLayout.add(grid);

        final TabSheet tabSheet = new  TabSheet();
        tabSheet.add("My Contexts", contextsVerticalLayout);

        // Admins get an additional read-only view of every user's contexts.
        if (isAdmin() && AdminAccessConfig.isCrossUserAccessEnabled()) {
            tabSheet.add("All Contexts", buildAllContextsLayout(contextService, contextEntryService));
        }

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
        pageHorizontalLayout.setSizeFull();

        setContent(pageHorizontalLayout);

    }

    /** The page size for the admin "All Contexts" lazy listing. */
    private static final int ALL_CONTEXTS_PAGE_SIZE = 25;

    /** Builds the admin-only "All Contexts" tab: every user's contexts with the owner's email, paged. */
    private VerticalLayout buildAllContextsLayout(final ContextDataService contextService, final ContextEntryDataService contextEntryService) {

        final Grid<AllContextRow> grid = new Grid<>();
        grid.setPageSize(ALL_CONTEXTS_PAGE_SIZE);
        grid.addColumn(AllContextRow::name).setHeader("Context").setResizable(true);
        grid.addColumn(AllContextRow::owner).setHeader("Owner").setResizable(true);

        // A View button identical to the one on the "My Contexts" tab, scoped to the row's owner.
        grid.addComponentColumn(row -> {
            final Button viewContextButton = new Button(VaadinIcon.DOCTOR_BRIEFCASE.create());
            viewContextButton.setTooltipText("View context " + row.name());
            viewContextButton.setText("View");
            viewContextButton.addClickListener(event ->
                    openContextCountsDialog(contextEntryService, row.name(), row.ownerId()));
            return viewContextButton;
        }).setHeader("View").setAutoWidth(true).setFlexGrow(0);

        grid.setSizeFull();

        // Lazy paging: one page (offset/limit) at a time plus the total count for the scrollbar. Owner
        // emails for the page are resolved in a single batched lookup rather than one query per row.
        grid.setItems(
                query -> {
                    final List<ContextEntity> page = contextService.findAllAcrossUsers(query.getOffset(), query.getLimit());
                    final Map<ObjectId, String> ownerEmails = userService.findEmailsByIds(
                            page.stream().map(ContextEntity::getUserId).collect(Collectors.toSet()));
                    return page.stream().map(contextEntity -> toAllContextRow(contextEntity, ownerEmails));
                },
                query -> contextService.countAllAcrossUsers());

        final VerticalLayout layout = new VerticalLayout();
        layout.setSizeFull();
        layout.add(new Span("All contexts across all users."));
        layout.add(grid);
        return layout;

    }

    /** Maps a context to an "All Contexts" row, resolving the owner's email from the prefetched map. */
    private AllContextRow toAllContextRow(final ContextEntity contextEntity, final Map<ObjectId, String> ownerEmails) {
        final String email = ownerEmails.getOrDefault(contextEntity.getUserId(), "(unknown)");
        return new AllContextRow(contextEntity.getContextName(), email, contextEntity.getUserId());
    }

    /**
     * Opens the "Filter type counts" dialog for a context. Shared by the "My Contexts" and admin
     * "All Contexts" tabs so both show the identical dialog; the counts are scoped to {@code ownerId}.
     */
    private void openContextCountsDialog(final ContextEntryDataService contextEntryService, final String contextName, final ObjectId ownerId) {

        final Dialog viewContextDialog = new Dialog();
        viewContextDialog.setWidth("500px");
        viewContextDialog.setHeight("750px");

        final VerticalLayout verticalLayout = new VerticalLayout();
        verticalLayout.add(new H3("Context"));
        verticalLayout.add(new Paragraph("Filter type counts for context: " + contextName));

        final Map<String, Long> filterTypeCounts = contextEntryService.getFilterTypeCounts(contextName, ownerId);

        if (filterTypeCounts != null && !filterTypeCounts.isEmpty()) {

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

    }

    /** A row in the admin "All Contexts" table: the context name, the owner's email, and the owner's id. */
    private record AllContextRow(String name, String owner, ObjectId ownerId) {}

}

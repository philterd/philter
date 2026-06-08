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
import ai.philterd.philter.api.responses.LedgerEntryView;
import ai.philterd.philter.config.AdminAccessConfig;
import ai.philterd.philter.api.responses.LedgerExport;
import ai.philterd.philter.data.entities.LedgerEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.services.LedgerDataService;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.model.Source;
import ai.philterd.philter.services.RequestIdGenerator;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.views.widgets.CommonWidgets;
import com.google.gson.Gson;
import com.mongodb.client.MongoClient;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.TabSheet;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.StreamResource;
import jakarta.annotation.security.PermitAll;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Route(value = "ledger")
@PageTitle("Philter - Redaction Ledger")
@PermitAll
public class LedgerView extends AbstractRestrictedView {

    private static final Logger LOGGER = LogManager.getLogger(LedgerView.class);

    private static final Gson GSON = new Gson();

    private static final int PAGE_SIZE = 25;

    private final LedgerDataService ledgerService;
    private final UserEntity currentUser;
    private final Grid<LedgerEntity> grid = new Grid<>(LedgerEntity.class, false);

    /** The current "My Ledger" search term, or null/blank for the full (paged) listing. */
    private String searchTerm;

    public LedgerView(final MongoClient mongoClient, final EncryptionService encryptionService,
                      final AuditEventPublisher auditEventPublisher, final LedgerDataService ledgerService) {
        super(mongoClient, encryptionService, auditEventPublisher);

        this.ledgerService = ledgerService;
        this.currentUser = getCurrentUser();

        grid.addColumn(LedgerEntity::getDocumentId).setHeader("Document ID").setResizable(true).setAutoWidth(true);
        grid.addColumn(LedgerEntity::getFilename).setHeader("Filename").setResizable(true).setSortable(true);
        grid.addColumn(LedgerEntity::getTimestamp).setHeader("Created").setResizable(true).setSortable(true);
        grid.setSizeFull();

        grid.addComponentColumn(this::createViewButton).setHeader("View").setAutoWidth(true).setFlexGrow(0);
        grid.addComponentColumn(this::createDeleteButton).setHeader("Delete").setAutoWidth(true).setFlexGrow(0);

        // Lazy paging: fetch one page (offset/limit) at a time plus the total count, honoring the
        // current search term.
        grid.setPageSize(PAGE_SIZE);
        grid.setItems(
                query -> fetchMyLedger(query.getOffset(), query.getLimit()).stream(),
                query -> countMyLedger());

        // Search by document id or filename. Updating the term re-runs the lazy query.
        final TextField searchField = new TextField();
        searchField.setPlaceholder("Search by document id or filename");
        searchField.setClearButtonVisible(true);
        searchField.setWidth("320px");
        searchField.addValueChangeListener(e -> {
            searchTerm = e.getValue();
            grid.getDataProvider().refreshAll();
        });

        // Manual purge: the ledger is kept indefinitely by default, so this is how stale entries are pruned.
        final Button purgeButton = new Button("Purge old entries", VaadinIcon.TRASH.create());
        purgeButton.addThemeVariants(ButtonVariant.LUMO_ERROR);
        purgeButton.setTooltipText("Delete ledger entries older than a number of days.");
        purgeButton.addClickListener(e -> openPurgeDialog());

        final HorizontalLayout controls = new HorizontalLayout(searchField, purgeButton);
        controls.setWidthFull();
        controls.setAlignItems(HorizontalLayout.Alignment.END);
        controls.expand(searchField);

        final Span label = new Span("The redaction ledger is a tamper-evident, hash-chained record of redactions made in "
                + "contexts that have the ledger enabled. Entries are kept indefinitely unless you purge them.");

        final VerticalLayout ledgerLayout = new VerticalLayout();
        ledgerLayout.setSizeFull();
        ledgerLayout.add(label, controls, grid);

        final TabSheet tabSheet = new TabSheet();
        tabSheet.add("My Ledger", ledgerLayout);

        // Admins get an additional read-only view of every user's ledger chains.
        if (isAdmin() && AdminAccessConfig.isCrossUserAccessEnabled()) {
            tabSheet.add("All Ledger", buildAllLedgerLayout());
        }

        tabSheet.setSizeFull();

        final VerticalLayout div = new VerticalLayout();
        div.add(getTitle("Redaction Ledger"));
        div.add(tabSheet);
        div.add(CommonWidgets.getFooter());
        div.setSizeFull();

        final HorizontalLayout pageHorizontalLayout = new HorizontalLayout();
        pageHorizontalLayout.add(div);
        pageHorizontalLayout.setSizeFull();

        setContent(pageHorizontalLayout);

    }

    /** Fetches one page of the caller's chain heads, applying the current search term if any. */
    private List<LedgerEntity> fetchMyLedger(final int offset, final int limit) {
        final String requestId = RequestIdGenerator.generate();
        if (searchTerm == null || searchTerm.isBlank()) {
            return ledgerService.findChainsByUserId(requestId, currentUser.getId(), offset, limit, Source.WEBUI.getSource());
        }
        // Search is not paged in the data layer, so page the matches in memory.
        return ledgerService.searchChainsByUserId(requestId, currentUser.getId(), searchTerm, Source.WEBUI.getSource())
                .stream().skip(offset).limit(limit).toList();
    }

    /** Counts the caller's chain heads (matching the current search term, if any). */
    private int countMyLedger() {
        if (searchTerm == null || searchTerm.isBlank()) {
            return ledgerService.countChainsByUserId(currentUser.getId());
        }
        return ledgerService.searchChainsByUserId(RequestIdGenerator.generate(), currentUser.getId(),
                searchTerm, Source.WEBUI.getSource()).size();
    }

    private Button createViewButton(final LedgerEntity chainHead) {
        final Button viewButton = new Button("View", VaadinIcon.EYE.create());
        viewButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        viewButton.addClickListener(e -> openChainDialog(chainHead.getDocumentId()));
        return viewButton;
    }

    private void openChainDialog(final String documentId) {

        final List<LedgerEntity> chain = ledgerService.getChain(currentUser.getId(), documentId);

        boolean valid;
        try {
            valid = ledgerService.isChainValid(currentUser.getId(), documentId);
        } catch (final Exception ex) {
            LOGGER.warn("Unable to validate ledger chain for document {}", documentId, ex);
            valid = false;
        }

        final Dialog dialog = new Dialog();
        dialog.setWidth("800px");
        dialog.setHeight("600px");
        dialog.add(new H3("Ledger Chain"));
        dialog.add(new Paragraph("Document ID: " + documentId));

        final Span validityBadge = new Span(valid ? "Chain verified" : "Chain INVALID");
        validityBadge.getElement().getThemeList().add("badge " + (valid ? "success" : "error"));
        dialog.add(validityBadge);

        final Grid<LedgerEntity> entriesGrid = new Grid<>(LedgerEntity.class, false);
        entriesGrid.addColumn(LedgerEntity::getType).setHeader("Type").setAutoWidth(true);
        entriesGrid.addColumn(LedgerEntity::getReplacement).setHeader("Replacement").setAutoWidth(true);
        entriesGrid.addColumn(LedgerEntity::getStartPosition).setHeader("Position").setAutoWidth(true);
        entriesGrid.addColumn(LedgerEntity::getTimestamp).setHeader("Timestamp").setAutoWidth(true);
        entriesGrid.setItems(chain);
        entriesGrid.setSizeFull();
        dialog.add(entriesGrid);

        // Export the chain as a downloadable JSON document.
        final Anchor exportAnchor = new Anchor(exportResource(documentId, chain), "Export (JSON)");
        exportAnchor.getElement().setAttribute("download", true);

        final Button closeButton = new Button("Close", e -> dialog.close());
        closeButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        dialog.getFooter().add(exportAnchor, closeButton);
        dialog.open();
    }

    /** Builds a downloadable JSON export ({@link LedgerExport}) of a document's chain. */
    private StreamResource exportResource(final String documentId, final List<LedgerEntity> chain) {
        final List<LedgerEntryView> entries = new ArrayList<>(chain.size());
        for (final LedgerEntity entry : chain) {
            entries.add(new LedgerEntryView(entry.getDocumentId(), entry.getFilename(), entry.getType(),
                    entry.getToken(), entry.getReplacement(), entry.getStartPosition(), entry.getDocumentHash(),
                    entry.getPreviousHash(), entry.getHash(), entry.getTimestamp()));
        }
        final byte[] json = GSON.toJson(new LedgerExport(documentId, entries)).getBytes(StandardCharsets.UTF_8);
        return new StreamResource("ledger-" + documentId + "-export.json", () -> new ByteArrayInputStream(json));
    }

    private Button createDeleteButton(final LedgerEntity chainHead) {
        final Button deleteButton = new Button(VaadinIcon.TRASH.create());
        deleteButton.setTooltipText("Delete this document's ledger chain.");
        deleteButton.addClickListener(e -> {

            final Dialog confirmDialog = new Dialog();
            confirmDialog.add(new H3("Confirm Deletion"));
            confirmDialog.add(new Paragraph("Delete the ledger chain for document " + chainHead.getDocumentId()
                    + "? This permanently removes its entries and cannot be undone."));

            final Button confirmButton = new Button("Delete", ev -> {
                ledgerService.deleteByDocumentId(RequestIdGenerator.generate(), currentUser.getId(),
                        chainHead.getDocumentId(), Source.WEBUI.getSource());
                confirmDialog.close();
                grid.getDataProvider().refreshAll();
                showSuccessNotification("Ledger chain deleted.");
            });
            confirmButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR);

            final Button cancelButton = new Button("Cancel", ev -> confirmDialog.close());

            confirmDialog.getFooter().add(cancelButton, confirmButton);
            confirmDialog.open();
        });
        return deleteButton;
    }

    private void openPurgeDialog() {

        final IntegerField daysField = new IntegerField("Delete entries older than (days)");
        daysField.setMin(0);
        daysField.setValue(90);
        daysField.setStepButtonsVisible(true);
        daysField.setWidthFull();

        final Dialog dialog = new Dialog();
        dialog.setWidth("420px");
        dialog.add(new H3("Purge Old Ledger Entries"));
        dialog.add(new Paragraph("Permanently delete your ledger entries older than the given number of days. "
                + "This cannot be undone."));
        dialog.add(daysField);

        final Button confirmButton = new Button("Purge", e -> {
            final Integer days = daysField.getValue();
            if (days == null || days < 0) {
                daysField.setInvalid(true);
                daysField.setErrorMessage("Enter zero or more days.");
                return;
            }
            final long deleted = ledgerService.deleteChainsByUserIdAndOlderThan(
                    RequestIdGenerator.generate(), currentUser.getId(), days);
            dialog.close();
            grid.getDataProvider().refreshAll();
            showSuccessNotification("Deleted " + deleted + " ledger entries older than " + days + " days.");
        });
        confirmButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR);

        final Button cancelButton = new Button("Cancel", e -> dialog.close());

        dialog.getFooter().add(cancelButton, confirmButton);
        dialog.open();
    }

    /** Builds the admin-only "All Ledger" tab: every user's chains with the owner's email, paged. */
    private VerticalLayout buildAllLedgerLayout() {

        final Grid<AllLedgerRow> allGrid = new Grid<>();
        allGrid.setPageSize(PAGE_SIZE);
        allGrid.addColumn(AllLedgerRow::documentId).setHeader("Document ID").setResizable(true).setAutoWidth(true);
        allGrid.addColumn(AllLedgerRow::owner).setHeader("Owner").setResizable(true);
        allGrid.addColumn(AllLedgerRow::created).setHeader("Created").setResizable(true);
        allGrid.setSizeFull();

        // Lazy paging: one page (offset/limit) at a time plus the total count for the scrollbar.
        allGrid.setItems(
                query -> ledgerService.findAllChainHeadsAcrossUsers(query.getOffset(), query.getLimit()).stream()
                        .map(this::toAllLedgerRow),
                query -> ledgerService.countAllChainHeads());

        final VerticalLayout layout = new VerticalLayout();
        layout.setSizeFull();
        layout.add(new Span("All redaction-ledger chains across all users."), allGrid);
        return layout;

    }

    /** Maps a chain head to an "All Ledger" row, resolving the owner's email. */
    private AllLedgerRow toAllLedgerRow(final LedgerEntity head) {
        final UserEntity owner = userService.findOneById(head.getUserId());
        return new AllLedgerRow(head.getDocumentId(),
                owner != null ? owner.getEmail() : "(unknown)", head.getTimestamp());
    }

    /** A row in the admin "All Ledger" table: the document id, the owner's email, and the created time. */
    private record AllLedgerRow(String documentId, String owner, Date created) {}

}

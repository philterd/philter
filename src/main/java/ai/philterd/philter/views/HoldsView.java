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

import ai.philterd.philter.data.entities.LegalHoldEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.services.LegalHoldDataService;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.services.RequestIdGenerator;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.config.AdminAccessConfig;
import com.mongodb.client.MongoClient;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
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
import jakarta.annotation.security.PermitAll;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.bson.types.ObjectId;

@Route(value = "holds")
@PageTitle("Philter - Legal Holds")
@PermitAll
public class HoldsView extends AbstractRestrictedView {

    private static final int PAGE_SIZE = 25;

    private final LegalHoldDataService legalHoldDataService;
    private final UserEntity currentUser;
    private final Grid<LegalHoldEntity> grid = new Grid<>(LegalHoldEntity.class, false);
    private Grid<AllHoldRow> allGrid;

    public HoldsView(final MongoClient mongoClient,
                     final EncryptionService encryptionService,
                     final AuditEventPublisher auditEventPublisher,
                     final LegalHoldDataService legalHoldDataService) {
        super(mongoClient, encryptionService, auditEventPublisher);
        this.legalHoldDataService = legalHoldDataService;
        this.currentUser = getCurrentUser();

        final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");

        grid.addColumn(LegalHoldEntity::getReference).setHeader("Reference").setAutoWidth(true).setResizable(true);
        grid.addColumn(LegalHoldEntity::getScopeType).setHeader("Scope Type").setAutoWidth(true);
        grid.addColumn(LegalHoldEntity::getScopeValue).setHeader("Scope Value").setAutoWidth(true).setResizable(true);
        grid.addColumn(LegalHoldEntity::getReason).setHeader("Reason").setFlexGrow(1).setResizable(true);
        grid.addColumn(h -> h.getSetAt() != null ? dateFormat.format(h.getSetAt()) : "")
                .setHeader("Set At").setAutoWidth(true);
        grid.addComponentColumn(this::createReleaseButton)
                .setHeader("").setAutoWidth(true).setFlexGrow(0);

        grid.setPageSize(PAGE_SIZE);
        grid.setItems(
                query -> fetchHolds(query.getOffset(), query.getLimit()).stream(),
                query -> countHolds());

        final Button setHoldButton = new Button("Set Hold", VaadinIcon.LOCK.create());
        setHoldButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        setHoldButton.addClickListener(e -> openSetHoldDialog());

        final HorizontalLayout toolbar = new HorizontalLayout(setHoldButton);

        final Span description = new Span(
                "Legal holds block deletion and purge of governance evidence until released. "
                        + "Every hold and release is audited. Holds shown here protect your own evidence. ");
        description.add(ai.philterd.philter.views.widgets.CommonWidgets.getLink("Learn more about legal holds.", "/public/docs/redaction/legal_holds.html", true));

        final VerticalLayout layout = new VerticalLayout();
        layout.setSizeFull();
        layout.add(description, grid);

        final TabSheet tabSheet = new TabSheet();
        tabSheet.add("My Legal Holds", layout);
        if (isAdmin() && AdminAccessConfig.isCrossUserAccessEnabled()) {
            tabSheet.add("All Legal Holds", buildAllHoldsLayout());
        }
        tabSheet.setSizeFull();
        tabSheet.setSuffixComponent(toolbar);

        final VerticalLayout page = new VerticalLayout();
        page.add(getTitle("Legal Holds"));
        page.add(tabSheet);
        page.add(ai.philterd.philter.views.widgets.CommonWidgets.getFooter());
        page.setSizeFull();

        final HorizontalLayout root = new HorizontalLayout(page);
        root.setSizeFull();
        setContent(root);
    }

    private List<LegalHoldEntity> fetchHolds(final int offset, final int limit) {
        return legalHoldDataService.findAllByUserId(currentUser.getId(), offset, limit);
    }

    private int countHolds() {
        return legalHoldDataService.countByUserId(currentUser.getId());
    }

    private void refreshGrids() {
        grid.getDataProvider().refreshAll();
        if (allGrid != null) {
            allGrid.getDataProvider().refreshAll();
        }
    }

    /** Builds the admin-only "All Legal Holds" tab: every user's holds, with the owner's email. */
    private VerticalLayout buildAllHoldsLayout() {
        final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");

        allGrid = new Grid<>();
        allGrid.addColumn(r -> r.hold().getReference()).setHeader("Reference").setAutoWidth(true).setResizable(true);
        allGrid.addColumn(AllHoldRow::ownerEmail).setHeader("User").setAutoWidth(true).setResizable(true);
        allGrid.addColumn(r -> r.hold().getScopeType()).setHeader("Scope Type").setAutoWidth(true);
        allGrid.addColumn(r -> r.hold().getScopeValue()).setHeader("Scope Value").setAutoWidth(true).setResizable(true);
        allGrid.addColumn(r -> r.hold().getReason()).setHeader("Reason").setFlexGrow(1).setResizable(true);
        allGrid.addColumn(r -> r.hold().getSetAt() != null ? dateFormat.format(r.hold().getSetAt()) : "")
                .setHeader("Set At").setAutoWidth(true);
        allGrid.addComponentColumn(r -> createReleaseButton(r.hold()))
                .setHeader("").setAutoWidth(true).setFlexGrow(0);

        allGrid.setPageSize(PAGE_SIZE);
        allGrid.setItems(
                query -> {
                    final List<LegalHoldEntity> holds = legalHoldDataService.findAll(query.getOffset(), query.getLimit());
                    final Map<ObjectId, String> emails = userService.findUsernamesByIds(
                            holds.stream().map(LegalHoldEntity::getUserId).collect(Collectors.toSet()));
                    return holds.stream().map(h -> new AllHoldRow(h, emails.getOrDefault(h.getUserId(), "(unknown)")));
                },
                query -> legalHoldDataService.countAll());

        final Span description = new Span(
                "All legal holds across all users. Every hold and release is audited.");

        final VerticalLayout layout = new VerticalLayout();
        layout.setSizeFull();
        layout.add(description, allGrid);
        return layout;
    }

    private Button createReleaseButton(final LegalHoldEntity hold) {
        final Button btn = new Button("Release", VaadinIcon.UNLOCK.create());
        btn.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR);
        btn.addClickListener(e -> openReleaseConfirmDialog(hold));
        return btn;
    }

    private void openReleaseConfirmDialog(final LegalHoldEntity hold) {
        final Dialog dialog = new Dialog();
        dialog.setMinWidth("480px");
        dialog.add(new H3("Release Hold"));
        dialog.add(new Paragraph("Release hold \"" + hold.getReference() + "\"? "
                + "Once released, evidence covered by this hold may become eligible for deletion or "
                + "purge if no other holds remain. This action is audited and cannot be undone."));

        final Button confirmBtn = new Button("Release", e -> {
            final ServiceResponse response = legalHoldDataService.release(
                    RequestIdGenerator.generate(), hold.getReference(),
                    hold.getUserId());
            dialog.close();
            if (response.isSuccessful()) {
                refreshGrids();
                showSuccessNotification("Hold \"" + hold.getReference() + "\" released.");
            } else {
                showFailureNotification(response.getMessage());
            }
        });
        confirmBtn.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_PRIMARY);

        final Button cancelBtn = new Button("Cancel", e -> dialog.close());
        cancelBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        dialog.getFooter().add(cancelBtn, confirmBtn);
        dialog.open();
    }

    private void openSetHoldDialog() {
        final TextField referenceField = new TextField("Reference");
        referenceField.setPlaceholder("e.g. LITIGATION-2026-001");
        referenceField.setWidthFull();
        referenceField.setRequired(true);

        final ComboBox<String> scopeTypeCombo = new ComboBox<>("Scope Type");
        scopeTypeCombo.setItems(LegalHoldEntity.SCOPE_DOCUMENT_CHAIN, LegalHoldEntity.SCOPE_USER);
        scopeTypeCombo.setWidthFull();
        scopeTypeCombo.setRequired(true);

        final TextField scopeValueField = new TextField("Scope Value");
        scopeValueField.setPlaceholder("Document ID or User ID");
        scopeValueField.setWidthFull();
        scopeValueField.setRequired(true);

        scopeTypeCombo.addValueChangeListener(e -> {
            if (LegalHoldEntity.SCOPE_DOCUMENT_CHAIN.equals(e.getValue())) {
                scopeValueField.setLabel("Document ID");
                scopeValueField.setPlaceholder("The document ID to protect");
            } else if (LegalHoldEntity.SCOPE_USER.equals(e.getValue())) {
                scopeValueField.setLabel("User ID");
                scopeValueField.setPlaceholder("The user ID whose evidence to protect");
            }
        });

        final TextArea reasonField = new TextArea("Reason (optional)");
        reasonField.setPlaceholder("e.g. Outside counsel directive, regulatory audit");
        reasonField.setWidthFull();
        reasonField.setMaxHeight("120px");

        final Dialog dialog = new Dialog();
        dialog.setMinWidth("520px");
        dialog.add(new H3("Set Legal Hold"));
        dialog.add(new Paragraph("A hold blocks all deletion and purge of the covered evidence until released."));
        dialog.add(ai.philterd.philter.views.widgets.CommonWidgets.getLink(
                "Learn more about legal holds.", "/public/docs/redaction/legal_holds.html", true));
        dialog.add(referenceField, scopeTypeCombo, scopeValueField, reasonField);

        final Button setBtn = new Button("Set Hold", e -> {
            if (referenceField.getValue().isBlank()) {
                referenceField.setInvalid(true);
                referenceField.setErrorMessage("Reference is required.");
                return;
            }
            if (scopeTypeCombo.getValue() == null) {
                scopeTypeCombo.setInvalid(true);
                scopeTypeCombo.setErrorMessage("Scope type is required.");
                return;
            }
            if (scopeValueField.getValue().isBlank()) {
                scopeValueField.setInvalid(true);
                scopeValueField.setErrorMessage("Scope value is required.");
                return;
            }

            final ServiceResponse response = legalHoldDataService.create(
                    RequestIdGenerator.generate(),
                    referenceField.getValue().trim(),
                    scopeTypeCombo.getValue(),
                    scopeValueField.getValue().trim(),
                    reasonField.getValue().trim().isEmpty() ? null : reasonField.getValue().trim(),
                    currentUser.getId(),
                    currentUser.getId());

            dialog.close();
            if (response.isSuccessful()) {
                refreshGrids();
                showSuccessNotification("Hold \"" + referenceField.getValue().trim() + "\" set.");
            } else if (response.getStatusCode() == 409) {
                showWarningNotification("A hold with that reference already exists.");
            } else {
                showFailureNotification(response.getMessage());
            }
        });
        setBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        final Button cancelBtn = new Button("Cancel", e -> dialog.close());
        cancelBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        dialog.getFooter().add(cancelBtn, setBtn);
        dialog.open();
    }

    /** A row in the "All Legal Holds" table: the hold plus the owner's email. */
    private record AllHoldRow(LegalHoldEntity hold, String ownerEmail) {}
}

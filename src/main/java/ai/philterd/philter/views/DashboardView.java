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

import ai.philterd.phileas.model.filtering.AbstractFilterResult;
import org.bson.types.ObjectId;
import ai.philterd.phileas.model.filtering.BinaryDocumentFilterResult;
import ai.philterd.phileas.model.filtering.MimeType;
import ai.philterd.phileas.model.filtering.TextFilterResult;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.PolicyEntity;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.services.filtering.RedactionService;
import ai.philterd.philter.views.widgets.CommonWidgets;
import com.mongodb.client.MongoClient;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.TabSheet;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.component.upload.receivers.MemoryBuffer;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteAlias;
import com.vaadin.flow.server.StreamResource;
import jakarta.annotation.security.PermitAll;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

@Route(value = "dashboard")
@RouteAlias(value = "")
@PageTitle("Philter - Dashboard")
@PermitAll
public class DashboardView extends AbstractRestrictedView {

    private static final Logger LOGGER = LogManager.getLogger(DashboardView.class);

    private final PolicyDataService policyDataService;
    private final RedactionService redactionService;


    public DashboardView(final MongoClient mongoClient, final EncryptionService encryptionService,
                         final AuditEventPublisher auditEventPublisher, final PolicyDataService policyDataService,
                         final RedactionService redactionService) {
        super(mongoClient, encryptionService, auditEventPublisher);

        this.policyDataService = policyDataService;
        this.redactionService = redactionService;

        final VerticalLayout pageVerticalLayout = new VerticalLayout();
        pageVerticalLayout.setSizeFull();
        pageVerticalLayout.add(getTitle(("Philter Dashboard")));

        final HorizontalLayout filterHorizontalLayout = new HorizontalLayout();
        filterHorizontalLayout.setWidthFull();
        filterHorizontalLayout.add(createFilterText());
        filterHorizontalLayout.add(createPdfFilter());

        final TabSheet tabSheet = new TabSheet();
        tabSheet.add("Redaction Test", filterHorizontalLayout);
        tabSheet.setSizeFull();

        pageVerticalLayout.add(tabSheet);
        pageVerticalLayout.add(CommonWidgets.getFooter());
        pageVerticalLayout.setSizeFull();

        final HorizontalLayout pageHorizontalLayout = new HorizontalLayout();
        pageHorizontalLayout.add(pageVerticalLayout);
        pageHorizontalLayout.setSizeFull();

        setContent(pageHorizontalLayout);

    }

    private VerticalLayout createFilterText() {

        final ComboBox<String> policyComboBox = new ComboBox<>("Policy");
        policyComboBox.setWidthFull();
        policyComboBox.setPlaceholder("Select a policy");
        policyComboBox.setAllowCustomValue(false);
        policyComboBox.setItems(policyDataService.findAll(userEntity.getId(), 0, 100, false).stream().map(PolicyEntity::getName).toList());
        policyComboBox.setValue("default");

        final TextArea textToFilter = new TextArea("Text");
        textToFilter.setWidthFull();
        textToFilter.setValue("George Washington was president.");
        textToFilter.setHeight("300px");

        final TextArea redactedTextArea = new TextArea("Redacted Text");
        redactedTextArea.setWidthFull();
        redactedTextArea.setHeight("300px");
        redactedTextArea.setReadOnly(true);
        redactedTextArea.setVisible(false);

        final Button filterButton = new Button("Submit Text", event -> {

            final String selectedPolicy = policyComboBox.getValue();
            final String text = textToFilter.getValue();

            if (selectedPolicy == null || text == null || text.isEmpty()) {
                Notification.show("Please select a policy and enter text.");
                return;
            }

            try {

                final String redactedText = redactText(redactionService, selectedPolicy, userEntity.getId(), text);

                redactedTextArea.setValue(redactedText != null ? redactedText : "");
                redactedTextArea.setVisible(true);

                showSuccessNotification("Text redacted.");

            } catch (Exception ex) {
                LOGGER.error("Failed to redact text", ex);
                showFailureNotification("Failed to redact text: " + ex.getMessage());
            }

        });
        filterButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        final VerticalLayout filterTextVerticalLayout = new VerticalLayout();
        filterTextVerticalLayout.setSizeFull();
        filterTextVerticalLayout.add(new Paragraph("Test Philter's configuration by redacting text."));
        filterTextVerticalLayout.add(policyComboBox);
        filterTextVerticalLayout.add(textToFilter);
        filterTextVerticalLayout.add(filterButton);
        filterTextVerticalLayout.add(redactedTextArea);

        return filterTextVerticalLayout;

    }

    private VerticalLayout createPdfFilter() {

        final VerticalLayout pageVerticalLayout = new VerticalLayout();
        pageVerticalLayout.setSizeFull();

        pageVerticalLayout.add(new Paragraph("Select a PDF document to redact."));

        final ComboBox<String> pdfPolicyComboBox = new ComboBox<>("Policy");
        pdfPolicyComboBox.setWidthFull();
        pdfPolicyComboBox.setPlaceholder("Select a policy");
        pdfPolicyComboBox.setAllowCustomValue(false);
        pdfPolicyComboBox.setItems(policyDataService.findAll(userEntity.getId(), 0, 100, false).stream().map(PolicyEntity::getName).toList());
        pdfPolicyComboBox.setValue("default");

        final MemoryBuffer buffer = new MemoryBuffer();
        Upload upload = new Upload(buffer);
        upload.setAcceptedFileTypes("application/pdf");
        upload.setWidthFull();

        final HorizontalLayout downloadRow = new HorizontalLayout();
        downloadRow.setVisible(false);

        final Button filterPdfButton = new Button("Submit PDF", event -> {
            final String selectedPolicy = pdfPolicyComboBox.getValue();
            if (selectedPolicy == null) {
                Notification.show("Please select a policy.");
                return;
            }
            if (buffer.getFileName().isEmpty()) {
                Notification.show("Please select a file.");
                return;
            }

            try {
                final byte[] body = buffer.getInputStream().readAllBytes();

                // The dashboard redaction test is stateless: it uses no context.
                final AbstractFilterResult result = redactionService.filter(
                        selectedPolicy, userEntity.getId(), "", body, MimeType.APPLICATION_PDF).result();

                final byte[] redactedBytes = ((BinaryDocumentFilterResult) result).getDocument();

                final String fileName = buffer.getFileName();
                final StreamResource resource = new StreamResource(
                        "redacted-" + fileName, () -> new ByteArrayInputStream(redactedBytes));
                resource.setContentType("application/pdf");

                final Anchor downloadLink = new Anchor(resource, "Download redacted " + fileName);
                downloadLink.getElement().setAttribute("download", true);

                downloadRow.removeAll();
                downloadRow.add(downloadLink);
                downloadRow.setVisible(true);

                showSuccessNotification("PDF redacted.");

            } catch (Exception ex) {
                LOGGER.error("Failed to redact PDF", ex);
                showFailureNotification("Failed to redact PDF: " + ex.getMessage());
            }
        });
        filterPdfButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        pageVerticalLayout.add(pdfPolicyComboBox, upload, filterPdfButton, downloadRow);

        return pageVerticalLayout;

    }

    /**
     * Runs the dashboard's text redaction against the redaction service and returns the redacted
     * text. Extracted from the button handler so the filtering behavior can be tested without a
     * browser.
     */
    static String redactText(final RedactionService redactionService, final String policyName,
                             final ObjectId userId, final String text) throws Exception {

        // The dashboard redaction test is stateless: it uses no context, so token replacements are not
        // persisted and disambiguation (if any) is limited to this document.
        final AbstractFilterResult result = redactionService.filter(
                policyName, userId, "", text.getBytes(StandardCharsets.UTF_8), MimeType.TEXT_PLAIN).result();

        return ((TextFilterResult) result).getFilteredText();

    }

}

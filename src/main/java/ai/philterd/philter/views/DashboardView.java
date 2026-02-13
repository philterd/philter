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

import com.google.gson.Gson;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.component.upload.receivers.MemoryBuffer;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;

@Route(value = "")
@PageTitle("Philter - Dashboard")
@AnonymousAllowed
public class DashboardView extends AbstractView {

    private static final Logger LOGGER = LogManager.getLogger(DashboardView.class);

    @Autowired
    public DashboardView(final Gson gson) {

        final VerticalLayout pageVerticalLayout = new VerticalLayout();
        pageVerticalLayout.setSizeFull();
        pageVerticalLayout.add(new H1("Philter Dashboard"));

        final HorizontalLayout filterHorizontalLayout = new HorizontalLayout();
        filterHorizontalLayout.setWidthFull();
        filterHorizontalLayout.add(createFilterText());
        filterHorizontalLayout.add(createPdfFilter());

        pageVerticalLayout.add(filterHorizontalLayout);

        pageVerticalLayout.add(new H2("Usage Metrics"));
        pageVerticalLayout.add(new Paragraph("Metrics are not persistent and reset upon Philter restart."));

        pageVerticalLayout.add(getFooter());
        pageVerticalLayout.setSizeFull();

        setContent(pageVerticalLayout);

    }

    private VerticalLayout createFilterText() {

        final ComboBox<String> policyComboBox = new ComboBox<>("Policy");
        policyComboBox.setWidthFull();
        policyComboBox.setPlaceholder("Select a policy");
        policyComboBox.setAllowCustomValue(false);

        final TextArea textToFilter = new TextArea("Text");
        textToFilter.setWidthFull();
        textToFilter.setValue("George Washington was president.");
        textToFilter.setHeight("300px");

        final Button filterButton = new Button("Submit Text", event -> {

            String profile = policyComboBox.getValue();
            String text = textToFilter.getValue();

            if (profile == null || text == null || text.isEmpty()) {
                Notification.show("Please select a policy and enter text.");
                return;
            }

            // Mocking the behavior for now as it was commented out in MainView
            Notification.show("Filtering text with policy: " + profile);

        });
        filterButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        final VerticalLayout filterTextVerticalLayout = new VerticalLayout();
        filterTextVerticalLayout.setSizeFull();
        filterTextVerticalLayout.add(new Paragraph("Test Philter's configuration by filtering text."));
        filterTextVerticalLayout.add(policyComboBox);
        filterTextVerticalLayout.add(textToFilter);
        filterTextVerticalLayout.add(filterButton);

        return filterTextVerticalLayout;

    }

    private VerticalLayout createPdfFilter() {

        final VerticalLayout pageVerticalLayout = new VerticalLayout();
        pageVerticalLayout.setSizeFull();

        pageVerticalLayout.add(new Paragraph("Select a PDF document to filter."));

        final ComboBox<String> pdfPolicyComboBox = new ComboBox<>("Policy");
        pdfPolicyComboBox.setWidthFull();
        pdfPolicyComboBox.setPlaceholder("Select a policy");
        pdfPolicyComboBox.setAllowCustomValue(false);
        pdfPolicyComboBox.setItems("default", "policy1", "policy2");
        pdfPolicyComboBox.setValue("default");

        final MemoryBuffer buffer = new MemoryBuffer();
        Upload upload = new Upload(buffer);
        upload.setAcceptedFileTypes("application/pdf");
        upload.setWidthFull();

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
            // TODO
            //Notification.show("Filtering PDF: " + fileName + " with policy: " + profile);
            //filterPdf(selectedPolicy, buffer.getInputStream(), buffer.getFileName());
        });
        filterPdfButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        pageVerticalLayout.add(pdfPolicyComboBox, upload, filterPdfButton);

        return pageVerticalLayout;

    }

}

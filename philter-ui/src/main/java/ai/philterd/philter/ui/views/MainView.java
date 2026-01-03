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
package ai.philterd.philter.ui.views;

import ai.philterd.philter.ui.domain.Policy;
import com.google.gson.Gson;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Html;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H5;
import com.vaadin.flow.component.html.H6;
import com.vaadin.flow.component.html.Hr;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.component.upload.receivers.MemoryBuffer;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

@Route("")
@PageTitle("Philter - Dashboard")
public class MainView extends VerticalLayout {

    private static final Logger LOGGER = LogManager.getLogger(MainView.class);

    private final Gson gson;

    private final ComboBox<String> policyComboBox = new ComboBox<>("Policy");
    private final TextArea textToFilter = new TextArea("Text");
    private final Span filteredTextSpan = new Span();
    private final TextArea explanationTextArea = new TextArea("Explanation");
    private final Grid<Policy> policyGrid = new Grid<>(Policy.class, false);

    @Autowired
    public MainView(Gson gson) {

        this.gson = gson;

        setSizeFull();
        setPadding(false);
        setSpacing(false);

        add(createHeader());

        HorizontalLayout mainContent = new HorizontalLayout();
        mainContent.setSizeFull();
        mainContent.setSpacing(true);
        mainContent.setPadding(true);

        VerticalLayout leftColumn = new VerticalLayout();
        leftColumn.setWidth("50%");
        leftColumn.add(createFilterTextCard());
        leftColumn.add(createFilterPdfCard());

        VerticalLayout rightColumn = new VerticalLayout();
        rightColumn.setWidth("50%");
        rightColumn.add(createFilteredOutputCard());
        rightColumn.add(createPoliciesCard());

        mainContent.add(leftColumn, rightColumn);
        add(mainContent);

        add(createSdkSection());
        add(createFooter());

        refreshPolicies();
    }

    private Component createHeader() {
        HorizontalLayout header = new HorizontalLayout();
        header.setWidthFull();
        header.setPadding(true);
        header.addClassName("shadow");
        header.getStyle().set("background-color", "white");
        header.getStyle().set("margin-bottom", "1rem");

        Image logo = new Image("img/philter-logo-transparent.png", "Philter Logo");
        logo.setHeight("50px");
        header.add(logo);

        return header;
    }

    private Component createFilterTextCard() {
        VerticalLayout card = (VerticalLayout) createCard("Filter Text", VaadinIcon.FILTER);

        Paragraph p = new Paragraph("Test Philter's configuration by filtering text.");
        card.add(p);

        policyComboBox.setWidthFull();
        textToFilter.setWidthFull();
        textToFilter.setValue("George Washington was president.");
        textToFilter.setHeight("150px");

        Button filterButton = new Button("Filter", event -> filterText());
        filterButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        filterButton.setWidthFull();

        card.add(policyComboBox, textToFilter, filterButton);

        return card;
    }

    private Component createFilteredOutputCard() {
        VerticalLayout card = (VerticalLayout) createCard("Filtered Output", VaadinIcon.FILE);

        card.add(new Html("<b>Filtered Text</b>"));
        filteredTextSpan.getStyle().set("display", "block");
        filteredTextSpan.getStyle().set("margin-bottom", "1rem");
        card.add(filteredTextSpan);

        explanationTextArea.setWidthFull();
        explanationTextArea.setReadOnly(true);
        explanationTextArea.setHeight("250px");
        card.add(explanationTextArea);

        return card;
    }

    private Component createFilterPdfCard() {
        VerticalLayout card = (VerticalLayout) createCard("Filter PDF Document", VaadinIcon.FILE_TEXT);

        card.add(new Paragraph("Select a PDF document to filter."));

        ComboBox<String> pdfPolicyComboBox = new ComboBox<>("Policy");
        pdfPolicyComboBox.setWidthFull();
        // Bind items from policyComboBox
        policyComboBox.addValueChangeListener(e -> pdfPolicyComboBox.setValue(e.getValue()));
        
        MemoryBuffer buffer = new MemoryBuffer();
        Upload upload = new Upload(buffer);
        upload.setAcceptedFileTypes("application/pdf");
        upload.setWidthFull();

        Button filterPdfButton = new Button("Filter PDF", event -> {
            String selectedPolicy = pdfPolicyComboBox.getValue();
            if (selectedPolicy == null) {
                Notification.show("Please select a policy.");
                return;
            }
            if (buffer.getFileName().isEmpty()) {
                Notification.show("Please select a file.");
                return;
            }
            filterPdf(selectedPolicy, buffer.getInputStream(), buffer.getFileName());
        });
        filterPdfButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        filterPdfButton.setWidthFull();

        card.add(pdfPolicyComboBox, upload, filterPdfButton);

        // Keep policies in sync
        policyComboBox.addValueChangeListener(e -> pdfPolicyComboBox.setValue(e.getValue()));
        // Note: they are refreshed together in refreshPolicies

        return card;
    }

    private Component createPoliciesCard() {
        VerticalLayout card = (VerticalLayout) createCard("Policies", VaadinIcon.FILE_TEXT);

        card.add(new Paragraph("Policies control what and how sensitive information is redacted from text."));

        Button newPolicyButton = new Button("New Policy", VaadinIcon.PLUS.create(), event -> showNewPolicyDialog());
        newPolicyButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL);
        card.add(newPolicyButton);

        policyGrid.addColumn(Policy::getName).setHeader("Name");
        policyGrid.addComponentColumn(policy -> {
            Button deleteButton = new Button("Delete", e -> {
//                try {
//                    philterClient.deletePolicy(policy.getName());
//                    refreshPolicies();
//                } catch (IOException ex) {
//                    LOGGER.error("Error deleting policy", ex);
//                    Notification.show("Error deleting policy: " + ex.getMessage());
//                }
            });
            deleteButton.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_SMALL);
            return deleteButton;
        }).setHeader("");
        
        policyGrid.setHeight("200px");

        card.add(policyGrid);

        return card;
    }

    private Component createCard(String title, VaadinIcon icon) {
        VerticalLayout card = new VerticalLayout();
        card.addClassName("shadow");
        card.getStyle().set("background-color", "white");
        card.getStyle().set("border-radius", "0.35rem");
        card.setPadding(true);
        card.setSpacing(true);
        card.setMargin(true);

        HorizontalLayout header = new HorizontalLayout();
        header.setWidthFull();
        header.setJustifyContentMode(FlexComponent.JustifyContentMode.BETWEEN);
        header.setVerticalComponentAlignment(FlexComponent.Alignment.CENTER);

        H6 h6 = new H6(title);
        h6.getStyle().set("color", "#4e73df");
        h6.getStyle().set("margin", "0");

        Icon vaadinIcon = icon.create();
        vaadinIcon.setColor("#dddfeb");

        header.add(h6, vaadinIcon);
        card.add(header, new Hr());

        return card;
    }

    private Component createSdkSection() {
        VerticalLayout section = new VerticalLayout();
        section.setPadding(true);
        
        VerticalLayout card = (VerticalLayout) createCard("Philter SDKs", VaadinIcon.TOOLS);
        
        HorizontalLayout sdkRow = new HorizontalLayout();
        sdkRow.setWidthFull();
        
        sdkRow.add(createSdkItem("CLI", "Command Line", "https://github.com/philterd/philter-cli", "Filter text from the command line."));
        sdkRow.add(createSdkItem("SDK", "Java", "https://github.com/philterd/philter-sdk-java", "Filter text from your Java apps"));
        sdkRow.add(createSdkItem("SDK", ".NET", "https://github.com/philterd/philter-sdk-net", "Filter text from your .NET apps"));
        sdkRow.add(createSdkItem("SDK", "Golang", "https://github.com/philterd/philter-sdk-golang", "Filter text from your Golang apps"));
        
        card.add(sdkRow);
        section.add(card);
        return section;
    }

    private Component createSdkItem(String type, String name, String url, String description) {
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

    private Component createFooter() {
        VerticalLayout footer = new VerticalLayout();
        footer.setWidthFull();
        footer.setAlignItems(FlexComponent.Alignment.CENTER);
        footer.setPadding(true);
        
        Image img = new Image("img/philterd.png", "Philterd");
        Span copyright = new Span("Copyright © Philterd, LLC. \"Philter\" is a registered trademark of Philterd, LLC.");
        copyright.getStyle().set("font-size", "0.8rem");
        copyright.getStyle().set("color", "#858796");
        
        footer.add(img, copyright);
        return footer;
    }

    private void filterText() {
        String profile = policyComboBox.getValue();
        String text = textToFilter.getValue();
        
        if (profile == null || text == null || text.isEmpty()) {
            Notification.show("Please select a policy and enter text.");
            return;
        }

//        try {
//            ExplainResponse explainResponse = philterClient.explain("context", "", profile, text);
//            filteredTextSpan.setText(explainResponse.getFilteredText());
//            explanationTextArea.setValue(gson.toJson(explainResponse.getExplanation()));
//        } catch (IOException e) {
//            LOGGER.error("Error filtering text", e);
//            Notification.show("Error filtering text: " + e.getMessage());
//        }
    }

    private void filterPdf(String profile, InputStream inputStream, String fileName) {
        try {
            File tempFile = File.createTempFile("temp", "pdf");
            FileUtils.copyInputStreamToFile(inputStream, tempFile);

//            BinaryFilterResponse binaryFilterResponse = philterClient.filter("context", "", profile, tempFile);
//
//            File zipFileTemp = File.createTempFile("philter", ".zip");
//            FileUtils.writeByteArrayToFile(zipFileTemp, binaryFilterResponse.getContent());
//
//            try (ZipFile zipFile = new ZipFile(zipFileTemp.getAbsolutePath())) {
//                Enumeration<? extends ZipEntry> entries = zipFile.entries();
//                if (entries.hasMoreElements()) {
//                    ZipEntry zipEntry = entries.nextElement();
//                    try (InputStream zipInputStream = zipFile.getInputStream(zipEntry)) {
//                        byte[] bytes = IOUtils.toByteArray(zipInputStream);
//                        StreamResource resource = new StreamResource("filtered-" + fileName + ".jpg", () -> new ByteArrayInputStream(bytes));
//
//                        Dialog dialog = new Dialog();
//                        dialog.setHeaderTitle("Filtered PDF (as Image)");
//                        Image image = new Image(resource, "Filtered PDF");
//                        image.setMaxWidth("100%");
//                        dialog.add(new VerticalLayout(image));
//                        Button closeButton = new Button("Close", e -> dialog.close());
//                        dialog.getFooter().add(closeButton);
//                        dialog.open();
//                    }
//                }
//            }
        } catch (IOException e) {
            LOGGER.error("Error filtering PDF", e);
            Notification.show("Error filtering PDF: " + e.getMessage());
        }
    }

    private void showNewPolicyDialog() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("New Policy");

        TextArea jsonTextArea = new TextArea("Policy JSON");
        jsonTextArea.setWidthFull();
        jsonTextArea.setHeight("300px");

        Button saveButton = new Button("Save", e -> {
//            try {
//                //philterClient.savePolicy(jsonTextArea.getValue());
//                refreshPolicies();
//                dialog.close();
//            } catch (IOException ex) {
//                LOGGER.error("Error saving policy", ex);
//                Notification.show("Error saving policy: " + ex.getMessage());
//            }
        });
        saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        Button cancelButton = new Button("Cancel", e -> dialog.close());

        dialog.add(new VerticalLayout(jsonTextArea));
        dialog.getFooter().add(cancelButton, saveButton);
        dialog.open();
    }

    private void refreshPolicies() {
//        try {
//           // List<String> policyNames = philterClient.getPolicies();
//            policyComboBox.setItems(policyNames);
//            if (!policyNames.isEmpty()) {
//                policyComboBox.setValue(policyNames.get(0));
//            }
//
//            List<Policy> policies = new LinkedList<>();
//            for (String name : policyNames) {
//                Policy p = new Policy();
//                p.setName(name);
//                policies.add(p);
//            }
//            policyGrid.setItems(policies);
//        } catch (IOException e) {
//            LOGGER.error("Error refreshing policies", e);
//            Notification.show("Error refreshing policies: " + e.getMessage());
//        }
    }
}

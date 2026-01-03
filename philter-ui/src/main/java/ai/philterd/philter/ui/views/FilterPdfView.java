package ai.philterd.philter.ui.views;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.component.upload.receivers.MemoryBuffer;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Route(value = "filter-pdf", layout = MainLayout.class)
@PageTitle("Philter - Filter PDF")
public class FilterPdfView extends VerticalLayout {

    private static final Logger LOGGER = LogManager.getLogger(FilterPdfView.class);

    public FilterPdfView() {

        setSizeFull();
        setPadding(true);

        final VerticalLayout pageVerticalLayout = new VerticalLayout();
        pageVerticalLayout.setSizeFull();

        pageVerticalLayout.add(new H1("Filter PDF Document"));
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

        final Button filterPdfButton = new Button("Filter PDF", event -> {
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
        filterPdfButton.setWidthFull();

        pageVerticalLayout.add(pdfPolicyComboBox, upload, filterPdfButton);

        add(pageVerticalLayout);

    }

}

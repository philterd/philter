package ai.philterd.philter.ui.views;

import com.google.gson.Gson;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;

@Route(value = "filter-text", layout = MainLayout.class)
@PageTitle("Philter - Filter Text")
public class FilterTextView extends VerticalLayout {

    private static final Logger LOGGER = LogManager.getLogger(FilterTextView.class);

    @Autowired
    public FilterTextView(Gson gson) {

        setSizeFull();
        setPadding(true);

        final ComboBox<String> policyComboBox = new ComboBox<>("Policy");
        policyComboBox.setWidthFull();
        policyComboBox.setPlaceholder("Select a policy");
        policyComboBox.setAllowCustomValue(false);

        final TextArea textToFilter = new TextArea("Text");
        textToFilter.setWidthFull();
        textToFilter.setValue("George Washington was president.");
        textToFilter.setHeight("300px");

        Button filterButton = new Button("Filter", event -> {

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
        filterButton.setWidthFull();

        final VerticalLayout pageVerticalLayout = new VerticalLayout();
        pageVerticalLayout.setSizeFull();
        pageVerticalLayout.add(new H1("Filter Text"));
        pageVerticalLayout.add(new Paragraph("Test Philter's configuration by filtering text."));
        pageVerticalLayout.add(policyComboBox);
        pageVerticalLayout.add(textToFilter);
        pageVerticalLayout.add(filterButton);

        add(pageVerticalLayout);

        // TODO
        policyComboBox.setItems("default", "policy1", "policy2");
        policyComboBox.setValue("default");

    }

}

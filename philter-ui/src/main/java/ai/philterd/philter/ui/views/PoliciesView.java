package ai.philterd.philter.ui.views;

import ai.philterd.philter.ui.domain.Policy;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedList;

@Route(value = "policies", layout = MainLayout.class)
@PageTitle("Philter - Policies")
public class PoliciesView extends VerticalLayout {

    private static final Logger LOGGER = LogManager.getLogger(PoliciesView.class);

    public PoliciesView() {

        setSizeFull();
        setPadding(true);

        final VerticalLayout pageVerticalLayout = new VerticalLayout();
        pageVerticalLayout.add(new H1("Policies"));
        pageVerticalLayout.add(new Paragraph("Policies control what types of PII to redact and how to redact each type."));
        pageVerticalLayout.setSizeFull();

        final Button newPolicyButton = new Button("New Policy", VaadinIcon.PLUS.create(), event -> {

            Dialog dialog = new Dialog();
            dialog.setHeaderTitle("New Policy");

            TextArea jsonTextArea = new TextArea("Policy JSON");
            jsonTextArea.setWidthFull();
            jsonTextArea.setHeight("300px");

            Button saveButton = new Button("Save", e -> {
                Notification.show("Saving policy...");
                dialog.close();
                // TODO:  refreshPolicies();
            });
            saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

            Button cancelButton = new Button("Cancel", e -> dialog.close());

            dialog.add(new VerticalLayout(jsonTextArea));
            dialog.getFooter().add(cancelButton, saveButton);
            dialog.open();

        });
        newPolicyButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL);
        pageVerticalLayout.add(newPolicyButton);

        final Grid<Policy> policyGrid = new Grid<>(Policy.class, false);
        policyGrid.addColumn(Policy::getName).setHeader("Name");
        policyGrid.addComponentColumn(originalListEntity -> {

            final Button deletePolicyButton = new Button("Delete", VaadinIcon.TRASH.create());
            deletePolicyButton.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_SMALL);
            deletePolicyButton.addClickListener(event -> {
                // TODO
            });

            return deletePolicyButton;

        });

        // TODO
        policyGrid.setItems(new LinkedList<>());

        policyGrid.setSizeFull();

        pageVerticalLayout.add(policyGrid);

        add(pageVerticalLayout);

    }

}

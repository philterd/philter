package ai.philterd.philter.ui.views;

import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;

public abstract class AbstractView extends VerticalLayout {

    protected VerticalLayout getFooter() {

        final VerticalLayout footer = new VerticalLayout();
        footer.setWidthFull();
        footer.setAlignItems(FlexComponent.Alignment.CENTER);
        footer.setPadding(true);

        final Image logo = new Image("img/philterd.png", "Philterd");

        final Anchor link = new Anchor("https://www.philterd.ai", logo);
        link.setTarget("_blank");

        final Span copyright = new Span("Copyright © Philterd, LLC. \"Philter\" is a registered trademark of Philterd, LLC.");
        copyright.getStyle().set("font-size", "0.8rem");
        copyright.getStyle().set("color", "#858796");

        footer.add(link, copyright);

        return footer;

    }

}

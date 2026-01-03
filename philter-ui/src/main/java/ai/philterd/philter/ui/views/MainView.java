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
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H5;
import com.vaadin.flow.component.html.H6;
import com.vaadin.flow.component.html.Hr;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;

@Route(value = "", layout = MainLayout.class)
@PageTitle("Philter - Dashboard")
public class MainView extends VerticalLayout {

    private static final Logger LOGGER = LogManager.getLogger(MainView.class);

    @Autowired
    public MainView(final Gson gson) {

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        add(createSdkSection());
        add(createFooter());

    }

    private Component createCard(final String title, final VaadinIcon icon) {

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

    private Component createSdkItem(final String type, final String name, final String url, final String description) {

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

}

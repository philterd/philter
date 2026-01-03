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

import com.google.gson.Gson;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H5;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;

@Route(value = "", layout = MainLayout.class)
@PageTitle("Philter - Dashboard")
public class DashboardView extends AbstractView {

    private static final Logger LOGGER = LogManager.getLogger(DashboardView.class);

    @Autowired
    public DashboardView(final Gson gson) {

        setSizeFull();
        setPadding(true);

        final VerticalLayout pageVerticalLayout = new VerticalLayout();
        pageVerticalLayout.setSizeFull();

        pageVerticalLayout.add(new H1("Philter Dashboard"));

        pageVerticalLayout.add(new H2("Usage Metrics"));
        pageVerticalLayout.add(new Paragraph("Metrics are not persistent and reset upon Philter restart."));

        pageVerticalLayout.add(new H2("Philter SDKs"));

        final HorizontalLayout sdkRow = new HorizontalLayout();
        sdkRow.setWidthFull();

        sdkRow.add(createSdkItem("CLI", "Command Line", "https://github.com/philterd/philter-cli", "Filter text from the command line."));
        sdkRow.add(createSdkItem("SDK", "Java", "https://github.com/philterd/philter-sdk-java", "Filter text from your Java apps"));
        sdkRow.add(createSdkItem("SDK", ".NET", "https://github.com/philterd/philter-sdk-net", "Filter text from your .NET apps"));
        sdkRow.add(createSdkItem("SDK", "Golang", "https://github.com/philterd/philter-sdk-golang", "Filter text from your Golang apps"));

        pageVerticalLayout.add(sdkRow);

        add(pageVerticalLayout);
        add(getFooter());

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

}

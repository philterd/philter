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
package ai.philterd.philter.views.widgets;

import ai.philterd.philter.services.usage.OpenSearchRedactionsUsageService;
import ai.philterd.philter.services.usage.apirequests.ApiRequestsUsageService;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CommonWidgets {

    public static Span getLink(final String linkText, final String linkDestination, final boolean openInNewWindow) {

        final Anchor link = new Anchor(linkDestination, linkText);

        if(openInNewWindow) {
            link.setTarget("_blank");
        }

        final Span span = new Span(link);
        span.getStyle().set("font-size", "var(--lumo-font-size-s)");
        span.getStyle().set("color", "var(--lumo-secondary-text-color)");

        return span;

    }

    public static Span getContactSupportLink() {

        final Anchor link = new Anchor("https://www.philterd.ai/support/", "Support");
        link.setTarget("_blank");

        final Span span = new Span(link);
        span.getStyle().set("font-size", "var(--lumo-font-size-s)");
        span.getStyle().set("color", "var(--lumo-secondary-text-color)");

        return span;

    }

    public static HorizontalLayout getFooter() {

        final Image logoImage = new Image("/public/philterd.png", "Philter");
        logoImage.setWidth("100px");
        logoImage.getStyle().set("cursor", "pointer");
        logoImage.addClickListener(click -> {
            UI.getCurrent().navigate("https://www.philterd.ai/");
        });

        // --- Copyright Label ---
        final Span copyright = new Span("© 2026 Philterd, LLC. All rights reserved.");
        copyright.getStyle().set("font-size", "var(--lumo-font-size-s)");
        copyright.getStyle().set("color", "var(--lumo-secondary-text-color)");

        final HorizontalLayout footer = new HorizontalLayout();
        footer.setPadding(true);
        footer.setAlignItems(FlexComponent.Alignment.END);
        footer.add(logoImage);
        footer.add(copyright);
        footer.add(getContactSupportLink());
        footer.add(getLink("Documentation", "/public/docs/index.html", true));

        final Anchor link = new Anchor("/public/docs/mistakes.html", "Redactions can include mistakes - learn more.");
        link.setTarget("_blank");

        final Span mistakesSpan = new Span(link);
        mistakesSpan.getStyle().set("font-size", "var(--lumo-font-size-s)");
        mistakesSpan.getStyle().set("color", "var(--lumo-secondary-text-color)");
        footer.add(mistakesSpan);

        return footer;

    }

    public static Div buildTokenCountsLastXDays(final OpenSearchRedactionsUsageService openSearchRedactionsService, final int previousDays) throws IOException {

        final Map<String, Long> tokenCounts = openSearchRedactionsService.getTokensPreviousXDays(previousDays);
        return createTimeSeriesWidget(tokenCounts, "Token Counts for Last " + previousDays + " Days", "Tokens");

    }

    public static Div buildRedactionCountsLastXDays(final OpenSearchRedactionsUsageService openSearchRedactionsService, final int previousDays) throws IOException {

        final Map<String, Long> redactions = openSearchRedactionsService.getRedactionsPreviousXDays(previousDays);
        return createTimeSeriesWidget(redactions, "Redactions for Last " + previousDays + " Days", "Redactions");

    }

    public static Div buildStandardApiRequestsLastXDays(final ApiRequestsUsageService apiRequestsUsageService, final int previousDays) throws IOException {

        final Map<String, Long> tokenCounts = apiRequestsUsageService.getApiRequestsLastXDays(previousDays);
        return createTimeSeriesWidget(tokenCounts, "API Requests for Last " + previousDays + " Days", "Requests");

    }

    private static Div createTimeSeriesWidget(final Map<String, Long> data, final String widgetTitle, final String valueColumnLabel) {

        final List<TimeSeriesRow> rows = new ArrayList<>();
        data.forEach((date, value) -> rows.add(new TimeSeriesRow(date, value)));

        final Grid<TimeSeriesRow> grid = new Grid<>(TimeSeriesRow.class, false);
        grid.addColumn(TimeSeriesRow::date).setHeader("Date").setAutoWidth(true);
        grid.addColumn(TimeSeriesRow::value).setHeader(valueColumnLabel).setAutoWidth(true);
        grid.setItems(rows);
        grid.setAllRowsVisible(true);

        final VerticalLayout layout = new VerticalLayout();
        layout.setPadding(true);
        layout.add(new H4(widgetTitle));
        layout.add(grid);

        final Div div = new Div(layout);
        div.getStyle().set("border", "1px solid var(--lumo-contrast-10pct)");
        div.getStyle().set("border-radius", "var(--lumo-border-radius-m)");
        div.getStyle().set("padding", "var(--lumo-space-s)");
        return div;

    }

    private record TimeSeriesRow(String date, Long value) {}

}

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
import com.vaadin.flow.component.charts.Chart;
import com.vaadin.flow.component.charts.model.Configuration;
import com.vaadin.flow.component.charts.model.DataSeries;
import com.vaadin.flow.component.charts.model.DataSeriesItem;
import com.vaadin.flow.component.charts.model.PlotOptionsLine;
import com.vaadin.flow.component.charts.model.Tooltip;
import com.vaadin.flow.component.charts.model.XAxis;
import com.vaadin.flow.component.charts.model.YAxis;
import com.vaadin.flow.component.dashboard.DashboardWidget;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;

import java.io.IOException;
import java.util.Map;

public class CommonWidgets {

    public static Span getLink(final String linkText, final String linkDestination, final boolean openInNewWindow) {

        final Anchor link = new Anchor(linkDestination, linkText);

        if(openInNewWindow) {
            link.setTarget("_blank");
        }

        final Span span = new Span(link);
        span.getStyle().set("font-size", "var(--lumo-font-size-m)");
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

        // --- Copyright Label ---
        final Span copyright = new Span("© 2026 Philterd, LLC. All rights reserved.");
        copyright.getStyle().set("font-size", "var(--lumo-font-size-s)");
        copyright.getStyle().set("color", "var(--lumo-secondary-text-color)");

        final HorizontalLayout footer = new HorizontalLayout();
        footer.setPadding(true);
        footer.setAlignItems(FlexComponent.Alignment.END);
        footer.add(copyright);
        footer.add(getContactSupportLink());

        final Anchor link = new Anchor("https://docs.philterd.ai/mistakes.html", "Redactions can include mistakes - learn more.");
        link.setTarget("_blank");

        final Span mistakesSpan = new Span(link);
        mistakesSpan.getStyle().set("font-size", "var(--lumo-font-size-s)");
        mistakesSpan.getStyle().set("color", "var(--lumo-secondary-text-color)");
        footer.add(mistakesSpan);

        return footer;

    }

    public static DashboardWidget buildTokenCountsLastXDays(final OpenSearchRedactionsUsageService openSearchRedactionsService, final int previousDays) throws IOException {

        final Map<String, Long> tokenCounts = openSearchRedactionsService.getTokensPreviousXDays(previousDays);
        return createTimeSeriesWidget(tokenCounts, "Token Counts", "Token Counts for Last " + previousDays + " Days", "Date", "Token", "tokens");

    }

    public static DashboardWidget buildRedactionCountsLastXDays(final OpenSearchRedactionsUsageService openSearchRedactionsService, final int previousDays) throws IOException {

        final Map<String, Long> redactions = openSearchRedactionsService.getRedactionsPreviousXDays(previousDays);
        return createTimeSeriesWidget(redactions,  "Redactions", "Redactions for Last " + previousDays + " Days","Date", "Redactions", "redactions");

    }

    public static DashboardWidget buildStandardApiRequestsLastXDays(final ApiRequestsUsageService apiRequestsUsageService, final int previousDays) throws IOException {

        final Map<String, Long> tokenCounts = apiRequestsUsageService.getApiRequestsLastXDays(previousDays);
        return createTimeSeriesWidget(tokenCounts, "API Requests", "API Requests for Last " + previousDays + " Days", "Date", "Standard API Requests", "requests");

    }

    private static DashboardWidget createTimeSeriesWidget(final Map<String, Long> data, final String title,
                                                          final String widgetTitle, final String xAxisLabel,
                                                          final String yAxisLabel, final String tooltipSuffix) {

        final Chart chart = new Chart();
        Configuration configuration = chart.getConfiguration();
        configuration.setTitle(title);

        final XAxis xAxis = new XAxis();
        xAxis.setCategories(data.keySet().toArray(new String[0]));
        xAxis.setTitle(xAxisLabel);
        configuration.addxAxis(xAxis);

        final YAxis yAxis = new YAxis();
        yAxis.setMin(0);
        yAxis.setTitle(yAxisLabel);
        configuration.addyAxis(yAxis);

        final Tooltip tooltip = new Tooltip();
        tooltip.setValueSuffix(" " + tooltipSuffix);
        configuration.setTooltip(tooltip);

        final DataSeries series = new DataSeries(title);
        final PlotOptionsLine plotOptions = new PlotOptionsLine();
        plotOptions.setAllowPointSelect(false);
        series.setPlotOptions(plotOptions);

        data.forEach((date, tokens) -> {
            series.add(new DataSeriesItem(date, tokens)); // Add token count as the Y-value
        });

        configuration.setSeries(series);

        chart.drawChart();

        final Div div = new Div(chart);

        final DashboardWidget dashboardWidget = new DashboardWidget(widgetTitle);
        dashboardWidget.setContent(div);

        return dashboardWidget;

    }

}

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

import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.services.usage.OpenSearchRedactionsUsageService;
import ai.philterd.philter.services.usage.UsageService;
import ai.philterd.philter.services.usage.apirequests.ApiRequestsUsageService;
import ai.philterd.philter.views.widgets.CommonWidgets;
import com.mongodb.client.MongoClient;
import com.vaadin.flow.component.dashboard.Dashboard;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.vaadin.firitin.components.DynamicFileDownloader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Route(value = "metrics")
@PageTitle("Philter - Metrics")
@PermitAll
public class MetricsView extends AbstractRestrictedView {

    private static final Logger LOGGER = LogManager.getLogger(MetricsView.class);

    @Override
    public String getHelpMarkdownText() {
        return "Placeholder for metrics help text.";
    }

    @Autowired
    public MetricsView(final MongoClient mongoClient, final EncryptionService encryptionService, final AuditEventPublisher auditEventPublisher,
                       final OpenSearchRedactionsUsageService openSearchRedactionsService, final ApiRequestsUsageService apiRequestsUsageService) throws IOException {
        super(mongoClient, encryptionService, auditEventPublisher,false);

        final VerticalLayout pageVerticalLayout = new VerticalLayout();
        pageVerticalLayout.setSizeFull();
        pageVerticalLayout.add(getTitle("Metrics"));

        final Dashboard dashboard = new Dashboard();
        dashboard.setSizeUndefined();
        dashboard.setMinimumColumnWidth("150px");
        dashboard.setMaximumColumnCount(2);
        dashboard.setSizeFull();

        // Usage widgets
        dashboard.add(CommonWidgets.buildTokenCountsLastXDays(openSearchRedactionsService, 30));
        dashboard.add(CommonWidgets.buildRedactionCountsLastXDays(openSearchRedactionsService,30));
        dashboard.add(CommonWidgets.buildStandardApiRequestsLastXDays(apiRequestsUsageService,  30));

        // Download CSV of usage

        final DynamicFileDownloader downloadUsageLink = new DynamicFileDownloader("Download All Usage for Last 180 Days as CSV", outputStream -> {

            final UsageService usageService = new UsageService(apiRequestsUsageService, openSearchRedactionsService);
            final List<String> csvReport = usageService.buildUsageCSV(180);

            final StringBuilder combinedString = new StringBuilder();
            for (final String s : csvReport) {
                combinedString.append(s);
            }
            final String finalString = combinedString.toString();

            // Convert the final string to a byte array using UTF-8 encoding
            byte[] byteArray = finalString.getBytes(StandardCharsets.UTF_8);

            outputStream.write(byteArray);

        }).withFileNameGenerator(r -> "usage_report.csv");

        pageVerticalLayout.add(dashboard);
        pageVerticalLayout.add(downloadUsageLink);
        pageVerticalLayout.add(CommonWidgets.getFooter());
        pageVerticalLayout.setSizeFull();

        setContent(pageVerticalLayout);

    }

}

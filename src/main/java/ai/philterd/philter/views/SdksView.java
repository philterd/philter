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
import ai.philterd.philter.views.widgets.CommonWidgets;
import com.mongodb.client.MongoClient;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H5;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.TabSheet;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

@Route(value = "sdks")
@PageTitle("Philter - SDKs")
@PermitAll
public class SdksView extends AbstractRestrictedView {

    public SdksView(final MongoClient mongoClient, final EncryptionService encryptionService,
                    final AuditEventPublisher auditEventPublisher) {
        super(mongoClient, encryptionService, auditEventPublisher);

        final HorizontalLayout sdkRow = new HorizontalLayout();
        sdkRow.setWidthFull();
        sdkRow.add(createSdkItem("CLI", "Command Line", "https://github.com/philterd/philter-cli", "Filter text from the command line."));
        sdkRow.add(createSdkItem("SDK", "Java", "https://github.com/philterd/philter-sdk-java", "Filter text from your Java apps"));
        sdkRow.add(createSdkItem("SDK", ".NET", "https://github.com/philterd/philter-sdk-net", "Filter text from your .NET apps"));
        sdkRow.add(createSdkItem("SDK", "Golang", "https://github.com/philterd/philter-sdk-golang", "Filter text from your Golang apps"));

        final VerticalLayout sdksVerticalLayout = new VerticalLayout();
        sdksVerticalLayout.add(sdkRow);
        sdksVerticalLayout.setSizeFull();

        final TabSheet tabSheet = new TabSheet();
        tabSheet.add("Client SDKs", sdksVerticalLayout);
        tabSheet.setSizeFull();

        final VerticalLayout pageVerticalLayout = new VerticalLayout();
        pageVerticalLayout.add(getTitle("SDKs"));
        pageVerticalLayout.add(tabSheet);
        pageVerticalLayout.add(CommonWidgets.getFooter());
        pageVerticalLayout.setSizeFull();

        final HorizontalLayout pageHorizontalLayout = new HorizontalLayout();
        pageHorizontalLayout.add(pageVerticalLayout);
        pageHorizontalLayout.setSizeFull();

        setContent(pageHorizontalLayout);

    }

    private Component createSdkItem(final String type, final String name, final String url, final String description) {

        final VerticalLayout item = new VerticalLayout();
        item.setPadding(false);
        item.setSpacing(false);

        final Span typeSpan = new Span(type);
        typeSpan.getStyle().set("font-size", "0.7rem");
        typeSpan.getStyle().set("font-weight", "bold");
        typeSpan.getStyle().set("color", "#4e73df");

        final H5 nameH5 = new H5(name);
        nameH5.getStyle().set("margin", "0");

        final Anchor link = new Anchor(url, url);
        link.setTarget("_blank");
        link.getStyle().set("font-size", "0.8rem");

        final Paragraph desc = new Paragraph(description);
        desc.getStyle().set("font-size", "0.8rem");

        item.add(typeSpan, nameH5, link, desc);

        return item;

    }

}

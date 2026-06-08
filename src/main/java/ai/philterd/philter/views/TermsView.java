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
import ai.philterd.philter.data.entities.GlobalTermsEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.services.GlobalTermsDataService;
import ai.philterd.philter.model.Source;
import ai.philterd.philter.services.RequestIdGenerator;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.views.widgets.CommonWidgets;
import com.mongodb.client.MongoClient;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.TabSheet;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

import java.util.stream.Collectors;

@Route(value = "terms")
@PageTitle("Philter - Terms")
@PermitAll
public class TermsView extends AbstractRestrictedView {

    public TermsView(final MongoClient mongoClient, final EncryptionService encryptionService,
                     final AuditEventPublisher auditEventPublisher, final GlobalTermsDataService globalTermsService) {
        super(mongoClient, encryptionService, auditEventPublisher);

        final UserEntity userEntity = getCurrentUser();

        final GlobalTermsEntity globalTermsEntity = globalTermsService.find(null);

        final TextArea termsToAlwaysRedactTextArea = new TextArea();
        termsToAlwaysRedactTextArea.setSizeFull();
        if (globalTermsEntity != null) {
            termsToAlwaysRedactTextArea.setValue(String.join("\n", globalTermsEntity.getTermsToAlwaysRedact()));
        }

        final TextArea termsToNeverRedactTextArea = new TextArea();
        termsToNeverRedactTextArea.setSizeFull();
        if (globalTermsEntity != null) {
            termsToNeverRedactTextArea.setValue(String.join("\n", globalTermsEntity.getTermsToNeverRedact()));
        }

        final Button saveGlobalTermsButton = new Button("Save Terms");
        saveGlobalTermsButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        saveGlobalTermsButton.addClickListener(event -> {
            globalTermsService.saveOrUpdate(RequestIdGenerator.generate(), userEntity.getId(),
                    termsToAlwaysRedactTextArea.getValue().lines().collect(Collectors.toList()),
                    termsToNeverRedactTextArea.getValue().lines().collect(Collectors.toList()), Source.WEBUI.getSource());
            showSuccessNotification("Terms saved.");
        });

        final VerticalLayout globalTermsVerticalLayout = new VerticalLayout();
        globalTermsVerticalLayout.add(new H3("Terms to Always Redact"));
        globalTermsVerticalLayout.add(new Span("These terms, one per line, will always be redacted regardless of the selected policy."));
        globalTermsVerticalLayout.add(termsToAlwaysRedactTextArea);
        globalTermsVerticalLayout.add(new H3("Terms to Never Redact"));
        globalTermsVerticalLayout.add(new Span("These terms, one per line, will never be redacted regardless of the selected policy."));
        globalTermsVerticalLayout.add(termsToNeverRedactTextArea);
        globalTermsVerticalLayout.add(CommonWidgets.getLink("Learn about the options available for fuzzy-matching and other options.", "/public/docs/redaction/global_terms.html", true));
        globalTermsVerticalLayout.add(saveGlobalTermsButton);
        globalTermsVerticalLayout.setSizeFull();

        final TabSheet tabSheet = new TabSheet();
        tabSheet.add("Global Terms", globalTermsVerticalLayout);
        tabSheet.setSizeFull();

        final VerticalLayout div = new VerticalLayout();
        div.add(getTitle("Terms"));
        div.add(tabSheet);
        div.add(CommonWidgets.getFooter());
        div.setSizeFull();

        final HorizontalLayout pageHorizontalLayout = new HorizontalLayout();
        pageHorizontalLayout.add(div);
        pageHorizontalLayout.setSizeFull();

        setContent(pageHorizontalLayout);

    }

}

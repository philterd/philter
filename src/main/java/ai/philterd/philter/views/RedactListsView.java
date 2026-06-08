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
import ai.philterd.philter.data.entities.RedactListsEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.services.RedactListsDataService;
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

@Route(value = "redact-lists")
@PageTitle("Philter - Always/Never Redact Lists")
@PermitAll
public class RedactListsView extends AbstractRestrictedView {

    public RedactListsView(final MongoClient mongoClient, final EncryptionService encryptionService,
                     final AuditEventPublisher auditEventPublisher, final RedactListsDataService redactListsService) {
        super(mongoClient, encryptionService, auditEventPublisher);

        final UserEntity userEntity = getCurrentUser();

        // These lists are per-user: the save below and the redaction-time lookup (RedactionService)
        // both key by the owning user's id, so the displayed lists must be scoped to the signed-in
        // user too. Reading with a null id would show whatever orphan (owner-less) document happens
        // to exist rather than this user's own lists.
        final RedactListsEntity redactListsEntity = redactListsService.find(userEntity.getId());

        final TextArea termsToAlwaysRedactTextArea = new TextArea();
        termsToAlwaysRedactTextArea.setSizeFull();
        if (redactListsEntity != null) {
            termsToAlwaysRedactTextArea.setValue(String.join("\n", redactListsEntity.getTermsToAlwaysRedact()));
        }

        final TextArea termsToNeverRedactTextArea = new TextArea();
        termsToNeverRedactTextArea.setSizeFull();
        if (redactListsEntity != null) {
            termsToNeverRedactTextArea.setValue(String.join("\n", redactListsEntity.getTermsToNeverRedact()));
        }

        final Button saveButton = new Button("Save Lists");
        saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        saveButton.addClickListener(event -> {
            redactListsService.saveOrUpdate(RequestIdGenerator.generate(), userEntity.getId(),
                    termsToAlwaysRedactTextArea.getValue().lines().collect(Collectors.toList()),
                    termsToNeverRedactTextArea.getValue().lines().collect(Collectors.toList()), Source.WEBUI.getSource());
            showSuccessNotification("Lists saved.");
        });

        final VerticalLayout redactListsVerticalLayout = new VerticalLayout();

        // Make the scope explicit: these lists apply across all of your own policies and contexts, but
        // only within your own account — they are never shared with or applied to other users.
        final Span scopeNote = new Span("These lists apply only to your own account — across all of your "
                + "redaction policies and contexts. They do not affect any other user's redactions.");
        scopeNote.getStyle().set("font-weight", "600");
        redactListsVerticalLayout.add(scopeNote);

        redactListsVerticalLayout.add(new H3("Terms to Always Redact"));
        redactListsVerticalLayout.add(new Span("These terms, one per line, will always be redacted in your account's redactions, regardless of the selected policy."));
        redactListsVerticalLayout.add(termsToAlwaysRedactTextArea);
        redactListsVerticalLayout.add(new H3("Terms to Never Redact"));
        redactListsVerticalLayout.add(new Span("These terms, one per line, will never be redacted in your account's redactions, regardless of the selected policy."));
        redactListsVerticalLayout.add(termsToNeverRedactTextArea);
        redactListsVerticalLayout.add(CommonWidgets.getLink("Learn about the options available for fuzzy-matching and other options.", "/public/docs/redaction/redact_lists.html", true));
        redactListsVerticalLayout.add(saveButton);
        redactListsVerticalLayout.setSizeFull();

        final TabSheet tabSheet = new TabSheet();
        tabSheet.add("Always/Never Redact Lists", redactListsVerticalLayout);
        tabSheet.setSizeFull();

        final VerticalLayout div = new VerticalLayout();
        div.add(getTitle("Always/Never Redact Lists"));
        div.add(tabSheet);
        div.add(CommonWidgets.getFooter());
        div.setSizeFull();

        final HorizontalLayout pageHorizontalLayout = new HorizontalLayout();
        pageHorizontalLayout.add(div);
        pageHorizontalLayout.setSizeFull();

        setContent(pageHorizontalLayout);

    }

}

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
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.TabSheet;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

import java.util.List;
import java.util.stream.Collectors;

@Route(value = "redact-lists")
@PageTitle("Philter - Always/Never Redact Lists")
@PermitAll
public class RedactListsView extends AbstractRestrictedView {

    public RedactListsView(final MongoClient mongoClient, final EncryptionService encryptionService,
                     final AuditEventPublisher auditEventPublisher, final RedactListsDataService redactListsService) {
        super(mongoClient, encryptionService, auditEventPublisher);

        final UserEntity userEntity = getCurrentUser();

        // These lists are per-user: the saves below and the redaction-time lookup (RedactionService)
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

        // --- Always Redact List tab ---
        final Button saveAlwaysButton = new Button("Save Always Redact List");
        saveAlwaysButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        saveAlwaysButton.addClickListener(event -> {
            // Save only this list; re-read the current never-redact list so saving here does not clobber it.
            final RedactListsEntity current = redactListsService.find(userEntity.getId());
            final List<String> neverTerms = current != null ? current.getTermsToNeverRedact() : List.<String>of();
            redactListsService.saveOrUpdate(RequestIdGenerator.generate(), userEntity.getId(),
                    termsToAlwaysRedactTextArea.getValue().lines().collect(Collectors.toList()),
                    neverTerms, Source.WEBUI.getSource());
            showSuccessNotification("Always-redact list saved.");
        });

        final Span alwaysDescription = new Span("These terms, one per line, will always be redacted in your account's redactions, regardless of the selected policy. ");
        alwaysDescription.add(CommonWidgets.getLink("Learn more about redact lists.", "/public/docs/redaction/redact_lists.html", true));

        final VerticalLayout alwaysLayout = new VerticalLayout();
        alwaysLayout.add(alwaysDescription);
        alwaysLayout.add(termsToAlwaysRedactTextArea);
        alwaysLayout.add(saveAlwaysButton);
        alwaysLayout.setSizeFull();

        // --- Never Redact List tab ---
        final Button saveNeverButton = new Button("Save Never Redact List");
        saveNeverButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        saveNeverButton.addClickListener(event -> {
            // Save only this list; re-read the current always-redact list so saving here does not clobber it.
            final RedactListsEntity current = redactListsService.find(userEntity.getId());
            final List<String> alwaysTerms = current != null ? current.getTermsToAlwaysRedact() : List.<String>of();
            redactListsService.saveOrUpdate(RequestIdGenerator.generate(), userEntity.getId(),
                    alwaysTerms,
                    termsToNeverRedactTextArea.getValue().lines().collect(Collectors.toList()), Source.WEBUI.getSource());
            showSuccessNotification("Never-redact list saved.");
        });

        final Span neverDescription = new Span("These terms, one per line, will never be redacted in your account's redactions, regardless of the selected policy. ");
        neverDescription.add(CommonWidgets.getLink("Learn more about redact lists.", "/public/docs/redaction/redact_lists.html", true));

        final VerticalLayout neverLayout = new VerticalLayout();
        neverLayout.add(neverDescription);
        neverLayout.add(termsToNeverRedactTextArea);
        neverLayout.add(saveNeverButton);
        neverLayout.setSizeFull();

        final TabSheet tabSheet = new TabSheet();
        tabSheet.add("Always Redact List", alwaysLayout);
        tabSheet.add("Never Redact List", neverLayout);
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

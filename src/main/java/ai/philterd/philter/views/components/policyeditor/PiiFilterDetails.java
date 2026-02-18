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
package ai.philterd.philter.views.components.policyeditor;

import ai.philterd.phileas.model.filtering.FilterType;
import ai.philterd.phileas.services.anonymization.AnonymizationMethod;
import ai.philterd.philter.services.policies.SimplifiedCondition;
import ai.philterd.philter.services.policies.SimplifiedPolicy;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;
import com.vaadin.flow.component.textfield.IntegerField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class PiiFilterDetails extends VerticalLayout {

    private static final Logger LOGGER = LoggerFactory.getLogger(PiiFilterDetails.class);

    private final Checkbox filterCheckbox;
    private final RadioButtonGroup<String> redactionActionGroup;
    private final IntegerField confidenceField;
    private final RadioButtonGroup<String> redactionScopeGroup;
    private final RadioButtonGroup<String> anonymizationMethodGroup;

    public boolean isChecked() {
        return filterCheckbox.getValue();
    }

    public String getSelectedAction() {
        return redactionActionGroup.getValue();
    }

    public String getSelectedRedactionScope() {

        if(redactionScopeGroup.getValue() == null) {

            // Default to "Document" if not set.
            return SimplifiedPolicy.DISAMBIGUATION_SCOPE_DOCUMENT;

        } else {

            if (redactionScopeGroup.getValue().equalsIgnoreCase(SimplifiedPolicy.DISAMBIGUATION_SCOPE_DOCUMENT)) {
                return SimplifiedPolicy.DISAMBIGUATION_SCOPE_DOCUMENT;
            } else {
                return SimplifiedPolicy.DISAMBIGUATION_SCOPE_CONTEXT;
            }
        }

    }

    public AnonymizationMethod getAnonymizationMethod() {

        if(anonymizationMethodGroup.getValue().equalsIgnoreCase("UUID")) {
            return AnonymizationMethod.UUID;
        } else if(anonymizationMethodGroup.getValue().equalsIgnoreCase("Realistic Replace")) {
            return AnonymizationMethod.REALISTIC;
        } else if(anonymizationMethodGroup.getValue().equalsIgnoreCase("From a List")) {
            return AnonymizationMethod.FROM_LIST;
        }

        // Default to UUID if not set.
        return AnonymizationMethod.UUID;

    }

    public int getConfidenceThreshold() {
        // Return 85 if not set.
        if (confidenceField.getValue() == null) {
            return 85;
        }
        return confidenceField.getValue();
    }

    public void setConfidenceThresholdInvalid() {
        confidenceField.setInvalid(true);
        confidenceField.setErrorMessage("Confidence threshold value must be between 1 and 100.");
    }

    public boolean validateConfidenceThreshold() {

        if(!confidenceField.isEmpty()) {

            final double confidenceThreshold = getConfidenceThreshold();
            LOGGER.info("Confidence threshold value is {} ", confidenceThreshold);
            final boolean valid = (confidenceThreshold >= 0 && confidenceThreshold <= 100);

            if(!valid) {
                LOGGER.info("Confidence threshold value {} is not valid", confidenceField.getValue());
            }

            return valid;

        }

        return false;

    }

    public PiiFilterDetails(final FilterType filterType, final SimplifiedPolicy simplifiedPolicy, final String piiName, List<String> redactionOptions) {

        filterCheckbox = new Checkbox(piiName);

        final VerticalLayout contentLayout = new VerticalLayout();
        contentLayout.setSpacing(false);
        contentLayout.setPadding(false);

        this.confidenceField = new IntegerField();
        confidenceField.setLabel("Confidence Threshold:");
        confidenceField.setHelperText("Confidence threshold value between 1 and 100. We recommend 85 as a starting value.");
        confidenceField.setTooltipText("Entities detected as having a confidence less than this value will not be redacted.");
        confidenceField.setMin(1);
        confidenceField.setMax(100);
        confidenceField.setStep(1);
        confidenceField.setEnabled(false);
        confidenceField.setWidthFull();

        if(simplifiedPolicy.getFilters().containsKey(filterType)) {

            final SimplifiedCondition simplifiedCondition = simplifiedPolicy.getFilters().get(filterType).get(0).getSimplifiedCondition();
            if(simplifiedCondition != null) {
                confidenceField.setValue(simplifiedPolicy.getFilters().get(filterType).get(0).getSimplifiedCondition().getConfidence());
            } else {
                confidenceField.setValue(85);
            }

        } else {
            confidenceField.setValue(85);
        }

        contentLayout.add(confidenceField);

        redactionActionGroup = new RadioButtonGroup<>();
        redactionActionGroup.setLabel("Choose Action:");

        redactionActionGroup.setItems(redactionOptions);
        if (simplifiedPolicy.getFilters().containsKey(filterType)) {
            // TODO: Only looking at the first strategy right now.
            final String strategy = simplifiedPolicy.getFilters().get(filterType).get(0).getStrategy();
            redactionActionGroup.setValue(strategy);
        }

        final HorizontalLayout redactionScopeHorizontalLayout = new HorizontalLayout();
        redactionScopeGroup = new RadioButtonGroup<>();
        redactionScopeGroup.setLabel("Anonymization Scope:");
        redactionScopeGroup.setItems("Document", "Context");
        redactionScopeGroup.setValue("Document");
        if (simplifiedPolicy.getFilters().containsKey(filterType)) {
            // TODO: Only looking at the first strategy right now.
            final String redactionScope = simplifiedPolicy.getFilters().get(filterType).get(0).getRedactionScope();
            redactionScopeGroup.setValue(redactionScope);
        }
        redactionScopeHorizontalLayout.add(redactionScopeGroup);

        anonymizationMethodGroup = new RadioButtonGroup<>();
        anonymizationMethodGroup.setLabel("Anonymization Method:");
        anonymizationMethodGroup.setItems("Realistic", "UUID");
        anonymizationMethodGroup.setValue("UUID");

        if (simplifiedPolicy.getFilters().containsKey(filterType)) {
            final String method = simplifiedPolicy.getFilters().get(filterType).get(0).getAnonymizationMethod();

            if(method != null) {
                if (method.equalsIgnoreCase(AnonymizationMethod.REALISTIC.getValue())) {
                    anonymizationMethodGroup.setValue("Realistic");
                } else {
                    anonymizationMethodGroup.setValue("UUID");
                }
            }
        }

        redactionActionGroup.addValueChangeListener(event -> {

            // Only enable the redaction scope and anonymization method if Anonymize is selected.
            if(event.getValue() != null && event.getValue().equalsIgnoreCase("Anonymize")) {
                redactionScopeGroup.setEnabled(true);
                anonymizationMethodGroup.setEnabled(true);
            } else {
                redactionScopeGroup.setEnabled(false);
                anonymizationMethodGroup.setEnabled(false);
            }

        });

        contentLayout.add(redactionActionGroup);

        contentLayout.add(redactionScopeHorizontalLayout);

        contentLayout.add(anonymizationMethodGroup);



        // Determine whether the checkbox is checked based on the policy's identifiers.
        if (simplifiedPolicy.getFilters().containsKey(filterType)) {

            filterCheckbox.setValue(true);
            confidenceField.setEnabled(true);
            redactionActionGroup.setEnabled(true);

            // Only enable the redaction scope and anonymization method if Anonymize is selected.
            if(redactionActionGroup.getValue() != null && redactionActionGroup.getValue().equalsIgnoreCase("Anonymize")) {
                redactionScopeGroup.setEnabled(true);
                anonymizationMethodGroup.setEnabled(true);
            } else {
                redactionScopeGroup.setEnabled(false);
                anonymizationMethodGroup.setEnabled(false);
            }

        } else {

            filterCheckbox.setValue(false);
            confidenceField.setEnabled(false);
            redactionActionGroup.setEnabled(false);
            redactionScopeGroup.setEnabled(false);
            anonymizationMethodGroup.setEnabled(false);

        }

        final Details piiDetails = new Details(filterCheckbox, contentLayout);
        piiDetails.setOpened(false);

        // Add a ValueChangeListener to the main PII checkbox
        // This listener will enable/disable the radio group and open/close the details based on the checkbox's state
        filterCheckbox.addValueChangeListener(event -> {
            boolean isChecked = event.getValue();
            redactionActionGroup.setEnabled(isChecked); // Enable if checked, disable if unchecked
            confidenceField.setEnabled(isChecked);
            redactionScopeGroup.setEnabled(isChecked);
            anonymizationMethodGroup.setEnabled(isChecked);
            piiDetails.setOpened(isChecked); // Open details if checked, close if unchecked

            // Optionally, clear the selection when disabled
            if (isChecked) {

                // When enabled, re-select REDACT if nothing is selected
                if (redactionActionGroup.getValue() == null) {
                    redactionActionGroup.setValue("Redact");
                }

                if (redactionScopeGroup.getValue() == null) {
                    redactionScopeGroup.setValue("Document");
                }

                if (anonymizationMethodGroup.getValue() == null) {
                    anonymizationMethodGroup.setValue("UUID");
                }

            }
        });

        add(piiDetails);

    }

}

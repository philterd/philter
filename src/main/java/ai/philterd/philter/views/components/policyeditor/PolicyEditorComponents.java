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
import ai.philterd.philter.services.policies.SimplifiedCondition;
import ai.philterd.philter.services.policies.SimplifiedPolicy;
import ai.philterd.philter.services.policies.SimplifiedStrategy;
import ai.philterd.philter.views.widgets.CommonWidgets;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.component.textfield.TextArea;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PolicyEditorComponents {

    private final Gson gson;

    private TextArea policyJsonEditor;

    private final TextArea ignoredTermsTextArea;
    private final TextArea termsToAlwaysRedactTextArea;
    private final ComboBox<String> termsToAlwaysRedactStrategyComboBox;

    private final Checkbox highlightChangesCheckBox;
    private final Checkbox turnOnRevisionsCheckBox;
    private final ComboBox<String> disambiguationScopeCheckBox;
    
    // PDF options fields
    private final NumberField pdfScaleField;
    private final IntegerField pdfDpiField;
    private final NumberField pdfCompressionQualityField;

    private PiiFilterDetails firstNameFilter;
    private PiiFilterDetails surnamesFilter;
    private PiiFilterDetails personsNamesFilter;
    private PiiFilterDetails agesFilter;
    private PiiFilterDetails bankRoutingNumbersFilter;
    private PiiFilterDetails bitcoinAddressesFilter;
    private PiiFilterDetails citiesFilter;
    private PiiFilterDetails countiesFilter;
    private PiiFilterDetails creditCardNumbersFilters;
    private PiiFilterDetails datesFilter;
    private PiiFilterDetails driversLicenseNumbersFilter;
    private PiiFilterDetails emailAddressFilter;
    private PiiFilterDetails ibanCodesFilter;
    private PiiFilterDetails ipAddressesFilter;
    private PiiFilterDetails macAddressesFilter;
    private PiiFilterDetails passportNumbersFilter;
    private PiiFilterDetails phoneNumbersFilter;
    private PiiFilterDetails ssnsAndTinsFilter;
    private PiiFilterDetails streetAddressesFilter;
    private PiiFilterDetails statesFilter;
    private PiiFilterDetails trackingNumbersFilter;
    private PiiFilterDetails urlsFilter;
    private PiiFilterDetails vinsFilter;
    private PiiFilterDetails zipCodesFilter;

    public PolicyEditorComponents() {
        this.gson = new GsonBuilder().setPrettyPrinting().create();

        this.highlightChangesCheckBox = new Checkbox("Highlight redactions in Microsoft Word documents.");
        this.turnOnRevisionsCheckBox = new Checkbox("Turn on revisions in Microsoft Word documents.");
        this.disambiguationScopeCheckBox = new ComboBox<>("Disambiguation Scope", List.of("Document", "Context"));

        this.ignoredTermsTextArea = new TextArea("Words or phrases to ignore");
        this.termsToAlwaysRedactTextArea = new TextArea("Words or phrases to always redact");
        
        // Initialize strategy ComboBox with available redaction strategies
        this.termsToAlwaysRedactStrategyComboBox = new ComboBox<>("Redaction Strategy");
        this.termsToAlwaysRedactStrategyComboBox.setItems("Redact", "Anonymize", "Mask");
        this.termsToAlwaysRedactStrategyComboBox.setValue("Redact");
        this.termsToAlwaysRedactStrategyComboBox.setHelperText("Select how terms in the always redact list should be redacted.");
        this.termsToAlwaysRedactStrategyComboBox.setWidthFull();
        this.termsToAlwaysRedactStrategyComboBox.setRequiredIndicatorVisible(true);
        this.termsToAlwaysRedactStrategyComboBox.setAllowCustomValue(false);
        
        // Initialize PDF options fields with default values
        this.pdfScaleField = new NumberField("Scale");
        this.pdfScaleField.setValue(1.0);
        this.pdfScaleField.setMin(0.0);
        this.pdfScaleField.setMax(1.0);
        this.pdfScaleField.setStep(0.1);
        this.pdfScaleField.setHelperText("Float value from 0 to 1 (default: 1.0)");
        
        this.pdfDpiField = new IntegerField("DPI");
        this.pdfDpiField.setValue(300);
        this.pdfDpiField.setMin(1);
        this.pdfDpiField.setMax(600);
        this.pdfDpiField.setStep(1);
        this.pdfDpiField.setHelperText("Integer value (default: 300)");
        
        this.pdfCompressionQualityField = new NumberField("Compression Quality");
        this.pdfCompressionQualityField.setValue(1.0);
        this.pdfCompressionQualityField.setMin(0.0);
        this.pdfCompressionQualityField.setMax(1.0);
        this.pdfCompressionQualityField.setStep(0.1);
        this.pdfCompressionQualityField.setHelperText("Float value from 0 to 1 (default: 1.0)");

    }

    public VerticalLayout getJsonPolicyEditor(final String initialJsonPolicy) {

        final Span jsonIstructionsSpan = new Span("Enter the policy below. Note this feature is for advanced use-cases in which the Policy Creator is not sufficient.");

        final Anchor anchor = new Anchor("https://docs.philterd.ai/redaction/policy_syntax.html", "View the redaction policy syntax.");
        anchor.setTarget("_blank");

        this.policyJsonEditor = new TextArea("Policy");
        this.policyJsonEditor.setSizeFull();
        this.policyJsonEditor.setValue(initialJsonPolicy);

        final VerticalLayout verticalLayout = new VerticalLayout(jsonIstructionsSpan, anchor, policyJsonEditor);
        verticalLayout.setSizeFull();
        return verticalLayout;

    }

    public VerticalLayout getJsonPolicyEditor() {
        return getJsonPolicyEditor("{}");
    }

    public String getPolicyRawJson() {
        return policyJsonEditor.getValue();
    }

    public TextArea getPolicyJsonEditor() {
        return policyJsonEditor;
    }

    public String buildPolicy(final String policyName, final String lens) {

        final Map<FilterType, List<SimplifiedStrategy>> filters = new HashMap<>();

        if (personsNamesFilter.isChecked()) {

            if(!personsNamesFilter.validateConfidenceThreshold()) {
                personsNamesFilter.setConfidenceThresholdInvalid();
                return null;
            }

            final String selectedStrategy = personsNamesFilter.getSelectedAction();
            final String redactionScope = personsNamesFilter.getSelectedRedactionScope();
            // TODO: Additional filter strategy parameters can go in a Map passed to the strategy.
            final SimplifiedStrategy simplifiedStrategy = new SimplifiedStrategy(selectedStrategy, new SimplifiedCondition(personsNamesFilter.getConfidenceThreshold()), redactionScope, personsNamesFilter.getAnonymizationMethod());
            filters.put(FilterType.PERSON, List.of(simplifiedStrategy)); // TODO: Allow for multiple strategies.

        }

        if (firstNameFilter.isChecked()) {

            if(!firstNameFilter.validateConfidenceThreshold()) {
                firstNameFilter.setConfidenceThresholdInvalid();
                return null;
            }

            final String selectedStrategy = firstNameFilter.getSelectedAction();
            final String redactionScope = firstNameFilter.getSelectedRedactionScope();
            // TODO: Additional filter strategy parameters can go in a Map passed to the strategy.
            final SimplifiedStrategy simplifiedStrategy = new SimplifiedStrategy(selectedStrategy, new SimplifiedCondition(firstNameFilter.getConfidenceThreshold()), redactionScope, firstNameFilter.getAnonymizationMethod());
            filters.put(FilterType.FIRST_NAME, List.of(simplifiedStrategy)); // TODO: Allow for multiple strategies.

        }

        if (surnamesFilter.isChecked()) {

            if(!surnamesFilter.validateConfidenceThreshold()) {
                surnamesFilter.setConfidenceThresholdInvalid();
                return null;
            }

            final String selectedStrategy = surnamesFilter.getSelectedAction();
            final String redactionScope = surnamesFilter.getSelectedRedactionScope();
            // TODO: Additional filter strategy parameters can go in a Map passed to the strategy.
            final SimplifiedStrategy simplifiedStrategy = new SimplifiedStrategy(selectedStrategy, new SimplifiedCondition(surnamesFilter.getConfidenceThreshold()), redactionScope, surnamesFilter.getAnonymizationMethod());
            filters.put(FilterType.SURNAME, List.of(simplifiedStrategy)); // TODO: Allow for multiple strategies.

        }

        if (agesFilter.isChecked()) {

            if(!agesFilter.validateConfidenceThreshold()) {
                agesFilter.setConfidenceThresholdInvalid();
                return null;
            }

            final String selectedStrategy = agesFilter.getSelectedAction();
            final String redactionScope = agesFilter.getSelectedRedactionScope();
            // TODO: Additional filter strategy parameters can go in a Map passed to the strategy.
            final SimplifiedStrategy simplifiedStrategy = new SimplifiedStrategy(selectedStrategy, new SimplifiedCondition(agesFilter.getConfidenceThreshold()), redactionScope, agesFilter.getAnonymizationMethod());
            filters.put(FilterType.AGE, List.of(simplifiedStrategy)); // TODO: Allow for multiple strategies.

        }

        if (bankRoutingNumbersFilter.isChecked()) {

            if(!bankRoutingNumbersFilter.validateConfidenceThreshold()) {
                bankRoutingNumbersFilter.setConfidenceThresholdInvalid();
                return null;
            }

            final String selectedStrategy = bankRoutingNumbersFilter.getSelectedAction();
            final String redactionScope = bankRoutingNumbersFilter.getSelectedRedactionScope();
            // TODO: Additional filter strategy parameters can go in a Map passed to the strategy.
            final SimplifiedStrategy simplifiedStrategy = new SimplifiedStrategy(selectedStrategy, new SimplifiedCondition(bankRoutingNumbersFilter.getConfidenceThreshold()), redactionScope, bankRoutingNumbersFilter.getAnonymizationMethod());
            filters.put(FilterType.BANK_ROUTING_NUMBER, List.of(simplifiedStrategy)); // TODO: Allow for multiple strategies.

        }

        if (bitcoinAddressesFilter.isChecked()) {

            if(!bitcoinAddressesFilter.validateConfidenceThreshold()) {
                bitcoinAddressesFilter.setConfidenceThresholdInvalid();
                return null;
            }

            final String selectedStrategy = bitcoinAddressesFilter.getSelectedAction();
            final String redactionScope = bitcoinAddressesFilter.getSelectedRedactionScope();
            // TODO: Additional filter strategy parameters can go in a Map passed to the strategy.
            final SimplifiedStrategy simplifiedStrategy = new SimplifiedStrategy(selectedStrategy, new SimplifiedCondition(bitcoinAddressesFilter.getConfidenceThreshold()), redactionScope, bitcoinAddressesFilter.getAnonymizationMethod());
            filters.put(FilterType.BITCOIN_ADDRESS, List.of(simplifiedStrategy)); // TODO: Allow for multiple strategies.

        }

        if (citiesFilter.isChecked()) {

            if(!citiesFilter.validateConfidenceThreshold()) {
                citiesFilter.setConfidenceThresholdInvalid();
                return null;
            }

            final String selectedStrategy = citiesFilter.getSelectedAction();
            final String redactionScope = citiesFilter.getSelectedRedactionScope();
            // TODO: Additional filter strategy parameters can go in a Map passed to the strategy.
            final SimplifiedStrategy simplifiedStrategy = new SimplifiedStrategy(selectedStrategy, new SimplifiedCondition(citiesFilter.getConfidenceThreshold()), redactionScope, citiesFilter.getAnonymizationMethod());
            filters.put(FilterType.LOCATION_CITY, List.of(simplifiedStrategy)); // TODO: Allow for multiple strategies.

        }

        if (countiesFilter.isChecked()) {

            if(!countiesFilter.validateConfidenceThreshold()) {
                countiesFilter.setConfidenceThresholdInvalid();
                return null;
            }

            final String selectedStrategy = countiesFilter.getSelectedAction();
            final String redactionScope = countiesFilter.getSelectedRedactionScope();
            // TODO: Additional filter strategy parameters can go in a Map passed to the strategy.
            final SimplifiedStrategy simplifiedStrategy = new SimplifiedStrategy(selectedStrategy, new SimplifiedCondition(countiesFilter.getConfidenceThreshold()), redactionScope, countiesFilter.getAnonymizationMethod());
            filters.put(FilterType.LOCATION_COUNTY, List.of(simplifiedStrategy)); // TODO: Allow for multiple strategies.

        }

        if (creditCardNumbersFilters.isChecked()) {

            if(!creditCardNumbersFilters.validateConfidenceThreshold()) {
                creditCardNumbersFilters.setConfidenceThresholdInvalid();
                return null;
            }

            final String selectedStrategy = creditCardNumbersFilters.getSelectedAction();
            final String redactionScope = creditCardNumbersFilters.getSelectedRedactionScope();
            // TODO: Additional filter strategy parameters can go in a Map passed to the strategy.
            final SimplifiedStrategy simplifiedStrategy = new SimplifiedStrategy(selectedStrategy, new SimplifiedCondition(creditCardNumbersFilters.getConfidenceThreshold()), redactionScope, creditCardNumbersFilters.getAnonymizationMethod());
            filters.put(FilterType.CREDIT_CARD, List.of(simplifiedStrategy)); // TODO: Allow for multiple strategies.

        }

        if (datesFilter.isChecked()) {

            if(!datesFilter.validateConfidenceThreshold()) {
                datesFilter.setConfidenceThresholdInvalid();
                return null;
            }

            final String selectedStrategy = datesFilter.getSelectedAction();
            final String redactionScope = datesFilter.getSelectedRedactionScope();
            // TODO: Additional filter strategy parameters can go in a Map passed to the strategy.
            final SimplifiedStrategy simplifiedStrategy = new SimplifiedStrategy(selectedStrategy, new SimplifiedCondition(datesFilter.getConfidenceThreshold()), redactionScope, datesFilter.getAnonymizationMethod());
            filters.put(FilterType.DATE, List.of(simplifiedStrategy)); // TODO: Allow for multiple strategies.

        }

        if (driversLicenseNumbersFilter.isChecked()) {

            if(!driversLicenseNumbersFilter.validateConfidenceThreshold()) {
                driversLicenseNumbersFilter.setConfidenceThresholdInvalid();
                return null;
            }

            final String selectedStrategy = driversLicenseNumbersFilter.getSelectedAction();
            final String redactionScope = driversLicenseNumbersFilter.getSelectedRedactionScope();
            // TODO: Additional filter strategy parameters can go in a Map passed to the strategy.
            final SimplifiedStrategy simplifiedStrategy = new SimplifiedStrategy(selectedStrategy, new SimplifiedCondition(driversLicenseNumbersFilter.getConfidenceThreshold()), redactionScope, driversLicenseNumbersFilter.getAnonymizationMethod());
            filters.put(FilterType.DRIVERS_LICENSE_NUMBER, List.of(simplifiedStrategy)); // TODO: Allow for multiple strategies.

        }

        if (emailAddressFilter.isChecked()) {

            if(!emailAddressFilter.validateConfidenceThreshold()) {
                emailAddressFilter.setConfidenceThresholdInvalid();
                return null;
            }

            final String selectedStrategy = emailAddressFilter.getSelectedAction();
            final String redactionScope = emailAddressFilter.getSelectedRedactionScope();
            // TODO: Additional filter strategy parameters can go in a Map passed to the strategy.
            final SimplifiedStrategy simplifiedStrategy = new SimplifiedStrategy(selectedStrategy, new SimplifiedCondition(emailAddressFilter.getConfidenceThreshold()), redactionScope, emailAddressFilter.getAnonymizationMethod());
            filters.put(FilterType.EMAIL_ADDRESS, List.of(simplifiedStrategy)); // TODO: Allow for multiple strategies.

        }

        if (ibanCodesFilter.isChecked()) {

            if(!ibanCodesFilter.validateConfidenceThreshold()) {
                ibanCodesFilter.setConfidenceThresholdInvalid();
                return null;
            }

            final String selectedStrategy = ibanCodesFilter.getSelectedAction();
            final String redactionScope = ibanCodesFilter.getSelectedRedactionScope();
            // TODO: Additional filter strategy parameters can go in a Map passed to the strategy.
            final SimplifiedStrategy simplifiedStrategy = new SimplifiedStrategy(selectedStrategy, new SimplifiedCondition(ibanCodesFilter.getConfidenceThreshold()), redactionScope, ibanCodesFilter.getAnonymizationMethod());
            filters.put(FilterType.IBAN_CODE, List.of(simplifiedStrategy)); // TODO: Allow for multiple strategies.

        }

        if (ipAddressesFilter.isChecked()) {

            if(!ipAddressesFilter.validateConfidenceThreshold()) {
                ipAddressesFilter.setConfidenceThresholdInvalid();
                return null;
            }

            final String selectedStrategy = ipAddressesFilter.getSelectedAction();
            final String redactionScope = ipAddressesFilter.getSelectedRedactionScope();
            // TODO: Additional filter strategy parameters can go in a Map passed to the strategy.
            final SimplifiedStrategy simplifiedStrategy = new SimplifiedStrategy(selectedStrategy, new SimplifiedCondition(ipAddressesFilter.getConfidenceThreshold()), redactionScope, ipAddressesFilter.getAnonymizationMethod());
            filters.put(FilterType.IP_ADDRESS, List.of(simplifiedStrategy)); // TODO: Allow for multiple strategies.

        }

        if (macAddressesFilter.isChecked()) {

            if(!macAddressesFilter.validateConfidenceThreshold()) {
                macAddressesFilter.setConfidenceThresholdInvalid();
                return null;
            }

            final String selectedStrategy = macAddressesFilter.getSelectedAction();
            final String redactionScope = macAddressesFilter.getSelectedRedactionScope();
            // TODO: Additional filter strategy parameters can go in a Map passed to the strategy.
            final SimplifiedStrategy simplifiedStrategy = new SimplifiedStrategy(selectedStrategy, new SimplifiedCondition(macAddressesFilter.getConfidenceThreshold()), redactionScope, macAddressesFilter.getAnonymizationMethod());
            filters.put(FilterType.MAC_ADDRESS, List.of(simplifiedStrategy)); // TODO: Allow for multiple strategies.

        }

        if (passportNumbersFilter.isChecked()) {

            if(!passportNumbersFilter.validateConfidenceThreshold()) {
                passportNumbersFilter.setConfidenceThresholdInvalid();
                return null;
            }

            final String selectedStrategy = passportNumbersFilter.getSelectedAction();
            final String redactionScope = passportNumbersFilter.getSelectedRedactionScope();
            // TODO: Additional filter strategy parameters can go in a Map passed to the strategy.
            final SimplifiedStrategy simplifiedStrategy = new SimplifiedStrategy(selectedStrategy, new SimplifiedCondition(passportNumbersFilter.getConfidenceThreshold()), redactionScope, passportNumbersFilter.getAnonymizationMethod());
            filters.put(FilterType.PASSPORT_NUMBER, List.of(simplifiedStrategy)); // TODO: Allow for multiple strategies.

        }

        if (phoneNumbersFilter.isChecked()) {

            if(!phoneNumbersFilter.validateConfidenceThreshold()) {
                phoneNumbersFilter.setConfidenceThresholdInvalid();
                return null;
            }

            final String selectedStrategy = phoneNumbersFilter.getSelectedAction();
            final String redactionScope = phoneNumbersFilter.getSelectedRedactionScope();
            // TODO: Additional filter strategy parameters can go in a Map passed to the strategy.
            final SimplifiedStrategy simplifiedStrategy = new SimplifiedStrategy(selectedStrategy, new SimplifiedCondition(phoneNumbersFilter.getConfidenceThreshold()), redactionScope, phoneNumbersFilter.getAnonymizationMethod());
            filters.put(FilterType.PHONE_NUMBER, List.of(simplifiedStrategy)); // TODO: Allow for multiple strategies.

        }

        if (ssnsAndTinsFilter.isChecked()) {

            if(!ssnsAndTinsFilter.validateConfidenceThreshold()) {
                ssnsAndTinsFilter.setConfidenceThresholdInvalid();
                return null;
            }

            final String selectedStrategy = ssnsAndTinsFilter.getSelectedAction();
            final String redactionScope = ssnsAndTinsFilter.getSelectedRedactionScope();
            // TODO: Additional filter strategy parameters can go in a Map passed to the strategy.
            final SimplifiedStrategy simplifiedStrategy = new SimplifiedStrategy(selectedStrategy, new SimplifiedCondition(ssnsAndTinsFilter.getConfidenceThreshold()), redactionScope, ssnsAndTinsFilter.getAnonymizationMethod());
            filters.put(FilterType.SSN, List.of(simplifiedStrategy)); // TODO: Allow for multiple strategies.

        }

        if (statesFilter.isChecked()) {

            if(!statesFilter.validateConfidenceThreshold()) {
                statesFilter.setConfidenceThresholdInvalid();
                return null;
            }

            final String selectedStrategy = statesFilter.getSelectedAction();
            final String redactionScope = statesFilter.getSelectedRedactionScope();
            // TODO: Additional filter strategy parameters can go in a Map passed to the strategy.
            final SimplifiedStrategy simplifiedStrategy = new SimplifiedStrategy(selectedStrategy, new SimplifiedCondition(statesFilter.getConfidenceThreshold()), redactionScope, statesFilter.getAnonymizationMethod());
            filters.put(FilterType.LOCATION_STATE, List.of(simplifiedStrategy)); // TODO: Allow for multiple strategies.

        }

        if (trackingNumbersFilter.isChecked()) {

            if(!trackingNumbersFilter.validateConfidenceThreshold()) {
                trackingNumbersFilter.setConfidenceThresholdInvalid();
                return null;
            }

            final String selectedStrategy = trackingNumbersFilter.getSelectedAction();
            final String redactionScope = trackingNumbersFilter.getSelectedRedactionScope();
            // TODO: Additional filter strategy parameters can go in a Map passed to the strategy.
            final SimplifiedStrategy simplifiedStrategy = new SimplifiedStrategy(selectedStrategy, new SimplifiedCondition(trackingNumbersFilter.getConfidenceThreshold()), redactionScope, trackingNumbersFilter.getAnonymizationMethod());
            filters.put(FilterType.TRACKING_NUMBER, List.of(simplifiedStrategy)); // TODO: Allow for multiple strategies.

        }

        if (urlsFilter.isChecked()) {

            if(!urlsFilter.validateConfidenceThreshold()) {
                urlsFilter.setConfidenceThresholdInvalid();
                return null;
            }

            final String selectedStrategy = urlsFilter.getSelectedAction();
            final String redactionScope = urlsFilter.getSelectedRedactionScope();
            // TODO: Additional filter strategy parameters can go in a Map passed to the strategy.
            final SimplifiedStrategy simplifiedStrategy = new SimplifiedStrategy(selectedStrategy, new SimplifiedCondition(urlsFilter.getConfidenceThreshold()), redactionScope, urlsFilter.getAnonymizationMethod());
            filters.put(FilterType.URL, List.of(simplifiedStrategy)); // TODO: Allow for multiple strategies.

        }

        if (vinsFilter.isChecked()) {

            if(!vinsFilter.validateConfidenceThreshold()) {
                vinsFilter.setConfidenceThresholdInvalid();
                return null;
            }

            final String selectedStrategy = vinsFilter.getSelectedAction();
            final String redactionScope = vinsFilter.getSelectedRedactionScope();
            // TODO: Additional filter strategy parameters can go in a Map passed to the strategy.
            final SimplifiedStrategy simplifiedStrategy = new SimplifiedStrategy(selectedStrategy, new SimplifiedCondition(vinsFilter.getConfidenceThreshold()), redactionScope, vinsFilter.getAnonymizationMethod());
            filters.put(FilterType.VIN, List.of(simplifiedStrategy)); // TODO: Allow for multiple strategies.

        }

        if (zipCodesFilter.isChecked()) {

            if(!zipCodesFilter.validateConfidenceThreshold()) {
                zipCodesFilter.setConfidenceThresholdInvalid();
                return null;
            }

            final String selectedStrategy = zipCodesFilter.getSelectedAction();
            final String redactionScope = zipCodesFilter.getSelectedRedactionScope();
            // TODO: Additional filter strategy parameters can go in a Map passed to the strategy.
            final SimplifiedStrategy simplifiedStrategy = new SimplifiedStrategy(selectedStrategy, new SimplifiedCondition(zipCodesFilter.getConfidenceThreshold()), redactionScope, zipCodesFilter.getAnonymizationMethod());
            filters.put(FilterType.ZIP_CODE, List.of(simplifiedStrategy)); // TODO: Allow for multiple strategies.

        }

        final SimplifiedPolicy simplifiedPolicy = new SimplifiedPolicy();
        simplifiedPolicy.setName(policyName);
        simplifiedPolicy.setLens(lens);
        simplifiedPolicy.setFilters(filters);

        // Terms to never redact
        final List<String> termsToIgnore;
        if(ignoredTermsTextArea.getValue() == null || ignoredTermsTextArea.getValue().isEmpty()) {
            termsToIgnore = Collections.emptyList();
        } else {
            termsToIgnore = List.of(ignoredTermsTextArea.getValue().split("\n"));
        }
        simplifiedPolicy.setTermsToIgnore(termsToIgnore);

        // Terms to always redact
        final List<String> termsToAlwaysRedact;
        if(termsToAlwaysRedactTextArea.getValue() == null || termsToAlwaysRedactTextArea.getValue().isEmpty()) {
            termsToAlwaysRedact = Collections.emptyList();
        } else {
            termsToAlwaysRedact = List.of(termsToAlwaysRedactTextArea.getValue().split("\n"));
        }
        simplifiedPolicy.setTermsToAlwaysRedact(termsToAlwaysRedact);
        simplifiedPolicy.setTermsToAlwaysRedactStrategy(termsToAlwaysRedactStrategyComboBox.getValue());

        // Options
        simplifiedPolicy.setHighlightChangesinWordDocuments(highlightChangesCheckBox.getValue());
        simplifiedPolicy.setTurnOnRevisionsinWordDocuments(turnOnRevisionsCheckBox.getValue());
        simplifiedPolicy.setDisambiguationScope(disambiguationScopeCheckBox.getValue());
        
        // PDF options
        simplifiedPolicy.setPdfScale(pdfScaleField.getValue().floatValue());
        simplifiedPolicy.setPdfDpi(pdfDpiField.getValue());
        simplifiedPolicy.setPdfCompressionQuality(pdfCompressionQualityField.getValue().floatValue());

        return gson.toJson(simplifiedPolicy);

    }

    public VerticalLayout getOptions(final SimplifiedPolicy policy) {

        highlightChangesCheckBox.setValue(policy.isHighlightChangesinWordDocuments());
        turnOnRevisionsCheckBox.setValue(policy.isTurnOnRevisionsinWordDocuments());

        disambiguationScopeCheckBox.setValue(policy.getDisambiguationScope());
        disambiguationScopeCheckBox.setAllowCustomValue(false);
        
        // Set PDF option values from policy
        pdfScaleField.setValue((double) policy.getPdfScale());
        pdfDpiField.setValue(policy.getPdfDpi());
        pdfCompressionQualityField.setValue((double) policy.getPdfCompressionQuality());

        final VerticalLayout verticalLayout = new VerticalLayout();

        verticalLayout.add(new H4("Microsoft Word Options"));
        verticalLayout.add(highlightChangesCheckBox);
        verticalLayout.add(turnOnRevisionsCheckBox);

        verticalLayout.add(new H4("Disambiguation Options"));
        verticalLayout.add(disambiguationScopeCheckBox);
        verticalLayout.add(new Span("Disambiguation refers to differentiating between similar types of PII and PHI. This can be performed either at the document or context level."));
        verticalLayout.add(CommonWidgets.getLink("Learn more about disambiguation.", "https://docs.philterd.ai/redaction/disambiguation.html", true));
        
        // Add PDF Options section
        verticalLayout.add(new H4("PDF Options"));

        final HorizontalLayout pdfOptionsHorizontalLayout = new HorizontalLayout();
        pdfOptionsHorizontalLayout.add(pdfScaleField);
        pdfOptionsHorizontalLayout.add(pdfDpiField);
        pdfOptionsHorizontalLayout.add(pdfCompressionQualityField);

        verticalLayout.add(pdfOptionsHorizontalLayout);

        verticalLayout.setSizeFull();

        return verticalLayout;

    }

    public VerticalLayout getTermsToAlwaysRedact(final SimplifiedPolicy policy) {

        final Span instructionsSpan = new Span("Specify words or phrases, one per line, that should always be redacted.");

        termsToAlwaysRedactTextArea.setSizeFull();

        if(policy.getTermsToAlwaysRedact() != null && !policy.getTermsToAlwaysRedact().isEmpty()) {
            termsToAlwaysRedactTextArea.setValue(String.join("\n", policy.getTermsToAlwaysRedact()));
        }

        // Set the strategy from the policy if available
        if(policy.getTermsToAlwaysRedactStrategy() != null && !policy.getTermsToAlwaysRedactStrategy().isEmpty()) {
            termsToAlwaysRedactStrategyComboBox.setValue(policy.getTermsToAlwaysRedactStrategy());
        }

        final VerticalLayout verticalLayout = new VerticalLayout(instructionsSpan, termsToAlwaysRedactTextArea, termsToAlwaysRedactStrategyComboBox);
        verticalLayout.setSizeFull();

        return verticalLayout;

    }

    public VerticalLayout getIgnored(final SimplifiedPolicy policy) {

        final Span instructionsSpan = new Span("Specify words or phrases, one per line, that should never be redacted.");

        ignoredTermsTextArea.setSizeFull();

        if(policy.getTermsToIgnore() != null && !policy.getTermsToIgnore().isEmpty()) {
            ignoredTermsTextArea.setValue(String.join("\n", policy.getTermsToIgnore()));
        }

        final VerticalLayout ignoredLayout = new VerticalLayout(instructionsSpan, ignoredTermsTextArea);
        ignoredLayout.setSizeFull();

        return ignoredLayout;

    }

    public VerticalLayout getFilters(final SimplifiedPolicy simplifiedPolicy) {

        final Span instructionsSpan = new Span("Select the types of PII/PHI to redact and how to redact each type below.");

        this.firstNameFilter = new PiiFilterDetails(FilterType.FIRST_NAME, simplifiedPolicy, "First Names (Dictionary-based)", List.of("Redact", "Mask", "Anonymize", "Hash-SHA256", "FPE"));
        this.surnamesFilter = new PiiFilterDetails(FilterType.SURNAME, simplifiedPolicy, "Surnames (Dictionary-based)", List.of("Redact", "Mask", "Anonymize", "Hash-SHA256", "FPE"));
        this.personsNamesFilter = new PiiFilterDetails(FilterType.PERSON, simplifiedPolicy, "Person's Names (AI)", List.of("Redact", "Mask", "Anonymize", "Hash-SHA256", "FPE"));
        this.agesFilter = new PiiFilterDetails(FilterType.AGE, simplifiedPolicy, "Ages", List.of("Redact", "Mask", "Anonymize", "Hash-SHA256"));
        this.bankRoutingNumbersFilter = new PiiFilterDetails(FilterType.BANK_ROUTING_NUMBER, simplifiedPolicy, "Bank Routing Numbers", List.of("Redact", "Mask", "Anonymize", "Hash-SHA256", "FPE"));
        this.bitcoinAddressesFilter = new PiiFilterDetails(FilterType.BITCOIN_ADDRESS, simplifiedPolicy, "Bitcoin Addresses", List.of("Redact", "Mask", "Anonymize", "Hash-SHA256", "FPE"));
        this.citiesFilter = new PiiFilterDetails(FilterType.LOCATION_CITY, simplifiedPolicy, "Cities (USA)", List.of("Redact", "Mask", "Anonymize", "Hash-SHA256", "FPE"));
        this.countiesFilter = new PiiFilterDetails(FilterType.LOCATION_COUNTY, simplifiedPolicy, "Counties (USA)", List.of("Redact", "Mask", "Anonymize", "Hash-SHA256", "FPE"));
        this.creditCardNumbersFilters = new PiiFilterDetails(FilterType.CREDIT_CARD, simplifiedPolicy, "Credit Card Numbers", List.of("Redact", "Mask", "Anonymize", "Hash-SHA256", "FPE"));
        this.datesFilter = new PiiFilterDetails(FilterType.DATE, simplifiedPolicy, "Dates", List.of("Redact", "Mask", "Anonymize", "Hash-SHA256", "Shift", "Truncate to Year"));
        this.driversLicenseNumbersFilter = new PiiFilterDetails(FilterType.DRIVERS_LICENSE_NUMBER, simplifiedPolicy, "Driver's License Numbers", List.of("Redact", "Mask", "Anonymize", "Hash-SHA256", "FPE"));
        this.emailAddressFilter = new PiiFilterDetails(FilterType.EMAIL_ADDRESS, simplifiedPolicy, "Email Addresses", List.of("Redact", "Mask", "Anonymize", "Hash-SHA256", "FPE"));
        this.ibanCodesFilter = new PiiFilterDetails(FilterType.IBAN_CODE, simplifiedPolicy, "IBAN Codes", List.of("Redact", "Mask", "Anonymize", "Hash-SHA256", "FPE"));
        this.ipAddressesFilter = new PiiFilterDetails(FilterType.IP_ADDRESS, simplifiedPolicy, "IP Addresses", List.of("Redact", "Mask", "Anonymize", "Hash-SHA256", "FPE"));
        this.macAddressesFilter = new PiiFilterDetails(FilterType.MAC_ADDRESS, simplifiedPolicy, "MAC Addresses", List.of("Redact", "Mask", "Anonymize", "Hash-SHA256", "FPE"));
        this.passportNumbersFilter = new PiiFilterDetails(FilterType.PASSPORT_NUMBER, simplifiedPolicy, "Passport Numbers", List.of("Redact", "Mask", "Anonymize", "Hash-SHA256", "FPE"));
        this.phoneNumbersFilter = new PiiFilterDetails(FilterType.PHONE_NUMBER, simplifiedPolicy, "Phone Numbers", List.of("Redact", "Mask", "Anonymize", "Hash-SHA256", "FPE"));
        this.ssnsAndTinsFilter = new PiiFilterDetails(FilterType.SSN, simplifiedPolicy, "SSNs and TINs", List.of("Redact", "Mask", "Anonymize", "Hash-SHA256", "FPE", "Last 4"));
        this.statesFilter = new PiiFilterDetails(FilterType.LOCATION_STATE, simplifiedPolicy, "States (USA)", List.of("Redact", "Mask", "Anonymize", "Hash-SHA256", "FPE"));
        this.streetAddressesFilter = new PiiFilterDetails(FilterType.STREET_ADDRESS, simplifiedPolicy, "Street Addresses", List.of("Redact", "Mask", "Anonymize", "Hash-SHA256", "FPE"));
        this.trackingNumbersFilter = new PiiFilterDetails(FilterType.TRACKING_NUMBER, simplifiedPolicy, "Tracking Numbers", List.of("Redact", "Mask", "Anonymize", "Hash-SHA256", "FPE"));
        this.urlsFilter = new PiiFilterDetails(FilterType.URL, simplifiedPolicy, "URLs", List.of("Redact", "Mask", "Anonymize", "Hash-SHA256", "FPE"));
        this.vinsFilter = new PiiFilterDetails(FilterType.VIN, simplifiedPolicy, "VINs", List.of("Redact", "Mask", "Anonymize", "Hash-SHA256", "FPE"));
        this.zipCodesFilter = new PiiFilterDetails(FilterType.ZIP_CODE, simplifiedPolicy, "Zip Codes", List.of("Redact", "Mask", "Anonymize", "Hash-SHA256", "Truncate"));

        final VerticalLayout filtersVerticalLayout = new VerticalLayout();
        filtersVerticalLayout.add(instructionsSpan);
        filtersVerticalLayout.add(firstNameFilter);
        filtersVerticalLayout.add(surnamesFilter);
        filtersVerticalLayout.add(personsNamesFilter);
        filtersVerticalLayout.add(agesFilter);
        filtersVerticalLayout.add(bankRoutingNumbersFilter);
        filtersVerticalLayout.add(bitcoinAddressesFilter);
        filtersVerticalLayout.add(citiesFilter);
        filtersVerticalLayout.add(countiesFilter);
        filtersVerticalLayout.add(creditCardNumbersFilters);
        filtersVerticalLayout.add(datesFilter);
        filtersVerticalLayout.add(driversLicenseNumbersFilter);
        filtersVerticalLayout.add(emailAddressFilter);
        filtersVerticalLayout.add(ibanCodesFilter);
        filtersVerticalLayout.add(ipAddressesFilter);
        filtersVerticalLayout.add(macAddressesFilter);
        filtersVerticalLayout.add(passportNumbersFilter);
        filtersVerticalLayout.add(phoneNumbersFilter);
        filtersVerticalLayout.add(ssnsAndTinsFilter);
        filtersVerticalLayout.add(statesFilter);
        filtersVerticalLayout.add(streetAddressesFilter);
        filtersVerticalLayout.add(trackingNumbersFilter);
        filtersVerticalLayout.add(urlsFilter);
        filtersVerticalLayout.add(vinsFilter);
        filtersVerticalLayout.add(zipCodesFilter);

        return filtersVerticalLayout;

    }

}

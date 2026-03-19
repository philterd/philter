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
package ai.philterd.philter.services.policies;

import ai.philterd.phileas.model.filtering.FilterType;
import ai.philterd.phileas.policy.Config;
import ai.philterd.phileas.policy.FPE;
import ai.philterd.phileas.policy.Identifiers;
import ai.philterd.phileas.policy.Ignored;
import ai.philterd.phileas.policy.Policy;
import ai.philterd.phileas.policy.config.Pdf;
import ai.philterd.phileas.policy.config.Splitting;
import ai.philterd.phileas.policy.filters.Age;
import ai.philterd.phileas.policy.filters.BankRoutingNumber;
import ai.philterd.phileas.policy.filters.BitcoinAddress;
import ai.philterd.phileas.policy.filters.City;
import ai.philterd.phileas.policy.filters.County;
import ai.philterd.phileas.policy.filters.CreditCard;
import ai.philterd.phileas.policy.filters.Currency;
import ai.philterd.phileas.policy.filters.CustomDictionary;
import ai.philterd.phileas.policy.filters.Date;
import ai.philterd.phileas.policy.filters.DriversLicense;
import ai.philterd.phileas.policy.filters.EmailAddress;
import ai.philterd.phileas.policy.filters.FirstName;
import ai.philterd.phileas.policy.filters.IbanCode;
import ai.philterd.phileas.policy.filters.Identifier;
import ai.philterd.phileas.policy.filters.IpAddress;
import ai.philterd.phileas.policy.filters.MacAddress;
import ai.philterd.phileas.policy.filters.PassportNumber;
import ai.philterd.phileas.policy.filters.PhEye;
import ai.philterd.phileas.policy.filters.PhoneNumber;
import ai.philterd.phileas.policy.filters.Ssn;
import ai.philterd.phileas.policy.filters.State;
import ai.philterd.phileas.policy.filters.StreetAddress;
import ai.philterd.phileas.policy.filters.Surname;
import ai.philterd.phileas.policy.filters.TrackingNumber;
import ai.philterd.phileas.policy.filters.Url;
import ai.philterd.phileas.policy.filters.Vin;
import ai.philterd.phileas.policy.filters.ZipCode;
import ai.philterd.phileas.policy.filters.pheye.PhEyeConfiguration;
import ai.philterd.phileas.services.anonymization.AnonymizationMethod;
import ai.philterd.phileas.services.strategies.AbstractFilterStrategy;
import ai.philterd.phileas.services.strategies.ai.PhEyeFilterStrategy;
import ai.philterd.phileas.services.strategies.custom.CustomDictionaryFilterStrategy;
import ai.philterd.phileas.services.strategies.dynamic.CityFilterStrategy;
import ai.philterd.phileas.services.strategies.dynamic.CountyFilterStrategy;
import ai.philterd.phileas.services.strategies.dynamic.FirstNameFilterStrategy;
import ai.philterd.phileas.services.strategies.dynamic.StateFilterStrategy;
import ai.philterd.phileas.services.strategies.dynamic.SurnameFilterStrategy;
import ai.philterd.phileas.services.strategies.rules.AgeFilterStrategy;
import ai.philterd.phileas.services.strategies.rules.BankRoutingNumberFilterStrategy;
import ai.philterd.phileas.services.strategies.rules.BitcoinAddressFilterStrategy;
import ai.philterd.phileas.services.strategies.rules.CreditCardFilterStrategy;
import ai.philterd.phileas.services.strategies.rules.CurrencyFilterStrategy;
import ai.philterd.phileas.services.strategies.rules.DateFilterStrategy;
import ai.philterd.phileas.services.strategies.rules.DriversLicenseFilterStrategy;
import ai.philterd.phileas.services.strategies.rules.EmailAddressFilterStrategy;
import ai.philterd.phileas.services.strategies.rules.IbanCodeFilterStrategy;
import ai.philterd.phileas.services.strategies.rules.IdentifierFilterStrategy;
import ai.philterd.phileas.services.strategies.rules.IpAddressFilterStrategy;
import ai.philterd.phileas.services.strategies.rules.MacAddressFilterStrategy;
import ai.philterd.phileas.services.strategies.rules.PassportNumberFilterStrategy;
import ai.philterd.phileas.services.strategies.rules.PhoneNumberFilterStrategy;
import ai.philterd.phileas.services.strategies.rules.SsnFilterStrategy;
import ai.philterd.phileas.services.strategies.rules.StreetAddressFilterStrategy;
import ai.philterd.phileas.services.strategies.rules.TrackingNumberFilterStrategy;
import ai.philterd.phileas.services.strategies.rules.UrlFilterStrategy;
import ai.philterd.phileas.services.strategies.rules.VinFilterStrategy;
import ai.philterd.phileas.services.strategies.rules.ZipCodeFilterStrategy;
import ai.philterd.philter.data.entities.CustomListEntity;
import ai.philterd.philter.data.services.CustomListDataService;
import com.google.gson.GsonBuilder;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This class is a simplified version of a Phileas policy.
 */
public class SimplifiedPolicy {

    private static final Logger LOGGER = LoggerFactory.getLogger(SimplifiedPolicy.class);

    private static final String PHEYE_ENDPOINT = System.getenv("PHEYE_ENDPOINT");
    private static final String CUSTOM_LIST_PREFIX = "list:";

    // Disambiguation scopes
    public static final String DISAMBIGUATION_SCOPE_DOCUMENT = "Document";
    public static final String DISAMBIGUATION_SCOPE_CONTEXT = "Context";
    public static final List<String> DISAMBIGUATION_SCOPES = List.of(DISAMBIGUATION_SCOPE_DOCUMENT.toLowerCase(), DISAMBIGUATION_SCOPE_CONTEXT.toLowerCase());

    // The default values here are important because the user doesn't have to supply value for all properties,
    // and the default values should be used instead.
    private String version = "1.0.0";
    private String name = "";
    private String description = "";
    private Map<FilterType, List<SimplifiedStrategy>> filters = Collections.emptyMap();
    private List<String> termsToIgnore = Collections.emptyList();
    private List<String> termsToAlwaysRedact = Collections.emptyList();
    private String termsToAlwaysRedactStrategy = "Redact";
    private boolean highlightChangesinWordDocuments = false;
    private boolean turnOnRevisionsinWordDocuments = false;
    private String disambiguationScope = DISAMBIGUATION_SCOPE_DOCUMENT;
    
    // PDF options with default values
    private float pdfScale = 1.0f;
    private int pdfDpi = 150;
    private float pdfCompressionQuality = 1.0f;

    public static String getDefaultPolicy() {

        // TODO: Define the default policy.

        final SimplifiedPolicy policy = new SimplifiedPolicy();
        policy.name = "default";
        policy.description = "Default Policy";

        final Map<FilterType, List<SimplifiedStrategy>> filters = new HashMap<>();
        filters.put(FilterType.PERSON, List.of(new SimplifiedStrategy("REDACT")));
        filters.put(FilterType.SSN, List.of(new SimplifiedStrategy("REDACT")));
        filters.put(FilterType.EMAIL_ADDRESS, List.of(new SimplifiedStrategy("REDACT")));

        policy.filters = filters;

        return policy.toString();

    }

    /**
     * Checks if a term is a custom list reference (starts with "list:").
     * @param term The term to check.
     * @return true if the term is a custom list reference, false otherwise.
     */
    private static boolean isCustomListReference(final String term) {
        return term != null && term.startsWith(CUSTOM_LIST_PREFIX);
    }

    /**
     * Extracts the list name from a custom list reference.
     * @param reference The reference string (e.g., "list:my-list").
     * @return The list name (e.g., "my-list").
     */
    private static String extractListName(final String reference) {
        if (isCustomListReference(reference)) {
            return reference.substring(CUSTOM_LIST_PREFIX.length());
        }
        return reference;
    }

    /**
     * Resolves custom list references in a list of terms.
     * Terms that start with "list:" will be replaced with the actual items from the custom list.
     * Terms that don't start with "list:" will be kept as-is.
     * @param terms The list of terms that may contain custom list references.
     * @param customListService The service to retrieve custom lists.
     * @param userId The user ID for retrieving custom lists.
     * @return A list with all custom list references resolved. Never returns null.
     */
    private static List<String> resolveCustomListReferences(final List<String> terms, 
                                                             final CustomListDataService customListService,
                                                             final ObjectId userId) {
        if (terms == null || terms.isEmpty()) {
            return Collections.emptyList();
        }

        // If no custom list service is provided, return the original list
        if (customListService == null || userId == null) {
            return terms;
        }

        final List<String> resolvedTerms = new ArrayList<>();

        for (final String term : terms) {
            if (isCustomListReference(term)) {
                final String listName = extractListName(term);
                final CustomListEntity customList = customListService.findOneByName(listName);
                
                if (customList != null && customList.getItems() != null) {
                    LOGGER.info("Resolved custom list reference '{}' to {} items", term, customList.getItems().size());
                    resolvedTerms.addAll(customList.getItems());
                } else {
                    LOGGER.warn("Custom list '{}' not found for user. Reference will be ignored.", listName);
                }
            } else {
                resolvedTerms.add(term);
            }
        }

        return resolvedTerms;
    }

    /**
     * Convert this object to a Phileas policy.
     * @param fpeKey The FPE key.
     * @param fpeTweak The FPE tweak.
     * @return A Phileas policy.
     * @deprecated Use {@link #toPolicy(String, String, CustomListDataService, ObjectId)} instead.
     */
    @Deprecated
    public Policy toPolicy(final String fpeKey, final String fpeTweak) throws IOException {
        return toPolicy(fpeKey, fpeTweak, null, null);
    }

    /**
     * Convert this object to a Phileas policy.
     * @param fpeKey The FPE key.
     * @param fpeTweak The FPE tweak.
     * @param customListService The service to retrieve custom lists (can be null if not using custom list references).
     * @param userId The user ID for retrieving custom lists (can be null if not using custom list references).
     * @return A Phileas policy.
     */
    public Policy toPolicy(final String fpeKey, final String fpeTweak, 
                          final CustomListDataService customListService,
                          final ObjectId userId) throws IOException {

        if("1.0.0".equals(version)) {

            final Policy policy = new Policy();

            // Resolve custom list references in termsToIgnore and termsToAlwaysRedact
            final List<String> resolvedTermsToIgnore = resolveCustomListReferences(termsToIgnore, customListService, userId);
            final List<String> resolvedTermsToAlwaysRedact = resolveCustomListReferences(termsToAlwaysRedact, customListService, userId);

            // Set the list of ignored terms.
            final Ignored ignored = new Ignored();
            ignored.setTerms(resolvedTermsToIgnore);

            final List<Ignored> ignoredList = new ArrayList<>();
            ignoredList.add(ignored);
            policy.setIgnored(ignoredList);

            // TODO: https://github.com/philterd/philterd-data-services/issues/33

            // Configure the splitting options.
            final Splitting splitting = new Splitting();
            splitting.setThreshold(4000);
            splitting.setMethod("characters");
            splitting.setEnabled(true);

            // Configure PDF options.
            final Pdf pdf = new Pdf();
            pdf.setScale(pdfScale);
            pdf.setDpi(pdfDpi);
            pdf.setCompressionQuality(pdfCompressionQuality);
            pdf.setPreserveUnredactedPages(true);

            // Set the policy config.
            final Config config = new Config();
            config.setSplitting(splitting);
            config.setPdf(pdf);
            policy.setConfig(config);

            // Set up the FPE.
            policy.setFpe(new FPE(fpeKey, fpeTweak));

            final Identifiers identifiers = new Identifiers();

            // Terms to always redact
            if(!resolvedTermsToAlwaysRedact.isEmpty()) {

                final CustomDictionaryFilterStrategy filterStrategy = new CustomDictionaryFilterStrategy();
                filterStrategy.setStrategy(convertStrategy(termsToAlwaysRedactStrategy));

                final CustomDictionary customDictionary = new CustomDictionary();
                customDictionary.setTerms(resolvedTermsToAlwaysRedact);
                customDictionary.setCustomDictionaryFilterStrategies(List.of(filterStrategy));

                if(identifiers.getCustomDictionaries() != null) {
                    identifiers.getCustomDictionaries().add(customDictionary);
                } else {
                    identifiers.setCustomDictionaries(List.of(customDictionary));
                }

            }

            if (filters.containsKey(FilterType.PERSON)) {

                final List<PhEyeFilterStrategy> filterStrategies = new ArrayList<>();

                for(final SimplifiedStrategy simplifiedStrategy : filters.get(FilterType.PERSON)) {

                    final PhEyeFilterStrategy filterStrategy = new PhEyeFilterStrategy();
                    filterStrategy.setStrategy(convertStrategy(simplifiedStrategy));
                    filterStrategy.setReplacementScope(simplifiedStrategy.getRedactionScope());
                    filterStrategy.setConditions("confidence >= " + simplifiedStrategy.getSimplifiedCondition().getConfidenceAsDouble());
                    filterStrategy.setAnonymizationMethod(AnonymizationMethod.fromString(simplifiedStrategy.getAnonymizationMethod()));

                    filterStrategies.add(filterStrategy);

                }

                // Configure PhEye settings.
                final PhEyeConfiguration phEyeConfiguration = new PhEyeConfiguration();
                phEyeConfiguration.setEndpoint(PHEYE_ENDPOINT);
                phEyeConfiguration.setLabels(List.of("Person"));    // "Person" is expected back by Phileas

                final Map<String, Double> thresholds = new HashMap<>();
                // TODO: Is this being used?
                thresholds.put("Person", 0.80);

                final PhEye filter = new PhEye();
                filter.setPhEyeConfiguration(phEyeConfiguration);
                filter.setThresholds(thresholds);
                filter.setPhEyeFilterStrategies(filterStrategies);

                identifiers.setPerson(filter);

            }

            if (filters.containsKey(FilterType.FIRST_NAME)) {

                final List<FirstNameFilterStrategy> filterStrategies = new ArrayList<>();

                for(final SimplifiedStrategy simplifiedStrategy : filters.get(FilterType.FIRST_NAME)) {

                    final FirstNameFilterStrategy filterStrategy = new FirstNameFilterStrategy();
                    filterStrategy.setStrategy(convertStrategy(simplifiedStrategy));
                    filterStrategy.setConditions("confidence >= " + simplifiedStrategy.getSimplifiedCondition().getConfidenceAsDouble());
                    filterStrategy.setRedactionFormat("[REDACTED-%t]");
                    filterStrategy.setReplacementScope(simplifiedStrategy.getRedactionScope());
                    filterStrategy.setAnonymizationMethod(AnonymizationMethod.fromString(simplifiedStrategy.getAnonymizationMethod()));
                    filterStrategies.add(filterStrategy);

                }

                final FirstName filter = new FirstName();
                filter.setFirstNameFilterStrategies(filterStrategies);

                identifiers.setFirstName(filter);

            }

            if (filters.containsKey(FilterType.SURNAME)) {

                final List<SurnameFilterStrategy> filterStrategies = new ArrayList<>();

                for(final SimplifiedStrategy simplifiedStrategy : filters.get(FilterType.SURNAME)) {

                    final SurnameFilterStrategy filterStrategy = new SurnameFilterStrategy();
                    filterStrategy.setStrategy(convertStrategy(simplifiedStrategy));
                    filterStrategy.setConditions("confidence >= " + simplifiedStrategy.getSimplifiedCondition().getConfidenceAsDouble());
                    filterStrategy.setReplacementScope(simplifiedStrategy.getRedactionScope());
                    filterStrategy.setAnonymizationMethod(AnonymizationMethod.fromString(simplifiedStrategy.getAnonymizationMethod()));
                    filterStrategies.add(filterStrategy);

                }

                final Surname filter = new Surname();
                filter.setSurnameFilterStrategies(filterStrategies);

                identifiers.setSurname(filter);

            }

            if (filters.containsKey(FilterType.LOCATION_COUNTY)) {

                final List<CountyFilterStrategy> filterStrategies = new ArrayList<>();

                for(final SimplifiedStrategy simplifiedStrategy : filters.get(FilterType.LOCATION_COUNTY)) {

                    final CountyFilterStrategy filterStrategy = new CountyFilterStrategy();
                    filterStrategy.setStrategy(convertStrategy(simplifiedStrategy));
                    filterStrategy.setConditions("confidence >= " + simplifiedStrategy.getSimplifiedCondition().getConfidenceAsDouble());
                    filterStrategy.setReplacementScope(simplifiedStrategy.getRedactionScope());
                    filterStrategy.setAnonymizationMethod(AnonymizationMethod.fromString(simplifiedStrategy.getAnonymizationMethod()));
                    filterStrategies.add(filterStrategy);

                }

                final County filter = new County();
                filter.setCountyFilterStrategies(filterStrategies);

                identifiers.setCounty(filter);

            }

            if (filters.containsKey(FilterType.LOCATION_CITY)) {

                final List<CityFilterStrategy> filterStrategies = new ArrayList<>();

                for(final SimplifiedStrategy simplifiedStrategy : filters.get(FilterType.LOCATION_CITY)) {

                    final CityFilterStrategy filterStrategy = new CityFilterStrategy();
                    filterStrategy.setStrategy(convertStrategy(simplifiedStrategy));
                    filterStrategy.setConditions("confidence >= " + simplifiedStrategy.getSimplifiedCondition().getConfidenceAsDouble());
                    filterStrategy.setReplacementScope(simplifiedStrategy.getRedactionScope());
                    filterStrategy.setAnonymizationMethod(AnonymizationMethod.fromString(simplifiedStrategy.getAnonymizationMethod()));
                    filterStrategies.add(filterStrategy);

                }

                final City filter = new City();
                filter.setCityFilterStrategies(filterStrategies);

                identifiers.setCity(filter);

            }

            if (filters.containsKey(FilterType.LOCATION_STATE)) {

                final List<StateFilterStrategy> filterStrategies = new ArrayList<>();

                for(final SimplifiedStrategy simplifiedStrategy : filters.get(FilterType.LOCATION_STATE)) {

                    final StateFilterStrategy filterStrategy = new StateFilterStrategy();
                    filterStrategy.setStrategy(convertStrategy(simplifiedStrategy));
                    filterStrategy.setConditions("confidence >= " + simplifiedStrategy.getSimplifiedCondition().getConfidenceAsDouble());
                    filterStrategy.setReplacementScope(simplifiedStrategy.getRedactionScope());
                    filterStrategy.setAnonymizationMethod(AnonymizationMethod.fromString(simplifiedStrategy.getAnonymizationMethod()));
                    filterStrategies.add(filterStrategy);

                }

                final State filter = new State();
                filter.setStateFilterStrategies(filterStrategies);

                identifiers.setState(filter);

            }

            if (filters.containsKey(FilterType.AGE)) {

                final List<AgeFilterStrategy> filterStrategies = new ArrayList<>();

                for(final SimplifiedStrategy simplifiedStrategy : filters.get(FilterType.AGE)) {

                    final AgeFilterStrategy filterStrategy = new AgeFilterStrategy();
                    filterStrategy.setStrategy(convertStrategy(simplifiedStrategy));
                    filterStrategy.setConditions("confidence >= " + simplifiedStrategy.getSimplifiedCondition().getConfidenceAsDouble());
                    filterStrategy.setReplacementScope(simplifiedStrategy.getRedactionScope());
                    filterStrategy.setAnonymizationMethod(AnonymizationMethod.fromString(simplifiedStrategy.getAnonymizationMethod()));
                    filterStrategies.add(filterStrategy);

                }

                final Age filter = new Age();
                filter.setAgeFilterStrategies(filterStrategies);

                identifiers.setAge(filter);

            }

            if (filters.containsKey(FilterType.BANK_ROUTING_NUMBER)) {

                final List<BankRoutingNumberFilterStrategy> filterStrategies = new ArrayList<>();

                for(final SimplifiedStrategy simplifiedStrategy : filters.get(FilterType.BANK_ROUTING_NUMBER)) {

                    final BankRoutingNumberFilterStrategy filterStrategy = new BankRoutingNumberFilterStrategy();
                    filterStrategy.setStrategy(convertStrategy(simplifiedStrategy));
                    filterStrategy.setConditions("confidence >= " + simplifiedStrategy.getSimplifiedCondition().getConfidenceAsDouble());
                    filterStrategy.setReplacementScope(simplifiedStrategy.getRedactionScope());
                    filterStrategy.setAnonymizationMethod(AnonymizationMethod.fromString(simplifiedStrategy.getAnonymizationMethod()));
                    filterStrategies.add(filterStrategy);

                }

                final BankRoutingNumber filter = new BankRoutingNumber();
                filter.setBankRoutingNumberFilterStrategies(filterStrategies);

                identifiers.setBankRoutingNumber(filter);

            }

            if (filters.containsKey(FilterType.BITCOIN_ADDRESS)) {

                final List<BitcoinAddressFilterStrategy> filterStrategies = new ArrayList<>();

                for(final SimplifiedStrategy simplifiedStrategy : filters.get(FilterType.BITCOIN_ADDRESS)) {

                    final BitcoinAddressFilterStrategy filterStrategy = new BitcoinAddressFilterStrategy();
                    filterStrategy.setStrategy(convertStrategy(simplifiedStrategy));
                    filterStrategy.setConditions("confidence >= " + simplifiedStrategy.getSimplifiedCondition().getConfidenceAsDouble());
                    filterStrategy.setReplacementScope(simplifiedStrategy.getRedactionScope());
                    filterStrategy.setAnonymizationMethod(AnonymizationMethod.fromString(simplifiedStrategy.getAnonymizationMethod()));
                    filterStrategies.add(filterStrategy);

                }

                final BitcoinAddress filter = new BitcoinAddress();
                filter.setBitcoinFilterStrategies(filterStrategies);

                identifiers.setBitcoinAddress(filter);

            }

            if (filters.containsKey(FilterType.CREDIT_CARD)) {

                final List<CreditCardFilterStrategy> filterStrategies = new ArrayList<>();

                for(final SimplifiedStrategy simplifiedStrategy : filters.get(FilterType.CREDIT_CARD)) {

                    final CreditCardFilterStrategy filterStrategy = new CreditCardFilterStrategy();
                    filterStrategy.setStrategy(convertStrategy(simplifiedStrategy));
                    filterStrategy.setConditions("confidence >= " + simplifiedStrategy.getSimplifiedCondition().getConfidenceAsDouble());
                    filterStrategy.setReplacementScope(simplifiedStrategy.getRedactionScope());
                    filterStrategy.setAnonymizationMethod(AnonymizationMethod.fromString(simplifiedStrategy.getAnonymizationMethod()));
                    filterStrategies.add(filterStrategy);

                }

                final CreditCard filter = new CreditCard();
                filter.setCreditCardFilterStrategies(filterStrategies);

                identifiers.setCreditCard(filter);

            }

            if (filters.containsKey(FilterType.CURRENCY)) {

                final List<CurrencyFilterStrategy> filterStrategies = new ArrayList<>();

                for(final SimplifiedStrategy simplifiedStrategy : filters.get(FilterType.CURRENCY)) {

                    final CurrencyFilterStrategy filterStrategy = new CurrencyFilterStrategy();
                    filterStrategy.setStrategy(convertStrategy(simplifiedStrategy));
                    filterStrategy.setConditions("confidence >= " + simplifiedStrategy.getSimplifiedCondition().getConfidenceAsDouble());
                    filterStrategy.setReplacementScope(simplifiedStrategy.getRedactionScope());
                    filterStrategy.setAnonymizationMethod(AnonymizationMethod.fromString(simplifiedStrategy.getAnonymizationMethod()));
                    filterStrategies.add(filterStrategy);

                }

                final Currency filter = new Currency();
                filter.setCurrencyFilterStrategies(filterStrategies);

                identifiers.setCurrency(filter);

            }

            if (filters.containsKey(FilterType.DATE)) {

                final List<DateFilterStrategy> filterStrategies = new ArrayList<>();

                for(final SimplifiedStrategy simplifiedStrategy : filters.get(FilterType.DATE)) {

                    final DateFilterStrategy filterStrategy = new DateFilterStrategy();
                    filterStrategy.setStrategy(convertStrategy(simplifiedStrategy));
                    filterStrategy.setConditions("confidence >= " + simplifiedStrategy.getSimplifiedCondition().getConfidenceAsDouble());
                    filterStrategy.setReplacementScope(simplifiedStrategy.getRedactionScope());
                    filterStrategy.setAnonymizationMethod(AnonymizationMethod.fromString(simplifiedStrategy.getAnonymizationMethod()));
                    filterStrategies.add(filterStrategy);

                }

                final Date filter = new Date();
                filter.setDateFilterStrategies(filterStrategies);

                identifiers.setDate(filter);

            }

            if (filters.containsKey(FilterType.DRIVERS_LICENSE_NUMBER)) {

                final List<DriversLicenseFilterStrategy> filterStrategies = new ArrayList<>();

                for(final SimplifiedStrategy simplifiedStrategy : filters.get(FilterType.DRIVERS_LICENSE_NUMBER)) {

                    final DriversLicenseFilterStrategy filterStrategy = new DriversLicenseFilterStrategy();
                    filterStrategy.setStrategy(convertStrategy(simplifiedStrategy));
                    filterStrategy.setConditions("confidence >= " + simplifiedStrategy.getSimplifiedCondition().getConfidenceAsDouble());
                    filterStrategy.setReplacementScope(simplifiedStrategy.getRedactionScope());
                    filterStrategy.setAnonymizationMethod(AnonymizationMethod.fromString(simplifiedStrategy.getAnonymizationMethod()));
                    filterStrategies.add(filterStrategy);

                }

                final DriversLicense filter = new DriversLicense();
                filter.setDriversLicenseFilterStrategies(filterStrategies);

                identifiers.setDriversLicense(filter);

            }

            if (filters.containsKey(FilterType.EMAIL_ADDRESS)) {

                final List<EmailAddressFilterStrategy> filterStrategies = new ArrayList<>();

                for(final SimplifiedStrategy simplifiedStrategy : filters.get(FilterType.EMAIL_ADDRESS)) {

                    final EmailAddressFilterStrategy filterStrategy = new EmailAddressFilterStrategy();
                    filterStrategy.setStrategy(convertStrategy(simplifiedStrategy));
                    filterStrategy.setConditions("confidence >= " + simplifiedStrategy.getSimplifiedCondition().getConfidenceAsDouble());
                    filterStrategy.setReplacementScope(simplifiedStrategy.getRedactionScope());
                    filterStrategies.add(filterStrategy);

                }

                final EmailAddress filter = new EmailAddress();
                filter.setEmailAddressFilterStrategies(filterStrategies);

                identifiers.setEmailAddress(filter);

            }

            if (filters.containsKey(FilterType.IBAN_CODE)) {

                final List<IbanCodeFilterStrategy> filterStrategies = new ArrayList<>();

                for(final SimplifiedStrategy simplifiedStrategy : filters.get(FilterType.IBAN_CODE)) {

                    final IbanCodeFilterStrategy filterStrategy = new IbanCodeFilterStrategy();
                    filterStrategy.setStrategy(convertStrategy(simplifiedStrategy));
                    filterStrategy.setConditions("confidence >= " + simplifiedStrategy.getSimplifiedCondition().getConfidenceAsDouble());
                    filterStrategy.setReplacementScope(simplifiedStrategy.getRedactionScope());
                    filterStrategy.setAnonymizationMethod(AnonymizationMethod.fromString(simplifiedStrategy.getAnonymizationMethod()));
                    filterStrategies.add(filterStrategy);

                }

                final IbanCode filter = new IbanCode();
                filter.setIbanCodeFilterStrategies(filterStrategies);

                identifiers.setIbanCode(filter);

            }

            if (filters.containsKey(FilterType.IP_ADDRESS)) {

                final List<IpAddressFilterStrategy> filterStrategies = new ArrayList<>();

                for(final SimplifiedStrategy simplifiedStrategy : filters.get(FilterType.IP_ADDRESS)) {

                    final IpAddressFilterStrategy filterStrategy = new IpAddressFilterStrategy();
                    filterStrategy.setStrategy(convertStrategy(simplifiedStrategy));
                    filterStrategy.setConditions("confidence >= " + simplifiedStrategy.getSimplifiedCondition().getConfidenceAsDouble());
                    filterStrategy.setReplacementScope(simplifiedStrategy.getRedactionScope());
                    filterStrategy.setAnonymizationMethod(AnonymizationMethod.fromString(simplifiedStrategy.getAnonymizationMethod()));
                    filterStrategies.add(filterStrategy);

                }

                final IpAddress filter = new IpAddress();
                filter.setIpAddressFilterStrategies(filterStrategies);

                identifiers.setIpAddress(filter);

            }

            if (filters.containsKey(FilterType.IDENTIFIER)) {

                final List<IdentifierFilterStrategy> filterStrategies = new ArrayList<>();

                for(final SimplifiedStrategy simplifiedStrategy : filters.get(FilterType.IDENTIFIER)) {

                    final IdentifierFilterStrategy filterStrategy = new IdentifierFilterStrategy();
                    filterStrategy.setStrategy(convertStrategy(simplifiedStrategy));
                    filterStrategy.setConditions("confidence >= " + simplifiedStrategy.getSimplifiedCondition().getConfidenceAsDouble());
                    filterStrategy.setReplacementScope(simplifiedStrategy.getRedactionScope());
                    filterStrategy.setAnonymizationMethod(AnonymizationMethod.fromString(simplifiedStrategy.getAnonymizationMethod()));
                    filterStrategies.add(filterStrategy);

                }

                final Identifier filter = new Identifier();
                filter.setIdentifierFilterStrategies(filterStrategies);

                // TODO: Handle a list of identifiers.
                identifiers.setIdentifiers(List.of(filter));

            }

            if (filters.containsKey(FilterType.MAC_ADDRESS)) {

                final List<MacAddressFilterStrategy> filterStrategies = new ArrayList<>();

                for(final SimplifiedStrategy simplifiedStrategy : filters.get(FilterType.MAC_ADDRESS)) {

                    final MacAddressFilterStrategy filterStrategy = new MacAddressFilterStrategy();
                    filterStrategy.setStrategy(convertStrategy(simplifiedStrategy));
                    filterStrategy.setConditions("confidence >= " + simplifiedStrategy.getSimplifiedCondition().getConfidenceAsDouble());
                    filterStrategy.setReplacementScope(simplifiedStrategy.getRedactionScope());
                    filterStrategy.setAnonymizationMethod(AnonymizationMethod.fromString(simplifiedStrategy.getAnonymizationMethod()));
                    filterStrategies.add(filterStrategy);

                }

                final MacAddress filter = new MacAddress();
                filter.setMacAddressFilterStrategies(filterStrategies);

                identifiers.setMacAddress(filter);

            }

            if (filters.containsKey(FilterType.PASSPORT_NUMBER)) {

                final List<PassportNumberFilterStrategy> filterStrategies = new ArrayList<>();

                for(final SimplifiedStrategy simplifiedStrategy : filters.get(FilterType.PASSPORT_NUMBER)) {

                    final PassportNumberFilterStrategy filterStrategy = new PassportNumberFilterStrategy();
                    filterStrategy.setStrategy(convertStrategy(simplifiedStrategy));
                    filterStrategy.setConditions("confidence >= " + simplifiedStrategy.getSimplifiedCondition().getConfidenceAsDouble());
                    filterStrategy.setReplacementScope(simplifiedStrategy.getRedactionScope());
                    filterStrategy.setAnonymizationMethod(AnonymizationMethod.fromString(simplifiedStrategy.getAnonymizationMethod()));
                    filterStrategies.add(filterStrategy);

                }

                final PassportNumber filter = new PassportNumber();
                filter.setPassportNumberFilterStrategies(filterStrategies);

                identifiers.setPassportNumber(filter);

            }

            if (filters.containsKey(FilterType.PHONE_NUMBER)) {

                final List<PhoneNumberFilterStrategy> filterStrategies = new ArrayList<>();

                for(final SimplifiedStrategy simplifiedStrategy : filters.get(FilterType.PHONE_NUMBER)) {

                    final PhoneNumberFilterStrategy filterStrategy = new PhoneNumberFilterStrategy();
                    filterStrategy.setStrategy(convertStrategy(simplifiedStrategy));
                    filterStrategy.setConditions("confidence >= " + simplifiedStrategy.getSimplifiedCondition().getConfidenceAsDouble());
                    filterStrategy.setReplacementScope(simplifiedStrategy.getRedactionScope());
                    filterStrategy.setAnonymizationMethod(AnonymizationMethod.fromString(simplifiedStrategy.getAnonymizationMethod()));
                    filterStrategies.add(filterStrategy);

                }

                final PhoneNumber filter = new PhoneNumber();
                filter.setPhoneNumberFilterStrategies(filterStrategies);

                identifiers.setPhoneNumber(filter);

            }

            if (filters.containsKey(FilterType.SSN)) {

                final List<SsnFilterStrategy> filterStrategies = new ArrayList<>();

                for(final SimplifiedStrategy simplifiedStrategy : filters.get(FilterType.SSN)) {

                    final SsnFilterStrategy filterStrategy = new SsnFilterStrategy();
                    filterStrategy.setStrategy(convertStrategy(simplifiedStrategy));
                    filterStrategy.setConditions("confidence >= " + simplifiedStrategy.getSimplifiedCondition().getConfidenceAsDouble());
                    filterStrategy.setReplacementScope(simplifiedStrategy.getRedactionScope());
                    filterStrategy.setAnonymizationMethod(AnonymizationMethod.fromString(simplifiedStrategy.getAnonymizationMethod()));
                    filterStrategies.add(filterStrategy);

                }

                final Ssn filter = new Ssn();
                filter.setSsnFilterStrategies(filterStrategies);

                identifiers.setSsn(filter);

            }

            if (filters.containsKey(FilterType.STREET_ADDRESS)) {

                final List<StreetAddressFilterStrategy> filterStrategies = new ArrayList<>();

                for(final SimplifiedStrategy simplifiedStrategy : filters.get(FilterType.STREET_ADDRESS)) {

                    final StreetAddressFilterStrategy filterStrategy = new StreetAddressFilterStrategy();
                    filterStrategy.setStrategy(convertStrategy(simplifiedStrategy));
                    filterStrategy.setConditions("confidence >= " + simplifiedStrategy.getSimplifiedCondition().getConfidenceAsDouble());
                    filterStrategy.setReplacementScope(simplifiedStrategy.getRedactionScope());
                    filterStrategy.setAnonymizationMethod(AnonymizationMethod.fromString(simplifiedStrategy.getAnonymizationMethod()));
                    filterStrategies.add(filterStrategy);

                }

                final StreetAddress filter = new StreetAddress();
                filter.setStreetAddressFilterStrategies(filterStrategies);

                identifiers.setStreetAddress(filter);

            }

            if (filters.containsKey(FilterType.TRACKING_NUMBER)) {

                final List<TrackingNumberFilterStrategy> filterStrategies = new ArrayList<>();

                for(final SimplifiedStrategy simplifiedStrategy : filters.get(FilterType.TRACKING_NUMBER)) {

                    final TrackingNumberFilterStrategy filterStrategy = new TrackingNumberFilterStrategy();
                    filterStrategy.setStrategy(convertStrategy(simplifiedStrategy));
                    filterStrategy.setConditions("confidence >= " + simplifiedStrategy.getSimplifiedCondition().getConfidenceAsDouble());
                    filterStrategy.setReplacementScope(simplifiedStrategy.getRedactionScope());
                    filterStrategies.add(filterStrategy);

                }

                final TrackingNumber filter = new TrackingNumber();
                filter.setTrackingNumberFilterStrategies(filterStrategies);

                identifiers.setTrackingNumber(filter);

            }

            if (filters.containsKey(FilterType.URL)) {

                final List<UrlFilterStrategy> filterStrategies = new ArrayList<>();

                for(final SimplifiedStrategy simplifiedStrategy : filters.get(FilterType.URL)) {

                    final UrlFilterStrategy filterStrategy = new UrlFilterStrategy();
                    filterStrategy.setStrategy(convertStrategy(simplifiedStrategy));
                    filterStrategy.setConditions("confidence >= " + simplifiedStrategy.getSimplifiedCondition().getConfidenceAsDouble());
                    filterStrategy.setReplacementScope(simplifiedStrategy.getRedactionScope());
                    filterStrategy.setAnonymizationMethod(AnonymizationMethod.fromString(simplifiedStrategy.getAnonymizationMethod()));
                    filterStrategies.add(filterStrategy);

                }

                final Url filter = new Url();
                filter.setUrlFilterStrategies(filterStrategies);

                identifiers.setUrl(filter);

            }

            if (filters.containsKey(FilterType.VIN)) {

                final List<VinFilterStrategy> filterStrategies = new ArrayList<>();

                for(final SimplifiedStrategy simplifiedStrategy : filters.get(FilterType.VIN)) {

                    final VinFilterStrategy filterStrategy = new VinFilterStrategy();
                    filterStrategy.setStrategy(convertStrategy(simplifiedStrategy));
                    filterStrategy.setConditions("confidence >= " + simplifiedStrategy.getSimplifiedCondition().getConfidenceAsDouble());
                    filterStrategy.setReplacementScope(simplifiedStrategy.getRedactionScope());
                    filterStrategy.setAnonymizationMethod(AnonymizationMethod.fromString(simplifiedStrategy.getAnonymizationMethod()));
                    filterStrategies.add(filterStrategy);

                }

                final Vin filter = new Vin();
                filter.setVinFilterStrategies(filterStrategies);

                identifiers.setVin(filter);

            }

            if (filters.containsKey(FilterType.ZIP_CODE)) {

                final List<ZipCodeFilterStrategy> filterStrategies = new ArrayList<>();

                for(final SimplifiedStrategy simplifiedStrategy : filters.get(FilterType.ZIP_CODE)) {

                    final ZipCodeFilterStrategy filterStrategy = new ZipCodeFilterStrategy();
                    filterStrategy.setStrategy(convertStrategy(simplifiedStrategy));
                    filterStrategy.setConditions("confidence >= " + simplifiedStrategy.getSimplifiedCondition().getConfidenceAsDouble());
                    filterStrategy.setReplacementScope(simplifiedStrategy.getRedactionScope());
                    filterStrategy.setAnonymizationMethod(AnonymizationMethod.fromString(simplifiedStrategy.getAnonymizationMethod()));
                    filterStrategies.add(filterStrategy);

                }

                final ZipCode filter = new ZipCode();
                filter.setZipCodeFilterStrategies(filterStrategies);

                identifiers.setZipCode(filter);

            }

            // TODO: Add conditionals for the other filter types.

            policy.setIdentifiers(identifiers);
            return policy;

        } else {
            LOGGER.error("Invalid policy version: {}", version);
            return null;
        }

    }

    public static Policy generateRiskAssessmentPolicy(final Map<String, Double> scores) {

        final Policy policy = new Policy();

        // Configure the splitting options.
        final Splitting splitting = new Splitting();
        splitting.setThreshold(4000);
        splitting.setMethod("characters");
        splitting.setEnabled(true);

        // Configure PDF options.
        final Pdf pdf = new Pdf();
        pdf.setScale(1.0f);
        pdf.setDpi(300);
        pdf.setCompressionQuality(1.0f);
        pdf.setPreserveUnredactedPages(true);

        // Set the policy config.
        final Config config = new Config();
        config.setSplitting(splitting);
        config.setPdf(pdf);
        policy.setConfig(config);

        final Identifiers identifiers = new Identifiers();

        // TODO: What to do about identifier?
        // These are not currently used by risk assessment.
        // hospital
        // phone number extension
//        identifiers.setCounty(new County());
//        identifiers.setCity(new City());
//        identifiers.setState(new State());

        // Configure PhEye settings.
        final PhEyeConfiguration phEyeConfiguration = new PhEyeConfiguration();
        phEyeConfiguration.setEndpoint(PHEYE_ENDPOINT);
        phEyeConfiguration.setLabels(List.of("Person"));    // "Person" is expected back by Phileas

        final Map<String, Double> thresholds = new HashMap<>();
        thresholds.put("Person", 0.80);

        final PhEye phEyeFilter = new PhEye();
        phEyeFilter.setPhEyeConfiguration(phEyeConfiguration);
        phEyeFilter.setThresholds(thresholds);

        if(scores.containsKey(FilterType.PERSON.getType()) && scores.get(FilterType.PERSON.getType()) != null) {
            if (scores.get(FilterType.PERSON.getType()) > 0) {
                identifiers.setPerson(phEyeFilter);
            }
        }

        if(scores.containsKey(FilterType.SURNAME.getType()) && scores.get(FilterType.SURNAME.getType()) != null) {
            if (scores.get(FilterType.SURNAME.getType()) > 0) {
                final Surname surname = new Surname();
                surname.setFuzzy(false);
                identifiers.setSurname(surname);
            }
        }

        if(scores.containsKey(FilterType.FIRST_NAME.getType()) && scores.get(FilterType.FIRST_NAME.getType()) != null) {
            if (scores.get(FilterType.FIRST_NAME.getType()) > 0) {
                final FirstName firstName = new FirstName();
                firstName.setFuzzy(false);
                identifiers.setFirstName(firstName);
            }
        }

        if(scores.containsKey(FilterType.AGE.getType()) && scores.get(FilterType.AGE.getType()) != null) {
            if (scores.get(FilterType.AGE.getType()) > 0) {
                identifiers.setAge(new Age());
            }
        }

        if(scores.containsKey(FilterType.BANK_ROUTING_NUMBER.getType()) && scores.get(FilterType.BANK_ROUTING_NUMBER.getType()) != null) {
            if (scores.get(FilterType.BANK_ROUTING_NUMBER.getType()) > 0) {
                identifiers.setBankRoutingNumber(new BankRoutingNumber());
            }
        }

        if(scores.containsKey(FilterType.BITCOIN_ADDRESS.getType()) && scores.get(FilterType.BITCOIN_ADDRESS.getType()) != null) {
            if (scores.get(FilterType.BITCOIN_ADDRESS.getType()) > 0) {
                identifiers.setBitcoinAddress(new BitcoinAddress());
            }
        }

        if(scores.containsKey(FilterType.CREDIT_CARD.getType()) && scores.get(FilterType.CREDIT_CARD.getType()) != null) {
            if (scores.get(FilterType.CREDIT_CARD.getType()) > 0) {
                identifiers.setCreditCard(new CreditCard());
            }
        }

        if(scores.containsKey(FilterType.CURRENCY.getType()) && scores.get(FilterType.CURRENCY.getType()) != null) {
            if (scores.get(FilterType.CURRENCY.getType()) > 0) {
                identifiers.setCurrency(new Currency());
            }
        }

        if(scores.containsKey(FilterType.DATE.getType()) && scores.get(FilterType.DATE.getType()) != null) {
            if (scores.get(FilterType.DATE.getType()) > 0) {
                identifiers.setDate(new Date());
            }
        }

        if(scores.containsKey(FilterType.DRIVERS_LICENSE_NUMBER.getType()) && scores.get(FilterType.DRIVERS_LICENSE_NUMBER.getType()) != null) {
            if (scores.get(FilterType.DRIVERS_LICENSE_NUMBER.getType()) > 0) {
                identifiers.setDriversLicense(new DriversLicense());
            }
        }

        if(scores.containsKey(FilterType.EMAIL_ADDRESS.getType()) && scores.get(FilterType.EMAIL_ADDRESS.getType()) != null) {
            if (scores.get(FilterType.EMAIL_ADDRESS.getType()) > 0) {
                identifiers.setEmailAddress(new EmailAddress());
            }
        }

        if(scores.containsKey(FilterType.IBAN_CODE.getType()) && scores.get(FilterType.IBAN_CODE.getType()) != null) {
            if (scores.get(FilterType.IBAN_CODE.getType()) > 0) {
                identifiers.setIbanCode(new IbanCode());
            }
        }

        if(scores.containsKey(FilterType.IP_ADDRESS.getType()) && scores.get(FilterType.IP_ADDRESS.getType()) != null) {
            if (scores.get(FilterType.IP_ADDRESS.getType()) > 0) {
                identifiers.setIpAddress(new IpAddress());
            }
        }

        if(scores.containsKey(FilterType.MAC_ADDRESS.getType()) && scores.get(FilterType.MAC_ADDRESS.getType()) != null) {
            if (scores.get(FilterType.MAC_ADDRESS.getType()) > 0) {
                identifiers.setMacAddress(new MacAddress());
            }
        }

        if(scores.containsKey(FilterType.PASSPORT_NUMBER.getType()) && scores.get(FilterType.PASSPORT_NUMBER.getType()) != null) {
            if (scores.get(FilterType.PASSPORT_NUMBER.getType()) > 0) {
                identifiers.setPassportNumber(new PassportNumber());
            }
        }

        if(scores.containsKey(FilterType.PHONE_NUMBER.getType()) && scores.get(FilterType.PHONE_NUMBER.getType()) != null) {
            if (scores.get(FilterType.PHONE_NUMBER.getType()) > 0) {
                identifiers.setPhoneNumber(new PhoneNumber());
            }
        }

        if(scores.containsKey(FilterType.SSN.getType()) && scores.get(FilterType.SSN.getType()) != null) {
            if (scores.get(FilterType.SSN.getType()) > 0) {
                identifiers.setSsn(new Ssn());
            }
        }

        if(scores.containsKey(FilterType.STREET_ADDRESS.getType()) && scores.get(FilterType.STREET_ADDRESS.getType()) != null) {
            if (scores.get(FilterType.STREET_ADDRESS.getType()) > 0) {
                identifiers.setStreetAddress(new StreetAddress());
            }
        }

        if(scores.containsKey(FilterType.TRACKING_NUMBER.getType()) && scores.get(FilterType.TRACKING_NUMBER.getType()) != null) {
            if (scores.get(FilterType.TRACKING_NUMBER.getType()) > 0) {
                identifiers.setTrackingNumber(new TrackingNumber());
            }
        }

        if(scores.containsKey(FilterType.URL.getType()) && scores.get(FilterType.URL.getType()) != null) {
            if (scores.get(FilterType.URL.getType()) > 0) {
                identifiers.setUrl(new Url());
            }
        }

        if(scores.containsKey(FilterType.VIN.getType()) && scores.get(FilterType.VIN.getType()) != null) {
            if (scores.get(FilterType.VIN.getType()) > 0) {
                identifiers.setVin(new Vin());
            }
        }

        if(scores.containsKey(FilterType.ZIP_CODE.getType()) && scores.get(FilterType.ZIP_CODE.getType()) != null) {
            if (scores.get(FilterType.ZIP_CODE.getType()) > 0) {
                identifiers.setZipCode(new ZipCode());
            }
        }

        policy.setIdentifiers(identifiers);

        return policy;

    }

    private String convertStrategy(final SimplifiedStrategy simplifiedStrategy) {

        // If strategy is null or empty, default to REDACT without logging an error
        // This is expected behavior for policies that don't specify a strategy
        if(simplifiedStrategy.getStrategy() == null || simplifiedStrategy.getStrategy().isEmpty()) {
            return AbstractFilterStrategy.REDACT;
        }

        return convertStrategy(simplifiedStrategy.getStrategy());

    }

    private String convertStrategy(final String strategy) {

        // If strategy is null or empty, default to REDACT without logging an error
        // This is expected behavior for policies that don't specify a strategy
        if(strategy == null || strategy.isEmpty()) {
            return AbstractFilterStrategy.REDACT;
        }

        if("REDACT".equalsIgnoreCase(strategy)) {
            return AbstractFilterStrategy.REDACT;
        } else if("Anonymize".equalsIgnoreCase(strategy)) {
            return AbstractFilterStrategy.RANDOM_REPLACE;
        } else if("MASK".equalsIgnoreCase(strategy)) {
            return AbstractFilterStrategy.MASK;
        } else {
            LOGGER.error("Invalid strategy: {} - Defaulting to redact", strategy);
            return AbstractFilterStrategy.REDACT;
        }

    }

    @Override
    public String toString() {
        return new GsonBuilder().setPrettyPrinting().create().toJson(this);
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Map<FilterType, List<SimplifiedStrategy>> getFilters() {
        return filters;
    }

    public void setFilters(Map<FilterType, List<SimplifiedStrategy>> filters) {
        this.filters = filters;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<String> getTermsToIgnore() {
        return termsToIgnore;
    }

    public void setTermsToIgnore(List<String> termsToIgnore) {
        this.termsToIgnore = termsToIgnore;
    }

    public boolean isHighlightChangesinWordDocuments() {
        return highlightChangesinWordDocuments;
    }

    public void setHighlightChangesinWordDocuments(boolean highlightChangesinWordDocuments) {
        this.highlightChangesinWordDocuments = highlightChangesinWordDocuments;
    }

    public boolean isTurnOnRevisionsinWordDocuments() {
        return turnOnRevisionsinWordDocuments;
    }

    public void setTurnOnRevisionsinWordDocuments(boolean turnOnRevisionsinWordDocuments) {
        this.turnOnRevisionsinWordDocuments = turnOnRevisionsinWordDocuments;
    }

    public List<String> getTermsToAlwaysRedact() {
        return termsToAlwaysRedact;
    }

    public void setTermsToAlwaysRedact(List<String> termsToAlwaysRedact) {
        this.termsToAlwaysRedact = termsToAlwaysRedact;
    }

    public String getTermsToAlwaysRedactStrategy() {
        return termsToAlwaysRedactStrategy;
    }

    public void setTermsToAlwaysRedactStrategy(String termsToAlwaysRedactStrategy) {
        this.termsToAlwaysRedactStrategy = termsToAlwaysRedactStrategy;
    }

    public String getDisambiguationScope() {
        return disambiguationScope;
    }

    public void setDisambiguationScope(String disambiguationScope) {
        this.disambiguationScope = disambiguationScope;
    }

    public float getPdfScale() {
        return pdfScale;
    }

    public void setPdfScale(float pdfScale) {
        this.pdfScale = pdfScale;
    }

    public int getPdfDpi() {
        return pdfDpi;
    }

    public void setPdfDpi(int pdfDpi) {
        this.pdfDpi = pdfDpi;
    }

    public float getPdfCompressionQuality() {
        return pdfCompressionQuality;
    }

    public void setPdfCompressionQuality(float pdfCompressionQuality) {
        this.pdfCompressionQuality = pdfCompressionQuality;
    }
}

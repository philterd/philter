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
import ai.philterd.phileas.policy.Identifiers;
import ai.philterd.phileas.policy.Policy;
import ai.philterd.phileas.services.anonymization.AnonymizationMethod;
import ai.philterd.phileas.services.strategies.AbstractFilterStrategy;
import ai.philterd.phileas.services.strategies.rules.SsnFilterStrategy;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SimplifiedPolicyTest {

    @Test
    public void allSupportedFilterTypesYieldNoUnsupported() {
        final SimplifiedPolicy policy = new SimplifiedPolicy();
        policy.setFilters(Map.of(
                FilterType.SSN, List.of(new SimplifiedStrategy("REDACT")),
                FilterType.EMAIL_ADDRESS, List.of(new SimplifiedStrategy("REDACT")),
                FilterType.PERSON, List.of(new SimplifiedStrategy("REDACT"))));

        assertTrue(policy.getUnsupportedFilterTypes().isEmpty());
    }

    @Test
    public void unsupportedFilterTypesAreReported() {
        final SimplifiedPolicy policy = new SimplifiedPolicy();
        policy.setFilters(Map.of(
                FilterType.SSN, List.of(new SimplifiedStrategy("REDACT")),
                FilterType.MEDICAL_CONDITION, List.of(new SimplifiedStrategy("REDACT")),
                FilterType.HOSPITAL, List.of(new SimplifiedStrategy("REDACT"))));

        final Set<FilterType> unsupported = policy.getUnsupportedFilterTypes();

        assertEquals(Set.of(FilterType.MEDICAL_CONDITION, FilterType.HOSPITAL), unsupported);
    }

    @Test
    public void emptyPolicyHasNoUnsupportedFilterTypes() {
        assertTrue(new SimplifiedPolicy().getUnsupportedFilterTypes().isEmpty());
    }

    @Test
    public void supportedSetMatchesContainsCheck() {
        // Sanity: a representative supported type is in the published set.
        assertTrue(SimplifiedPolicy.SUPPORTED_FILTER_TYPES.contains(FilterType.ZIP_CODE));
    }

    @Test
    public void toPolicyMapsStaticReplaceWithItsConfiguredValue() throws Exception {

        final SimplifiedPolicy policy = new SimplifiedPolicy();
        policy.setFilters(Map.of(FilterType.SSN, List.of(new SimplifiedStrategy(
                "STATIC_REPLACE", Map.of(SimplifiedPolicy.PARAM_STATIC_REPLACEMENT, "REDACTED-SSN"),
                AnonymizationMethod.UUID))));

        final Policy phileasPolicy = policy.toPolicy("key", "tweak", null, null);
        final SsnFilterStrategy strategy = phileasPolicy.getIdentifiers().getSsn().getSsnFilterStrategies().get(0);

        assertEquals(AbstractFilterStrategy.STATIC_REPLACE, strategy.getStrategy());
        assertEquals("REDACTED-SSN", strategy.getStaticReplacement(),
                "the staticReplacement parameter must be wired onto the Phileas strategy");
    }

    @Test
    public void toPolicyMapsHashSha256ReplaceAndItsSaltFlag() throws Exception {

        final SimplifiedPolicy policy = new SimplifiedPolicy();
        policy.setFilters(Map.of(FilterType.SSN, List.of(new SimplifiedStrategy(
                "HASH_SHA256_REPLACE", Map.of(SimplifiedPolicy.PARAM_SALT, "true"), AnonymizationMethod.UUID))));

        final Policy phileasPolicy = policy.toPolicy("key", "tweak", null, null);
        final SsnFilterStrategy strategy = phileasPolicy.getIdentifiers().getSsn().getSsnFilterStrategies().get(0);

        assertEquals(AbstractFilterStrategy.HASH_SHA256_REPLACE, strategy.getStrategy());
        assertTrue(strategy.isSalt(), "the salt parameter must be wired onto the Phileas strategy");
    }

    @Test
    public void toPolicyMapsFpeEncryptReplaceAndCarriesTheFpeKeyAndTweak() throws Exception {

        final SimplifiedPolicy policy = new SimplifiedPolicy();
        policy.setFilters(Map.of(FilterType.SSN, List.of(new SimplifiedStrategy("FPE"))));

        final Policy phileasPolicy = policy.toPolicy("0123456789abcdef0123456789abcdef", "0011223344556677", null, null);

        final SsnFilterStrategy strategy = phileasPolicy.getIdentifiers().getSsn().getSsnFilterStrategies().get(0);
        assertEquals(AbstractFilterStrategy.FPE_ENCRYPT_REPLACE, strategy.getStrategy());

        // The key/tweak supplied to toPolicy must be carried on the policy for FF3 to use.
        assertEquals("0123456789abcdef0123456789abcdef", phileasPolicy.getFpe().getKey());
        assertEquals("0011223344556677", phileasPolicy.getFpe().getTweak());
    }

    @Test
    public void usesStrategyDetectsWhetherAPolicyRequiresAGivenStrategy() {

        final SimplifiedPolicy fpePolicy = new SimplifiedPolicy();
        fpePolicy.setFilters(Map.of(
                FilterType.SSN, List.of(new SimplifiedStrategy("FPE")),
                FilterType.EMAIL_ADDRESS, List.of(new SimplifiedStrategy("REDACT"))));
        assertTrue(fpePolicy.usesStrategy(AbstractFilterStrategy.FPE_ENCRYPT_REPLACE));

        final SimplifiedPolicy noFpePolicy = new SimplifiedPolicy();
        noFpePolicy.setFilters(Map.of(FilterType.SSN, List.of(new SimplifiedStrategy("REDACT"))));
        assertFalse(noFpePolicy.usesStrategy(AbstractFilterStrategy.FPE_ENCRYPT_REPLACE));

        // An empty policy requires nothing.
        assertFalse(new SimplifiedPolicy().usesStrategy(AbstractFilterStrategy.FPE_ENCRYPT_REPLACE));
    }

    @Test
    public void staticReplaceWithoutAValueIsReportedAsAConfigurationError() {

        final SimplifiedPolicy policy = new SimplifiedPolicy();
        policy.setFilters(Map.of(FilterType.SSN, List.of(new SimplifiedStrategy("STATIC_REPLACE"))));

        final String error = policy.getStrategyConfigurationError();
        assertNotNull(error);
        assertTrue(error.contains("SSN"), "the error should name the offending filter");
    }

    @Test
    public void wellConfiguredStrategiesReportNoConfigurationError() {

        final SimplifiedPolicy policy = new SimplifiedPolicy();
        policy.setFilters(Map.of(
                FilterType.SSN, List.of(new SimplifiedStrategy(
                        "STATIC_REPLACE", Map.of(SimplifiedPolicy.PARAM_STATIC_REPLACEMENT, "x"), AnonymizationMethod.UUID)),
                FilterType.EMAIL_ADDRESS, List.of(new SimplifiedStrategy("REDACT"))));

        assertNull(policy.getStrategyConfigurationError());
    }

    @Test
    public void toPolicyMapsEverySupportedFilterType() throws Exception {

        // Maps each supported filter type to the Identifiers getter that toPolicy() should populate.
        final Map<FilterType, Function<Identifiers, Object>> getters = new HashMap<>();
        getters.put(FilterType.AGE, Identifiers::getAge);
        getters.put(FilterType.BANK_ROUTING_NUMBER, Identifiers::getBankRoutingNumber);
        getters.put(FilterType.BITCOIN_ADDRESS, Identifiers::getBitcoinAddress);
        getters.put(FilterType.CREDIT_CARD, Identifiers::getCreditCard);
        getters.put(FilterType.CURRENCY, Identifiers::getCurrency);
        getters.put(FilterType.DATE, Identifiers::getDate);
        getters.put(FilterType.DRIVERS_LICENSE_NUMBER, Identifiers::getDriversLicense);
        getters.put(FilterType.EMAIL_ADDRESS, Identifiers::getEmailAddress);
        getters.put(FilterType.FIRST_NAME, Identifiers::getFirstName);
        getters.put(FilterType.IBAN_CODE, Identifiers::getIbanCode);
        getters.put(FilterType.IDENTIFIER, Identifiers::getIdentifiers);
        getters.put(FilterType.IP_ADDRESS, Identifiers::getIpAddress);
        getters.put(FilterType.LOCATION_CITY, Identifiers::getCity);
        getters.put(FilterType.LOCATION_COUNTY, Identifiers::getCounty);
        getters.put(FilterType.LOCATION_STATE, Identifiers::getState);
        getters.put(FilterType.MAC_ADDRESS, Identifiers::getMacAddress);
        getters.put(FilterType.PASSPORT_NUMBER, Identifiers::getPassportNumber);
        getters.put(FilterType.PERSON, Identifiers::getPerson);
        getters.put(FilterType.PHONE_NUMBER, Identifiers::getPhoneNumber);
        getters.put(FilterType.SSN, Identifiers::getSsn);
        getters.put(FilterType.STREET_ADDRESS, Identifiers::getStreetAddress);
        getters.put(FilterType.SURNAME, Identifiers::getSurname);
        getters.put(FilterType.TRACKING_NUMBER, Identifiers::getTrackingNumber);
        getters.put(FilterType.URL, Identifiers::getUrl);
        getters.put(FilterType.VIN, Identifiers::getVin);
        getters.put(FilterType.ZIP_CODE, Identifiers::getZipCode);

        // If this fails, a type was added to SUPPORTED_FILTER_TYPES without being covered here.
        assertEquals(SimplifiedPolicy.SUPPORTED_FILTER_TYPES, getters.keySet(),
                "Test getter map is out of sync with SUPPORTED_FILTER_TYPES");

        // Each supported type must actually be translated into a Phileas filter by toPolicy().
        for (final FilterType filterType : SimplifiedPolicy.SUPPORTED_FILTER_TYPES) {

            final SimplifiedPolicy policy = new SimplifiedPolicy();
            policy.setFilters(Map.of(filterType, List.of(new SimplifiedStrategy("REDACT"))));

            final Policy phileasPolicy = policy.toPolicy("key", "tweak", null, null);
            final Object mapped = getters.get(filterType).apply(phileasPolicy.getIdentifiers());

            if (mapped instanceof Collection<?> collection) {
                assertFalse(collection.isEmpty(), filterType + " was not mapped by toPolicy()");
            } else {
                assertNotNull(mapped, filterType + " was not mapped by toPolicy()");
            }

        }

    }

}

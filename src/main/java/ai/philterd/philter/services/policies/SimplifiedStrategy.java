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

import ai.philterd.phileas.services.anonymization.AnonymizationMethod;
import ai.philterd.phileas.services.strategies.AbstractFilterStrategy;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class SimplifiedStrategy {

    private final String strategy;
    private final Map<String, String> parameters;
    private final SimplifiedCondition simplifiedCondition;
    private final String redactionScope;
    private final String anonymizationMethod;

    public SimplifiedStrategy(final String strategy) {
        this.strategy = strategy;
        this.parameters = new HashMap<>();
        this.simplifiedCondition = new SimplifiedCondition();
        this.redactionScope = AbstractFilterStrategy.REPLACEMENT_SCOPE_DOCUMENT;
        this.anonymizationMethod = AnonymizationMethod.UUID.getValue();
    }

    public SimplifiedStrategy(final String strategy, final AnonymizationMethod anonymizationMethod) {
        this.strategy = strategy;
        this.parameters = new HashMap<>();
        this.simplifiedCondition = new SimplifiedCondition();
        this.redactionScope = AbstractFilterStrategy.REPLACEMENT_SCOPE_DOCUMENT;
        this.anonymizationMethod = anonymizationMethod.getValue();
    }

    public SimplifiedStrategy(final String strategy, final SimplifiedCondition simplifiedCondition, final String redactionScope) {
        this.strategy = strategy;
        this.parameters = Collections.emptyMap();
        this.simplifiedCondition = simplifiedCondition;
        this.redactionScope = redactionScope;
        this.anonymizationMethod = AnonymizationMethod.UUID.getValue();
    }

    public SimplifiedStrategy(final String strategy, final SimplifiedCondition simplifiedCondition, final String redactionScope, final AnonymizationMethod anonymizationMethod) {
        this.strategy = strategy;
        this.parameters = Collections.emptyMap();
        this.simplifiedCondition = simplifiedCondition;
        this.redactionScope = redactionScope;
        this.anonymizationMethod = anonymizationMethod.getValue();
    }

    public SimplifiedStrategy(final String strategy, final Map<String, String> parameters, final AnonymizationMethod anonymizationMethod) {
        this.strategy = strategy;
        this.parameters = parameters;
        this.simplifiedCondition = new SimplifiedCondition();
        this.redactionScope = AbstractFilterStrategy.REPLACEMENT_SCOPE_DOCUMENT;
        this.anonymizationMethod = anonymizationMethod.getValue();
    }

    public SimplifiedStrategy(final String strategy, final Map<String, String> parameters, final SimplifiedCondition simplifiedCondition, final String redactionScope, final AnonymizationMethod anonymizationMethod) {
        this.strategy = strategy;
        this.parameters = parameters;
        this.simplifiedCondition = simplifiedCondition;
        this.redactionScope = redactionScope;
        this.anonymizationMethod = anonymizationMethod.getValue();
    }

    public String getStrategy() {
        return strategy;
    }

    public Map<String, String> getParameters() {
        return parameters;
    }

    public SimplifiedCondition getSimplifiedCondition() {
        return simplifiedCondition;
    }

    public String getRedactionScope() {
        return redactionScope;
    }

    public String getAnonymizationMethod() {
        return anonymizationMethod;
    }

}

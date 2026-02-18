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
package ai.philterd.philter;

import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.util.Properties;

public class PhilterConfiguration {

   private final Properties properties;

    public PhilterConfiguration(final Properties properties) throws IOException {
        this.properties = properties;
    }

    private String getProperty(final String property, final String defaultValue) {

        final String environmentVariableValue = getEnvironmentVariable(property);

        if(!StringUtils.isEmpty(environmentVariableValue)) {
            return environmentVariableValue;
        }

        final String systemPropertyValue = getSystemProperty(property);

        if(!StringUtils.isEmpty(systemPropertyValue)) {
            return systemPropertyValue;
        }

        final String propertyFileValue = getFileProperty(property);

        if(!StringUtils.isEmpty(propertyFileValue)) {
            return propertyFileValue;
        }

        return defaultValue;

    }

    private String getEnvironmentVariable(final String environmentVariable) {
        return System.getenv(environmentVariable);
    }

    private String getSystemProperty(final String property) {
        return System.getProperty(property);
    }

    private String getFileProperty(final String property) {
        return properties.getProperty(property);
    }

}
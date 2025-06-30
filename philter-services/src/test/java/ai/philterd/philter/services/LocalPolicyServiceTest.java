/*
 *     Copyright 2025 Philterd, LLC @ https://www.philterd.ai
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
package ai.philterd.philter.services;

import ai.philterd.phileas.model.policy.Identifiers;
import ai.philterd.phileas.model.policy.Policy;
import ai.philterd.phileas.model.policy.filters.Age;
import ai.philterd.phileas.model.policy.filters.strategies.rules.AgeFilterStrategy;
import ai.philterd.phileas.model.services.CacheService;
import ai.philterd.philter.PhilterConfiguration;
import ai.philterd.philter.services.policies.LocalPolicyService;
import ai.philterd.philter.services.policies.PolicyService;
import com.google.gson.Gson;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class LocalPolicyServiceTest {

    private final Gson gson = new Gson();

    private PhilterConfiguration getConfiguration() throws IOException {

        final String tempDirectory = Files.createTempDirectory("phileas-policies").toFile().getAbsolutePath();

        final Properties properties = new Properties();
        properties.setProperty("filter.policies.directory", tempDirectory);

        return new PhilterConfiguration(properties, "test-philter");

    }

    @Test
    public void list() throws IOException {

        final CacheService cacheService = Mockito.mock(CacheService.class);

        final PolicyService policyService = new LocalPolicyService(getConfiguration(), cacheService);

        policyService.save(getPolicy("name1"));
        policyService.save(getPolicy("name2"));

        final List<String> names = policyService.get();

        Assert.assertEquals(2, names.size());
        Assert.assertTrue(names.contains("name1"));
        Assert.assertTrue(names.contains("name2"));

    }

    @Test
    public void getAll() throws IOException {

        final CacheService cacheService = Mockito.mock(CacheService.class);

        final PolicyService policyService = new LocalPolicyService(getConfiguration(), cacheService);

        policyService.save(getPolicy("name1"));
        policyService.save(getPolicy("name2"));

        final Map<String, Policy> all = policyService.getAll();

        Assert.assertEquals(2, all.size());
        Assert.assertTrue(all.containsKey("name1"));
        Assert.assertTrue(all.containsKey("name2"));

    }

    @Test
    public void save() throws IOException {

        final CacheService cacheService = Mockito.mock(CacheService.class);

        final String name = "default";

        final Policy policy = getPolicy(name);

        final PolicyService policyService = new LocalPolicyService(getConfiguration(), cacheService);

        policyService.save(policy);

        final Policy saved = policyService.get("default");

        Assert.assertNotNull(saved);
        Assert.assertEquals(gson.toJson(policy), gson.toJson(saved));

    }

    @Test
    public void get() throws IOException {

        final CacheService cacheService = Mockito.mock(CacheService.class);

        final String name = "default";

        final Policy policy = getPolicy(name);

        final PolicyService policyService = new LocalPolicyService(getConfiguration(), cacheService);

        policyService.save(policy);

        final Policy retrievedPolicy = policyService.get(name);

        Assert.assertEquals(gson.toJson(policy), (gson.toJson(retrievedPolicy)));

    }

    @Test
    public void delete() throws IOException {

        final CacheService cacheService = Mockito.mock(CacheService.class);

        final String name = "default";
        final Policy policy = getPolicy(name);

        final PolicyService policyService = new LocalPolicyService(getConfiguration(), cacheService);

        policyService.save(policy);

        policyService.delete(name);

        Assert.assertFalse(policyService.getAll().containsKey(name));

    }

    @Test
    public void deleteOutsidePath() throws IOException {

        final CacheService cacheService = Mockito.mock(CacheService.class);

        final File tempFile = File.createTempFile("phileas-", "-temp");
        tempFile.deleteOnExit();

        Assert.assertTrue(Files.exists(tempFile.toPath()));

        final String name = "../" + tempFile.getName();

        final PolicyService policyService = new LocalPolicyService(getConfiguration(), cacheService);

        Assert.assertThrows(IOException.class, () -> policyService.delete(name));

    }

    private Policy getPolicy(String name) {

        AgeFilterStrategy ageFilterStrategy = new AgeFilterStrategy();

        Age age = new Age();
        age.setAgeFilterStrategies(List.of(ageFilterStrategy));

        Identifiers identifiers = new Identifiers();

        identifiers.setAge(age);

        Policy policy = new Policy();
        policy.setName(name);
        policy.setIdentifiers(identifiers);

        return policy;

    }

}

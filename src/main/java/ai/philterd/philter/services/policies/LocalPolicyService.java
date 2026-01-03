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

import ai.philterd.phileas.policy.Policy;
import ai.philterd.philter.PhilterConfiguration;
import com.google.gson.Gson;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class LocalPolicyService implements PolicyService {

    private static final Logger LOGGER = LogManager.getLogger(LocalPolicyService.class);

    private static final String JSON_EXTENSION = ".json";

    private final String policiesDirectory;
    private final Gson gson;

    public LocalPolicyService(final PhilterConfiguration philterConfiguration) {

        this.policiesDirectory = philterConfiguration.policiesDirectory();
        LOGGER.info("Looking for policies in {}", policiesDirectory);

        this.gson = new Gson();

    }

    @Override
    public List<String> get() throws IOException {

        // This function never uses a cache.

        final List<String> names = new LinkedList<>();

        // Read the policies from the file system.
        final Collection<File> files = FileUtils.listFiles(new File(policiesDirectory), new String[]{"json"}, false);

        for(final File file : files) {

            final String json = FileUtils.readFileToString(file, Charset.defaultCharset());

            final JSONObject object = new JSONObject(json);
            final String name = object.getString("name");

            names.add(name);

        }

        return names;

    }

    @Override
    public Policy get(String policyName) throws IOException {

        final Policy policy;

        final File file = new File(policiesDirectory, policyName + JSON_EXTENSION);

        if (file.exists()) {

            final String policyJson = FileUtils.readFileToString(file, Charset.defaultCharset());
            policy = gson.fromJson(policyJson, Policy.class);

        } else {
            throw new FileNotFoundException("Policy [" + policyName + "] does not exist.");
        }

        return policy;

    }

    @Override
    public Map<String, Policy> getAll() throws IOException {

        final Map<String, Policy> policies = new HashMap<>();

        // Read the policies from the file system.
        final Collection<File> files = FileUtils.listFiles(new File(policiesDirectory), new String[]{"json"}, false);
        LOGGER.info("Found {} policies", files.size());

        for (final File file : files) {

            LOGGER.info("Loading policy {}", file.getAbsolutePath());
            final String json = FileUtils.readFileToString(file, Charset.defaultCharset());

            final Policy policy = gson.fromJson(json, Policy.class);

            policies.put(policy.getName(), policy);
            LOGGER.info("Added policy named [{}]", policy.getName());

        }

        return policies;

    }

    @Override
    public void save(Policy policy) throws IOException {

        final String policyName = policy.getName();
        final String policyJson = gson.toJson(policy);

        final File file = new File(policiesDirectory, policyName + JSON_EXTENSION);

        FileUtils.writeStringToFile(file, policyJson, Charset.defaultCharset());

    }

    @Override
    public void delete(String policyName) throws IOException {

        final File file = new File(policiesDirectory, policyName + JSON_EXTENSION);

        LOGGER.info("Deleting policy at: {}", file.getAbsolutePath());

        if(file.exists()) {

            if(!file.delete()) {
                throw new IOException("Unable to delete policy " + policyName + JSON_EXTENSION);
            }

        } else {
            throw new FileNotFoundException("Policy with name " + policyName + " does not exist.");
        }

    }

}

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
package ai.philterd.philter.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the built documentation at {@code /public/docs/}. The documentation is built and placed on
 * the filesystem by the Docker image (see the Dockerfile's docs stage); this maps that directory to
 * the URL the UI links to (for example, the footer's "Documentation" link to
 * {@code /public/docs/index.html}).
 * <p>
 * The location is configurable via {@code philter.docs.location} and defaults to the path the Docker
 * image uses. When the directory is absent (for example, local development that did not build the
 * docs), requests under {@code /public/docs/} simply return 404 — the same behavior as before.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${philter.docs.location:file:/opt/philter/public/docs/}")
    private String docsLocation;

    @Override
    public void addResourceHandlers(final ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/public/docs/**").addResourceLocations(docsLocation);
    }

}

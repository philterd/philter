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
package ai.philterd.philter.api.filters.size;

import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.IOException;

/**
 * A wrapper for HttpServletRequest that returns a SizeLimitingInputStream
 * to enforce file size limits on uploaded files.
 */
public class SizeLimitingRequestWrapper extends HttpServletRequestWrapper {

    private final long maxSizeBytes;

    /**
     * Creates a new SizeLimitingRequestWrapper.
     *
     * @param request the original HttpServletRequest
     * @param maxSizeBytes the maximum number of bytes allowed for the request body
     */
    public SizeLimitingRequestWrapper(final HttpServletRequest request, final long maxSizeBytes) {
        super(request);
        this.maxSizeBytes = maxSizeBytes;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        return new SizeLimitingInputStream(super.getInputStream(), maxSizeBytes);
    }

}

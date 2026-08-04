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
package ai.philterd.philter.api.filters.content;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;

/**
 * Exposes the first bytes of the request body for format detection, then replays them so the body is
 * still readable in full.
 *
 * <p>Only {@link DocumentType#SIGNATURE_LENGTH} bytes are buffered, so a 10 MB document is not held
 * in memory to check its first eight bytes.
 */
public class ContentTypeVerifyingRequestWrapper extends HttpServletRequestWrapper {

    private final byte[] leadingBytes;
    private final InputStream body;

    public ContentTypeVerifyingRequestWrapper(final HttpServletRequest request) throws IOException {

        super(request);

        final InputStream original = request.getInputStream();
        final byte[] peeked = original.readNBytes(DocumentType.SIGNATURE_LENGTH);

        this.leadingBytes = peeked;
        this.body = new SequenceInputStream(new ByteArrayInputStream(peeked), original);

    }

    /** The leading bytes of the body, up to {@link DocumentType#SIGNATURE_LENGTH}. */
    public byte[] getLeadingBytes() {
        return leadingBytes;
    }

    @Override
    public ServletInputStream getInputStream() {

        return new ServletInputStream() {

            @Override
            public int read() throws IOException {
                return body.read();
            }

            @Override
            public int read(final byte[] b, final int off, final int len) throws IOException {
                return body.read(b, off, len);
            }

            @Override
            public boolean isFinished() {
                try {
                    return body.available() == 0;
                } catch (final IOException e) {
                    return true;
                }
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(final ReadListener readListener) {
                throw new UnsupportedOperationException("Asynchronous reads are not supported.");
            }

        };

    }

}

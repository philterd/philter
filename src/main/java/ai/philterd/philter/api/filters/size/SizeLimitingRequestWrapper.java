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

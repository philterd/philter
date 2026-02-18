package ai.philterd.philter.api.filters.size;

import ai.philterd.philter.api.exceptions.PayloadTooLargeException;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

/**
 * A ServletInputStream wrapper that tracks the number of bytes read and enforces a maximum size limit.
 * Throws an IOException if the size limit is exceeded during reading.
 */
public class SizeLimitingInputStream extends ServletInputStream {

    private static final Logger LOGGER = LoggerFactory.getLogger(SizeLimitingInputStream.class);

    private final InputStream inputStream;
    private final long maxSizeBytes;
    private long bytesRead;
    private boolean finished;

    /**
     * Creates a new SizeLimitingInputStream.
     *
     * @param inputStream the underlying input stream to read from
     * @param maxSizeBytes the maximum number of bytes that can be read before an exception is thrown
     */
    public SizeLimitingInputStream(final InputStream inputStream, final long maxSizeBytes) {
        this.inputStream = inputStream;
        this.maxSizeBytes = maxSizeBytes;
        this.bytesRead = 0;
        this.finished = false;
    }

    @Override
    public int read() throws IOException {
        final int data = inputStream.read();
        if (data != -1) {
            bytesRead++;
            checkSizeLimit();
        } else {
            finished = true;
        }
        return data;
    }

    @Override
    public int read(final byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    @Override
    public int read(final byte[] b, final int off, final int len) throws IOException {
        final int bytesReadNow = inputStream.read(b, off, len);
        if (bytesReadNow > 0) {
            bytesRead += bytesReadNow;
            checkSizeLimit();
        } else if (bytesReadNow == -1) {
            finished = true;
        }
        return bytesReadNow;
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public void setReadListener(final ReadListener readListener) {
        throw new UnsupportedOperationException("ReadListener is not supported");
    }

    /**
     * Gets the total number of bytes read so far.
     *
     * @return the number of bytes read
     */
    public long getBytesRead() {
        return bytesRead;
    }

    /**
     * Checks if the size limit has been exceeded and throws a PayloadTooLargeException if so.
     *
     * @throws PayloadTooLargeException if the size limit has been exceeded
     */
    private void checkSizeLimit() {

        if (bytesRead > maxSizeBytes) {

            LOGGER.warn("Size limit exceeded: {} bytes read, max allowed: {}", bytesRead, maxSizeBytes);
            throw new PayloadTooLargeException("Security Alert: Data stream exceeded allowed limit of " + maxSizeBytes + " bytes.");

        }

    }

}

package ai.philterd.philter.services.filtering;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Encodes the PDF result in the representation selected by the caller. */
public final class BinaryOutput {
    private BinaryOutput() { }

    public static byte[] encode(byte[] pdf, String outputMimeType) throws IOException {
        if (!"application/zip".equals(outputMimeType)) return pdf;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            final ZipEntry entry = new ZipEntry("redacted.pdf");
            entry.setTime(0);
            zip.putNextEntry(entry);
            zip.write(pdf);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}

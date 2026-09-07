package ai.philterd.philter.services.filtering;

import ai.philterd.philter.api.exceptions.BadRequestException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class PdfInputValidatorTest {
    @Test void auditMalformedInputIsAClientError() {
        assertThrows(BadRequestException.class, () -> PdfInputValidator.validate(
                "%PDF-1.7\ninvalid truncated document".getBytes(StandardCharsets.UTF_8)));
    }
    @Test void unsupportedInputIsAClientError() {
        assertThrows(BadRequestException.class, () -> PdfInputValidator.validate(new byte[]{1, 2, 3}));
    }
    @Test void validPdfWithNoExtractableTextIsAcceptedWithoutOcr() throws Exception {
        assertDoesNotThrow(() -> PdfInputValidator.validate(pdf(false)));
    }
    @Test void passwordProtectedPdfIsAClientError() throws Exception {
        assertThrows(BadRequestException.class, () -> PdfInputValidator.validate(pdf(true)));
    }
    @Test void truncatedPdfIsAClientError() throws Exception {
        assertThrows(BadRequestException.class, () -> PdfInputValidator.validate(java.util.Arrays.copyOf(pdf(false), 20)));
    }
    private byte[] pdf(boolean encrypted) throws Exception {
        try (var doc = new PDDocument(); var out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            if (encrypted) doc.protect(new StandardProtectionPolicy("owner-secret", "user-secret", new AccessPermission()));
            doc.save(out); return out.toByteArray();
        }
    }
}

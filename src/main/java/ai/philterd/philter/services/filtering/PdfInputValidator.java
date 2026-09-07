package ai.philterd.philter.services.filtering;

import ai.philterd.philter.api.exceptions.BadRequestException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import java.io.IOException;

/** Classifies input parsing failures without masking downstream storage or processing failures. */
public final class PdfInputValidator {
    private PdfInputValidator() { }

    public static void validate(final byte[] input) {
        // This overload parses the supplied bytes in memory: no application storage or network I/O.
        try (var document = Loader.loadPDF(input)) {
            if (document.getNumberOfPages() == 0) {
                throw new BadRequestException("PDF input must contain at least one page.");
            }
        } catch (InvalidPasswordException ex) {
            throw new BadRequestException("Password-protected PDF input is not supported.");
        } catch (IOException ex) {
            throw new BadRequestException("PDF input is malformed or cannot be parsed.");
        }
    }
}

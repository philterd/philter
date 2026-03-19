package ai.philterd.philter.model;

public enum ContentType {

    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", ".docx"),
    PDF("application/pdf", ".pdf"),
    TXT("text/plain", ".txt");

    private final String contentType;
    private final String extension;

    ContentType(final String contentType, final String extension) {
        this.contentType = contentType;
        this.extension = extension;
    }

    public static ContentType fromString(final String contentType) {

        for (final ContentType ct : ContentType.values()) {

            if (ct.contentType.equals(contentType)) {
                return ct;
            }

        }

        return null;
    }

    public static boolean isValidContentType(final String contentType) {
        return fromString(contentType) != null;
    }

    public String getExtension() {
        return extension;
    }

    @Override
    public String toString() {
        return contentType;
    }

    public String getContentType() {
        return contentType;
    }

}

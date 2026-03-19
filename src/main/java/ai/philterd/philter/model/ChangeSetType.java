package ai.philterd.philter.model;

public enum ChangeSetType {

    PAGE("page"),
    PARAGRAPH("paragraph"),
    LINE_NUMBER("line_number"),
    NONE("none");

    private final String type;

    private ChangeSetType(final String type) {
        this.type = type;
    }

    public String getType() {
        return type;
    }

    @Override
    public String toString() {
        return type;
    }

}

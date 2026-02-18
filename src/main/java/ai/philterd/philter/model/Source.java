package ai.philterd.philter.model;

public enum Source {

    WEBUI("webui"),
    API("api"),
    SYSTEM("system");

    private final String source;

    Source(final String source) {
        this.source = source;
    }

    @Override
    public String toString() {
        return source;
    }

    public String getSource() {
        return source;
    }

}

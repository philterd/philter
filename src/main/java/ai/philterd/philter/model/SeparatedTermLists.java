package ai.philterd.philter.model;

import java.util.List;

public class SeparatedTermLists {

    private final List<String> fuzzy;
    private final List<String> exact;

    public SeparatedTermLists(final List<String> fuzzy, final List<String> exact) {
        this.fuzzy = fuzzy;
        this.exact = exact;
    }

    public List<String> getFuzzy() {
        return fuzzy;
    }

    public List<String> getExact() {
        return exact;
    }

}

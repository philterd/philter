package ai.philterd.philter.ui.domain;

public class Profile {

    private String name;
    private String description;
    private int filters;
    private int enabledFilters;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getFilters() {
        return filters;
    }

    public void setFilters(int filters) {
        this.filters = filters;
    }

    public int getEnabledFilters() {
        return enabledFilters;
    }

    public void setEnabledFilters(int enabledFilters) {
        this.enabledFilters = enabledFilters;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

}

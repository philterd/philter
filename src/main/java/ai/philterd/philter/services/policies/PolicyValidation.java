package ai.philterd.philter.services.policies;

public class PolicyValidation {

    private final boolean valid;
    private final String message;

    private PolicyValidation(final boolean valid, final String message) {
        this.valid = valid;
        this.message = message;
    }

    public static PolicyValidation valid(final String message) {
        return new PolicyValidation(true, message);
    }

    public static PolicyValidation invalid(final String message) {
        return new PolicyValidation(false, message);
    }

    public boolean isValid() {
        return valid;
    }

    public String getMessage() {
        return message;
    }

}

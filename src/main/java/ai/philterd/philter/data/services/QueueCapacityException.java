package ai.philterd.philter.data.services;

public final class QueueCapacityException extends RuntimeException {
    private final int status;
    public QueueCapacityException(String message, int status) { super(message); this.status = status; }
    public int getStatus() { return status; }
}

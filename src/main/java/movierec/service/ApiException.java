package movierec.service;

/** The online movie database could not be used. The message is safe to show to the user. */
public class ApiException extends Exception {
    private final int status;     // HTTP status, or 0 for network problems

    public ApiException(String message, int status) { super(message); this.status = status; }
    public ApiException(String message, Throwable cause) { super(message, cause); this.status = 0; }

    public int getStatus() { return status; }
}

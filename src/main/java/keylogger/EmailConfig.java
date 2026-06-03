package keylogger;

/** Holds the user-configured email notification settings. */
public class EmailConfig {

    private final boolean enabled;
    private final String  sender;
    private final String  appPassword;
    private final String  recipient;
    private final String  smtpServer;
    private final int     smtpPort;

    /** Default (disabled) configuration. */
    public EmailConfig() {
        this(false, "", "", "", "smtp.gmail.com", 587);
    }

    public EmailConfig(boolean enabled, String sender, String appPassword,
                       String recipient, String smtpServer, int smtpPort) {
        this.enabled     = enabled;
        this.sender      = sender;
        this.appPassword = appPassword;
        this.recipient   = recipient;
        this.smtpServer  = smtpServer;
        this.smtpPort    = smtpPort;
    }

    public boolean isEnabled()     { return enabled; }
    public String  getSender()     { return sender; }
    public String  getAppPassword(){ return appPassword; }
    public String  getRecipient()  { return recipient; }
    public String  getSmtpServer() { return smtpServer; }
    public int     getSmtpPort()   { return smtpPort; }
}

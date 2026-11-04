import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.view.InputDevice;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Shell-side client (app_process, shell uid) that selects br.casa.tvkeys' key layout for the TV's IR remote
 * through the normal InputManager service API. No root, no system files touched.
 * Adapted from Lzchyi's sony-apps-layout companion (MIT), for the Sony mtkinp_ir_events remote on Android 9.
 *
 *   LayoutCtl inspect
 *   LayoutCtl layouts
 *   LayoutCtl trial <state.json> <seconds>   apply "cores"; restore unless <state.json>.commit appears in time
 *   LayoutCtl restore <state.json>           reverse overlay, then the saved selection
 */
public final class LayoutCtl {
    static final String DEVICE = "mtkinp_ir_events";
    static final String PREFIX = "br.casa.tvkeys/br.casa.tvkeys.Layouts/";
    static final String FORWARD = PREFIX + "cores", REVERSE = PREFIX + "original";
    static Object manager, identifier;
    static Class<?> api, idClass;
    static InputDevice remote;

    static void init() throws Exception {
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "input");
        api = Class.forName("android.hardware.input.IInputManager");
        manager = Class.forName("android.hardware.input.IInputManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
        idClass = Class.forName("android.hardware.input.InputDeviceIdentifier");
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice candidate = InputDevice.getDevice(id);
            if (candidate != null && !candidate.isVirtual() && DEVICE.equals(candidate.getName())) {
                if (remote != null) throw new IllegalStateException("More than one " + DEVICE);
                remote = candidate;
            }
        }
        if (remote == null) throw new IllegalStateException(DEVICE + " not found");
        identifier = idClass.getConstructor(String.class, int.class, int.class)
                .newInstance(remote.getDescriptor(), remote.getVendorId(), remote.getProductId());
    }

    static Object call(String name, Object... args) throws Exception {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) types[i] = i == 0 ? idClass : String.class;
        return api.getMethod(name, types).invoke(manager, args);
    }

    static String current() throws Exception {
        return (String) call("getCurrentKeyboardLayoutForInputDevice", identifier);
    }

    static String[] enabled() throws Exception {
        String[] result = (String[]) call("getEnabledKeyboardLayoutsForInputDevice", identifier);
        return result == null ? new String[0] : result;
    }

    static void set(String layout) throws Exception {
        call("setCurrentKeyboardLayoutForInputDevice", identifier, layout);
        SystemClock.sleep(450);
        if (!layout.equals(current())) throw new IllegalStateException("Selection not retained: " + layout);
    }

    static JSONObject inspect() throws Exception {
        String now = current();
        return new JSONObject().put("pid", Process.myPid()).put("uid", Process.myUid())
                .put("device", remote.getName()).put("deviceId", remote.getId())
                .put("vendor", remote.getVendorId()).put("product", remote.getProductId())
                .put("current", now == null ? JSONObject.NULL : now)
                .put("enabled", new JSONArray(Arrays.asList(enabled())));
    }

    static void write(File file, JSONObject value) throws Exception {
        File temporary = new File(file.getPath() + ".new");
        Files.write(temporary.toPath(), value.toString(2).getBytes(StandardCharsets.UTF_8));
        if (!temporary.renameTo(file)) throw new IllegalStateException("State rename failed");
    }

    static JSONObject read(File file) throws Exception {
        return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    static void restore(JSONObject before) throws Exception {
        // Overlays merge into the live key map: put the original keycodes back before dropping the selection.
        set(REVERSE);
        String previous = before.isNull("current") ? null : before.getString("current");
        JSONArray original = before.getJSONArray("enabled");
        Set<String> keep = new HashSet<>();
        for (int i = 0; i < original.length(); i++) keep.add(original.getString(i));
        if (previous != null) set(previous);
        for (String layout : enabled()) {
            if (!keep.contains(layout)) call("removeKeyboardLayoutForInputDevice", identifier, layout);
        }
        if (!Objects.equals(previous, current()) || !keep.equals(new HashSet<>(Arrays.asList(enabled())))) {
            throw new IllegalStateException("Restore mismatch: " + inspect());
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("Command required");
        init();
        switch (args[0]) {
            case "inspect":
                System.out.println(inspect().toString(2));
                return;
            case "layouts": {
                Object[] layouts = (Object[]) api.getMethod("getKeyboardLayouts").invoke(manager);
                for (Object layout : layouts) {
                    String descriptor = (String) layout.getClass().getMethod("getDescriptor").invoke(layout);
                    if (descriptor.startsWith(PREFIX)) System.out.println(descriptor);
                }
                return;
            }
            case "restore":
                restore(read(new File(args[1])).getJSONObject("before"));
                System.out.println("RESTORED " + inspect());
                return;
            case "trial":
                break;
            default:
                throw new IllegalArgumentException("Unknown command " + args[0]);
        }
        File state = new File(args[1]), commit = new File(args[1] + ".commit"), cancel = new File(args[1] + ".cancel");
        int seconds = Integer.parseInt(args[2]);
        if (seconds < 5 || seconds > 900) throw new IllegalArgumentException("Trial must be 5..900 seconds");
        if (state.exists() || commit.exists() || cancel.exists()) throw new IllegalStateException("Trial state already exists");
        JSONObject before = inspect();
        JSONArray layouts = before.getJSONArray("enabled");
        for (int i = 0; i < layouts.length(); i++) {
            if (layouts.getString(i).startsWith(PREFIX)) throw new IllegalStateException("Already selected; restore first");
        }
        JSONObject status = new JSONObject().put("pid", Process.myPid()).put("before", before)
                .put("seconds", seconds).put("phase", "arming");
        write(state, status);
        boolean committed = false;
        try {
            set(FORWARD);
            long deadline = SystemClock.elapsedRealtime() + seconds * 1000L;
            status.put("phase", "active").put("after", inspect());
            write(state, status);
            while (SystemClock.elapsedRealtime() < deadline && !cancel.exists()) {
                if (commit.exists()) { committed = true; break; }
                SystemClock.sleep(200);
            }
        } finally {
            if (committed) {
                status.put("phase", "committed").put("after", inspect());
                write(state, status);
            } else {
                try {
                    restore(before);
                    status.put("phase", "restored").put("after", inspect());
                    write(state, status);
                } catch (Exception error) {
                    status.put("phase", "restore-failed").put("error", error.toString());
                    write(state, status);
                    throw error;
                }
            }
        }
        System.out.println(status.toString(2));
    }
}

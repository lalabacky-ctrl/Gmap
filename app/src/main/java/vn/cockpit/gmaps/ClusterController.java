package vn.cockpit.gmaps;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * Bộ điều khiển hiển thị trên đồng hồ: nhận bước chỉ đường mới, quyết định khi nào
 * gửi, chạy chữ tên đường, giữ kết nối và tắt hiển thị khi hết dẫn đường.
 * Mọi việc chạy trên luồng chính (giống NaviService của Sunray).
 */
final class ClusterController implements DimBridge.Callback {
    private static final long KEEPALIVE_MS = 5000;
    private static final long MARQUEE_MS = 500;
    private static final long RETRY_MS = 3000;
    private static final long TEST_STEP_MS = 2500;

    private static ClusterController sInstance;

    static synchronized ClusterController get(Context c) {
        if (sInstance == null) sInstance = new ClusterController(c.getApplicationContext());
        return sInstance;
    }

    private final Context app;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final DimBridge dim;
    private final TextUtil.Marquee marquee = new TextUtil.Marquee();

    private NavState nav;          // bước hiện tại từ Google Maps
    private NavState test;         // bước đang gửi thử
    private long testUntil;
    private NavState[] testSteps;
    private int testIndex;

    private boolean started;       // đã báo đồng hồ "đang dẫn đường"
    private boolean notifyAfterInfo; // kiểu C: báo trạng thái sau lần gửi thông tin đầu
    private String lastKey;
    private long lastSendAt;
    private String lastLogged = "";
    private String marqueeFull;
    private int marqueeWindow;
    private String marqueeText = " ";
    private long marqueeAt;

    private volatile String shown = "";
    private volatile boolean mapsNavigating;
    private volatile long lastMapsUpdate;

    private final Runnable tick = new Runnable() {
        @Override public void run() { push(false); }
    };
    private final Runnable testStep = new Runnable() {
        @Override public void run() { advanceTest(); }
    };

    private ClusterController(Context app) {
        this.app = app;
        this.dim = new DimBridge(app, this);
    }

    // ---------------- dữ liệu vào ----------------

    void onMapsUpdate(final NavState s) {
        h.post(new Runnable() {
            @Override public void run() {
                nav = s;
                mapsNavigating = true;
                lastMapsUpdate = System.currentTimeMillis();
                push(false);
            }
        });
    }

    void onMapsEnded() {
        h.post(new Runnable() {
            @Override public void run() {
                if (nav != null) AppLog.i("Google Maps: hết dẫn đường");
                nav = null;
                mapsNavigating = false;
                push(true);
            }
        });
    }

    /** Gọi khi đổi cài đặt. */
    void refresh() {
        h.post(new Runnable() {
            @Override public void run() { push(true); }
        });
    }

    /** Kết nối sẵn tới đồng hồ (gọi khi mở app / khi dịch vụ chạy). */
    void connect() {
        h.post(new Runnable() {
            @Override public void run() {
                logDisplaysOnce();
                dim.ensure();
            }
        });
    }

    private boolean displaysLogged;

    /** Ghi danh sách màn hình đầu máy thấy được (xem đồng hồ có phải là một màn hình Android không). */
    private void logDisplaysOnce() {
        if (displaysLogged) return;
        displaysLogged = true;
        try {
            android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) return;
            StringBuilder sb = new StringBuilder();
            for (android.view.Display d : dm.getDisplays()) {
                android.graphics.Point p = new android.graphics.Point();
                d.getRealSize(p);
                sb.append("#").append(d.getDisplayId()).append(' ').append(d.getName())
                        .append(' ').append(p.x).append('x').append(p.y)
                        .append(" flags=0x").append(Integer.toHexString(d.getFlags())).append("; ");
            }
            AppLog.i("Màn hình: " + sb);
        } catch (Throwable t) {
            AppLog.w("Không liệt kê được màn hình", t);
        }
    }

    /** Xóa hiển thị trên đồng hồ ngay (bước kế tiếp từ Google Maps sẽ hiện lại nếu còn bật). */
    void clearNow() {
        h.post(new Runnable() {
            @Override public void run() {
                test = null;
                testSteps = null;
                testUntil = 0;
                h.removeCallbacks(testStep);
                nav = null;
                stopDim("người dùng bấm xóa");
                shown = "";
            }
        });
    }

    /** Gửi thử một loạt hướng rẽ, mỗi hướng 2,5 giây. */
    void runTest() {
        h.post(new Runnable() {
            @Override public void run() {
                testSteps = new NavState[]{
                        NavState.of(Maneuver.RIGHT, 300, "Nguyễn Huệ"),
                        NavState.of(Maneuver.LEFT, 150, "Lê Lợi"),
                        NavState.of(Maneuver.STRAIGHT, 1200, "Võ Văn Kiệt"),
                        NavState.of(Maneuver.SLIGHT_RIGHT, 500, "Cao tốc TP.HCM - Long Thành"),
                        NavState.of(Maneuver.SLIGHT_LEFT, 450, "Xa lộ Hà Nội"),
                        NavState.of(Maneuver.SHARP_RIGHT, 80, "Hẻm 12"),
                        NavState.of(Maneuver.UTURN_LEFT, 100, "Điện Biên Phủ"),
                        NavState.of(Maneuver.ROUNDABOUT, 200, "Vòng xoay Dân Chủ"),
                        NavState.of(Maneuver.DESTINATION, 30, "Điểm đến"),
                };
                testIndex = -1;
                AppLog.i("Gửi thử: bắt đầu " + testSteps.length + " hướng");
                advanceTest();
            }
        });
    }

    /** Gửi thử một mã hướng rẽ bất kỳ trong 8 giây (để dò mã nào hiện hình gì). */
    /** Gọi một lệnh menu của đồng hồ (chuyển chế độ / tab) và trả kết quả qua callback trên luồng chính. */
    interface Result { void done(String text); }

    void menuCall(final String name, final Result cb, final long... args) {
        h.post(new Runnable() {
            @Override public void run() {
                String r = dim.menuCall(name, args);
                if (cb != null) cb.done(r);
            }
        });
    }

    void sendRawTurnId(final int id) {
        h.post(new Runnable() {
            @Override public void run() {
                testSteps = null;
                h.removeCallbacks(testStep);
                NavState s = NavState.of(Maneuver.UNKNOWN, 250, "Ma " + id);
                s.rawTurnId = id;
                test = s;
                testUntil = SystemClock.uptimeMillis() + 8000;
                h.postDelayed(testStep, 8000);
                AppLog.i("Gửi thử mã hướng rẽ " + id);
                push(true);
            }
        });
    }

    private void advanceTest() {
        h.removeCallbacks(testStep);
        if (testSteps == null || ++testIndex >= testSteps.length) {
            if (test != null) AppLog.i("Gửi thử: xong");
            test = null;
            testSteps = null;
            testUntil = 0;
            push(true);
            return;
        }
        test = testSteps[testIndex];
        testUntil = SystemClock.uptimeMillis() + TEST_STEP_MS + 500;
        h.postDelayed(testStep, TEST_STEP_MS);
        push(true);
    }

    // ---------------- gửi lên đồng hồ ----------------

    private void push(boolean force) {
        h.removeCallbacks(tick);
        boolean testing = test != null && SystemClock.uptimeMillis() < testUntil;
        NavState s = testing ? test : nav;
        boolean enabled = testing || Prefs.enabled(app);

        if (s == null || !enabled) {
            if (started) stopDim(s == null ? "hết dẫn đường" : "đã tắt trong cài đặt");
            shown = "";
            return;
        }
        if (!dim.ensure()) {
            shown = "(chưa kết nối được đồng hồ) " + s.summary();
            if (!dim.isLibraryMissing()) h.postDelayed(tick, RETRY_MS);
            return;
        }
        int mode = Prefs.sendMode(app);
        if (!started) {
            switch (mode) {
                case 1: // B: không báo trạng thái; đặt lại thông tin về 0 để lần gửi 2 được coi là "đổi trạng thái"
                    dim.updateInfo(DimBridge.Info.idle());
                    break;
                case 2: // C: đặt lại hết, gửi thông tin trước rồi mới báo trạng thái
                    dim.notifyStatus(DimBridge.STATUS_IDLE);
                    dim.updateInfo(DimBridge.Info.idle());
                    notifyAfterInfo = true;
                    break;
                case 3: // D: đúng như Sunray, không lệnh riêng
                    dim.notifyStatus(DimBridge.STATUS_NAVIGATING);
                    break;
                default: // A
                    dim.notifyStatus(DimBridge.STATUS_NAVIGATING);
                    dim.turnByTurnStarted();
                    break;
            }
            dim.resetSent();
            started = true;
            force = true;
            AppLog.i("Đồng hồ: bắt đầu hiển thị dẫn đường, kiểu gửi " + Prefs.SEND_MODE_LABELS[mode]);
        }

        String full = "";
        if (Prefs.showName(app) && s.road != null) {
            full = TextUtil.collapseSpaces(s.road);
            if (Prefs.stripDiacritics(app)) full = TextUtil.stripDiacritics(full);
        }
        int window = Prefs.effectiveNameWindow(app);
        long t = SystemClock.uptimeMillis();
        // chỉ dịch chữ mỗi ~0,5 giây, dù Google Maps cập nhật nhanh hơn
        if (!full.equals(marqueeFull) || window != marqueeWindow || t - marqueeAt >= MARQUEE_MS - 50) {
            marqueeText = marquee.next(full, window);
            marqueeFull = full;
            marqueeWindow = window;
            marqueeAt = t;
        }
        String name = marqueeText;
        if (name.trim().isEmpty()) name = " "; // Sunray cũng gửi " " khi không có tên

        long distDest = s.remainDistM >= 0 ? Math.max(s.remainDistM, 1) : 0;
        DimBridge.Info info = new DimBridge.Info(DimBridge.STATUS_NAVIGATING, s.turnId(), name,
                Math.max(s.distM, 0), distDest, Math.max(s.remainSec, 0));
        info.arrow = s.arrow != null ? s.arrow : ArrowArt.get(s.maneuver);

        String key = info.turnId + "|" + info.distNextM + "|" + info.distDestM + "|" + info.etaSec + "|" + name;
        long now = SystemClock.uptimeMillis();
        if (force || !key.equals(lastKey) || now - lastSendAt >= KEEPALIVE_MS) {
            boolean ok = dim.updateInfo(info);
            if (notifyAfterInfo) {
                notifyAfterInfo = false;
                dim.notifyStatus(DimBridge.STATUS_NAVIGATING);
                dim.turnByTurnStarted();
            }
            if (mode != 3) dim.updateIndividual(info);
            dim.updateDriveDirection(0);
            lastKey = key;
            lastSendAt = now;
            String logKey = s.turnId() + "|" + s.road + "|" + ok;
            if (!logKey.equals(lastLogged)) {
                lastLogged = logKey;
                AppLog.i((ok ? "Đã gửi: " : "Gửi LỖI: ") + s.summary() + " → mã " + s.turnId());
            }
            if (!ok) {
                started = false; // lần sau báo lại trạng thái từ đầu
            }
        }
        shown = s.summary();
        boolean scrolling = TextUtil.Marquee.needsScrolling(full, window);
        h.postDelayed(tick, scrolling ? MARQUEE_MS : KEEPALIVE_MS);
    }

    private void stopDim(String reason) {
        h.removeCallbacks(tick);
        if (started && dim.isConnected()) {
            dim.updateInfo(DimBridge.Info.idle());
            if (Prefs.sendMode(app) != 3) dim.updateIndividual(DimBridge.Info.idle());
            dim.turnByTurnStopped();
            dim.notifyStatus(DimBridge.STATUS_IDLE);
            dim.clearAddresses();
            AppLog.i("Đồng hồ: tắt hiển thị (" + reason + ")");
        }
        started = false;
        notifyAfterInfo = false;
        dim.resetSent();
        lastKey = null;
        lastLogged = "";
    }

    /** Đổi kiểu gửi: tắt hiển thị rồi gửi lại từ đầu theo kiểu mới. */
    void restartDim() {
        h.post(new Runnable() {
            @Override public void run() {
                stopDim("đổi kiểu gửi");
                push(true);
            }
        });
    }

    @Override
    public void onConnectionChanged(final boolean connected) {
        h.post(new Runnable() {
            @Override public void run() {
                started = false;
                lastKey = null;
                if (connected) push(true);
            }
        });
    }

    // ---------------- cho màn hình trạng thái ----------------

    boolean isDimConnected() { return dim.isConnected(); }
    boolean isLibraryMissing() { return dim.isLibraryMissing(); }
    String lastError() { return dim.lastError(); }
    String shown() { return shown; }
    boolean isMapsNavigating() { return mapsNavigating; }
    long lastMapsUpdate() { return lastMapsUpdate; }
}

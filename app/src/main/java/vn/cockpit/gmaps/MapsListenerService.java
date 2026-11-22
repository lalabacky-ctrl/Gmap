package vn.cockpit.gmaps;

import android.app.Notification;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Đọc thông báo dẫn đường của Google Maps rồi chuyển cho ClusterController đẩy lên đồng hồ.
 * Hệ thống tự chạy dịch vụ này khi app đã được cấp quyền đọc thông báo.
 */
public class MapsListenerService extends NotificationListenerService {
    static final String MAPS_PKG = "com.google.android.apps.maps";
    /** Các bản Google Maps đã biết: bản gốc và bản mod (Morphe, ReVanced...). */
    static final String[] KNOWN_MAPS_PKGS = {
            MAPS_PKG,
            "app.morphe.android.apps.maps",
            "app.revanced.android.apps.maps",
    };

    /** Gói này có phải Google Maps (bản gốc hoặc bản mod) không. */
    static boolean isMapsPackage(String pkg) {
        if (pkg == null) return false;
        for (String k : KNOWN_MAPS_PKGS) if (k.equals(pkg)) return true;
        // bản mod khác thường giữ đuôi ".apps.maps"
        return pkg.endsWith(".apps.maps");
    }

    static volatile boolean sConnected;
    /** Nội dung thông báo Google Maps gần nhất (để xem trong mục chẩn đoán). */
    static volatile String sLastDump = "";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable checkGone = new Runnable() {
        @Override public void run() { rescan("thông báo cũ bị gỡ"); }
    };
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            rescan(null);
            main.postDelayed(this, 10000);
        }
    };

    @Override
    public void onListenerConnected() {
        sConnected = true;
        AppLog.i("Đã được phép đọc thông báo — bắt đầu theo dõi Google Maps");
        ClusterController.get(this).connect();
        rescan(null);
        main.removeCallbacks(watchdog);
        main.postDelayed(watchdog, 10000);
    }

    @Override
    public void onListenerDisconnected() {
        sConnected = false;
        main.removeCallbacks(watchdog);
        main.removeCallbacks(checkGone);
        AppLog.i("Mất quyền/kết nối đọc thông báo");
        ClusterController.get(this).onMapsEnded();
        ListenerAccess.rebind(this);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !isMapsPackage(sbn.getPackageName())) return;
        try {
            handle(sbn, true);
        } catch (Throwable t) {
            AppLog.w("Lỗi khi đọc thông báo Google Maps", t);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null || !isMapsPackage(sbn.getPackageName())) return;
        // Google Maps hay gỡ thông báo cũ rồi đăng thông báo mới khi đổi bước,
        // nên chờ một chút rồi mới kiểm tra còn dẫn đường không.
        main.removeCallbacks(checkGone);
        main.postDelayed(checkGone, 2500);
    }

    /** Xem lại các thông báo đang hiện: còn thông báo dẫn đường của Maps không. */
    private void rescan(String reason) {
        StatusBarNotification[] all;
        try {
            all = getActiveNotifications();
        } catch (Throwable t) {
            return; // chưa được cấp quyền
        }
        StatusBarNotification latest = null;
        if (all != null) {
            for (StatusBarNotification sbn : all) {
                if (!isMapsPackage(sbn.getPackageName())) continue;
                if (!isNavigation(sbn.getNotification())) continue;
                if (latest == null || sbn.getPostTime() > latest.getPostTime()) latest = sbn;
            }
        }
        if (latest != null) {
            try {
                handle(latest, false);
            } catch (Throwable t) {
                AppLog.w("Lỗi khi đọc thông báo Google Maps", t);
            }
        } else {
            ClusterController c = ClusterController.get(this);
            if (c.isMapsNavigating() && reason != null) AppLog.i("Không còn thông báo dẫn đường (" + reason + ")");
            c.onMapsEnded();
        }
    }

    private static NotificationParser.Input readTexts(Notification n) {
        Bundle ex = n.extras;
        NotificationParser.Input in = new NotificationParser.Input();
        if (ex == null) return in;
        in.title = str(ex.getCharSequence(Notification.EXTRA_TITLE));
        if (in.title.isEmpty()) in.title = str(ex.getCharSequence(Notification.EXTRA_TITLE_BIG));
        in.text = str(ex.getCharSequence(Notification.EXTRA_TEXT));
        in.subText = str(ex.getCharSequence(Notification.EXTRA_SUB_TEXT));
        in.bigText = str(ex.getCharSequence(Notification.EXTRA_BIG_TEXT));
        in.infoText = str(ex.getCharSequence(Notification.EXTRA_INFO_TEXT));
        in.summaryText = str(ex.getCharSequence(Notification.EXTRA_SUMMARY_TEXT));
        CharSequence[] lines = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines != null) {
            StringBuilder sb = new StringBuilder();
            for (CharSequence l : lines) {
                if (l == null) continue;
                if (sb.length() > 0) sb.append(" · ");
                sb.append(l);
            }
            in.textLines = sb.toString();
        }
        in.ticker = str(n.tickerText);
        return in;
    }

    /** Thông báo này có phải thông báo dẫn đường không. */
    static boolean isNavigation(Notification n) {
        if (n == null) return false;
        if (Notification.CATEGORY_NAVIGATION.equals(n.category)) return true;
        if ((n.flags & Notification.FLAG_ONGOING_EVENT) == 0) return false;
        NotificationParser.Input in = readTexts(n);
        return NotificationParser.parseDistanceMeters(in.title) >= 0
                || NotificationParser.parseDistanceMeters(in.text) >= 0;
    }

    private void handle(StatusBarNotification sbn, boolean fresh) {
        Notification n = sbn.getNotification();
        if (n == null) return;
        NotificationParser.Input in = readTexts(n);
        boolean nav = isNavigation(n);
        if (fresh) sLastDump = dump(sbn, in, nav);
        if (!nav) return;

        NotificationParser.Result r = NotificationParser.parse(in);

        Bitmap bmp = null;
        String iconFrom = "";
        try {
            bmp = toBitmap(n.getLargeIcon());
            iconFrom = "ảnh lớn";
            if (bmp == null && n.extras != null) {
                Parcelable p = n.extras.getParcelable(Notification.EXTRA_LARGE_ICON);
                if (p instanceof Bitmap) bmp = argb((Bitmap) p);
                else if (p instanceof Icon) bmp = toBitmap((Icon) p);
            }
            if (bmp == null) {
                bmp = toBitmap(n.getSmallIcon());
                iconFrom = "biểu tượng nhỏ";
            }
        } catch (Throwable t) {
            AppLog.w("Không đọc được hình mũi tên", t);
        }

        IconClassifier.Result ic = null;
        if (bmp != null) {
            int w = bmp.getWidth(), h = bmp.getHeight();
            int[] px = new int[w * h];
            bmp.getPixels(px, 0, w, 0, 0, w, h);
            ic = IconClassifier.classify(w, h, px);
        }

        IconStore store = IconStore.get(this);
        Maneuver m = Maneuver.UNKNOWN;
        String src = "không rõ";
        Maneuver learned = ic != null ? store.lookup(ic.signature) : null;
        if (learned != null) {
            m = learned;
            src = "đã dạy";
        } else if (r.keywordManeuver != Maneuver.UNKNOWN) {
            m = r.keywordManeuver;
            src = "chữ";
        } else if (ic != null && ic.maneuver != Maneuver.UNKNOWN && Prefs.useHeuristic(this)) {
            m = ic.maneuver;
            src = "đoán hình";
        }
        if (ic != null && ic.signature != null) {
            store.recordSeen(ic.signature, bmp, ic.maneuver, r.road);
        }
        if (fresh && ic != null) {
            sLastDump = sLastDump + "\nHình mũi tên (" + iconFrom + "): đoán = " + ic.maneuver.label
                    + " [" + ic.debug + "]\n" + IconClassifier.asciiArt(ic.signature);
        }

        NavState s = new NavState();
        s.maneuver = m;
        s.source = src;
        s.distM = r.distanceM;
        s.remainDistM = r.remainDistM;
        s.remainSec = r.remainSec;
        s.road = r.road;
        ClusterController.get(this).onMapsUpdate(s);
    }

    private Bitmap toBitmap(Icon icon) {
        if (icon == null) return null;
        Drawable d = icon.loadDrawable(this);
        return toBitmap(d);
    }

    /** Đổi về dạng ARGB_8888 để đọc được từng điểm ảnh (ảnh HARDWARE không đọc được). */
    static Bitmap argb(Bitmap b) {
        if (b == null) return null;
        if (b.getConfig() == Bitmap.Config.ARGB_8888) return b;
        return b.copy(Bitmap.Config.ARGB_8888, false);
    }

    static Bitmap toBitmap(Drawable d) {
        if (d == null) return null;
        if (d instanceof BitmapDrawable) {
            Bitmap b = ((BitmapDrawable) d).getBitmap();
            if (b != null) return argb(b);
        }
        int w = d.getIntrinsicWidth(), h = d.getIntrinsicHeight();
        if (w <= 0 || h <= 0) {
            w = 96;
            h = 96;
        }
        w = Math.max(24, Math.min(192, w));
        h = Math.max(24, Math.min(192, h));
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        return b;
    }

    private static String str(CharSequence cs) {
        return cs == null ? "" : cs.toString();
    }

    /** Ghi lại toàn bộ nội dung thông báo để chẩn đoán khi đọc sai. */
    private static String dump(StatusBarNotification sbn, NotificationParser.Input in, boolean nav) {
        Notification n = sbn.getNotification();
        StringBuilder sb = new StringBuilder();
        sb.append("Gói: ").append(sbn.getPackageName()).append("  id=").append(sbn.getId())
                .append("  dẫn đường=").append(nav ? "CÓ" : "không").append('\n');
        sb.append("category=").append(n.category).append("  flags=0x").append(Integer.toHexString(n.flags));
        if (Build.VERSION.SDK_INT >= 26) sb.append("  kênh=").append(n.getChannelId());
        sb.append('\n');
        Bundle ex = n.extras;
        if (ex != null) {
            for (String k : ex.keySet()) {
                Object v;
                try {
                    v = ex.get(k);
                } catch (Throwable t) {
                    v = "(không đọc được)";
                }
                String s = v == null ? "null" : v.toString();
                if (v instanceof CharSequence[]) {
                    StringBuilder a = new StringBuilder("[");
                    for (CharSequence c : (CharSequence[]) v) a.append(c).append(" | ");
                    s = a.append("]").toString();
                }
                if (s.length() > 200) s = s.substring(0, 200) + "…";
                sb.append("  ").append(k).append(" = ").append(s).append('\n');
            }
        }
        sb.append("Đọc được: ").append(NotificationParser.parse(in)).append('\n');
        return sb.toString();
    }
}

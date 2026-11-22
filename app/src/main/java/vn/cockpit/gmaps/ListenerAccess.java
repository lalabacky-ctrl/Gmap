package vn.cockpit.gmaps;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;

/** Kiểm tra và xin quyền đọc thông báo (cần để đọc chỉ đường của Google Maps). */
final class ListenerAccess {
    private static final String SECURE_KEY = "enabled_notification_listeners";

    private ListenerAccess() {}

    static ComponentName component(Context c) {
        return new ComponentName(c, MapsListenerService.class);
    }

    static boolean isSystemUid() {
        return Process.myUid() == Process.SYSTEM_UID;
    }

    static boolean isGranted(Context c) {
        ComponentName cn = component(c);
        if (Build.VERSION.SDK_INT >= 27) {
            try {
                NotificationManager nm = c.getSystemService(NotificationManager.class);
                if (nm != null) return nm.isNotificationListenerAccessGranted(cn);
            } catch (Throwable ignored) {
                // thử cách đọc cài đặt bên dưới
            }
        }
        String flat = Settings.Secure.getString(c.getContentResolver(), SECURE_KEY);
        return flat != null && (flat.contains(cn.flattenToString()) || flat.contains(cn.flattenToShortString()));
    }

    /**
     * Tự cấp quyền khi app chạy dưới quyền hệ thống (bản "hệ thống" ký bằng khóa platform),
     * hoặc khi đã được cấp WRITE_SECURE_SETTINGS qua adb. Trả về true nếu thành công.
     */
    static boolean trySelfGrant(Context c) {
        ComponentName cn = component(c);
        // Cách 1: lệnh ẩn của hệ thống (chỉ app hệ thống gọi được).
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            NotificationManager.class
                    .getMethod("setNotificationListenerAccessGranted", ComponentName.class, boolean.class)
                    .invoke(nm, cn, true);
            AppLog.i("Đã tự cấp quyền đọc thông báo (lệnh hệ thống)");
            rebind(c);
            if (isGranted(c)) return true;
        } catch (Throwable t) {
            AppLog.i("Không tự cấp quyền bằng lệnh hệ thống được: " + DimBridge.describe(t));
        }
        // Cách 2: ghi thẳng vào cài đặt bảo mật (cần WRITE_SECURE_SETTINGS).
        try {
            String flat = Settings.Secure.getString(c.getContentResolver(), SECURE_KEY);
            String me = cn.flattenToString();
            String v = (flat == null || flat.isEmpty()) ? me : flat + ":" + me;
            if (Settings.Secure.putString(c.getContentResolver(), SECURE_KEY, v)) {
                AppLog.i("Đã ghi quyền đọc thông báo vào cài đặt bảo mật");
                rebind(c);
                return isGranted(c);
            }
        } catch (Throwable t) {
            AppLog.i("Không ghi được cài đặt bảo mật: " + DimBridge.describe(t));
        }
        return false;
    }

    static void rebind(Context c) {
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                NotificationListenerService.requestRebind(component(c));
            } catch (Throwable ignored) {
                // không sao
            }
        }
    }

    /** Lệnh adb để cấp quyền từ máy tính nếu đầu máy giấu màn hình cài đặt. */
    static String adbCommand(Context c) {
        return "adb shell cmd notification allow_listener " + component(c).flattenToString();
    }
}

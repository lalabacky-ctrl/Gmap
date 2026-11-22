package vn.cockpit.gmaps;

import android.content.Context;
import android.content.SharedPreferences;

/** Các công tắc cài đặt của app. */
public final class Prefs {
    /** Bật/tắt đẩy chỉ đường lên đồng hồ (giống navi_clusteroutput_v2_enabled của Sunray). */
    public static final String ENABLED = "day_len_dong_ho";
    /** Hiện tên đường (giống navi_clusteraddress_enabled của Sunray). */
    public static final String SHOW_NAME = "hien_ten_duong";
    public static final String STRIP_DIACRITICS = "bo_dau";
    public static final String USE_HEURISTIC = "tu_doan_huong";
    /** Số ký tự tên đường: 0 = tự động theo đời đầu máy, -1 = không giới hạn. */
    public static final String NAME_WINDOW = "so_ky_tu";

    public static final int[] WINDOW_CHOICES = {0, 6, 10, 13, 20, -1};

    /** Kiểu gửi lên đồng hồ (thử nghiệm cho đầu máy đời mới như IHU689G). */
    public static final String SEND_MODE = "kieu_gui";
    public static final String[] SEND_MODE_LABELS = {
            "A: như Sunray + lệnh riêng",
            "B: không báo trạng thái, đặt lại thông tin 0 rồi 2",
            "C: gửi thông tin trước, báo trạng thái sau",
            "D: như Sunray, tối giản",
    };

    private Prefs() {}

    public static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("cai_dat", Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context c) { return sp(c).getBoolean(ENABLED, true); }
    public static boolean showName(Context c) { return sp(c).getBoolean(SHOW_NAME, true); }
    public static boolean stripDiacritics(Context c) { return sp(c).getBoolean(STRIP_DIACRITICS, true); }
    public static boolean useHeuristic(Context c) { return sp(c).getBoolean(USE_HEURISTIC, true); }
    public static int nameWindowSetting(Context c) { return sp(c).getInt(NAME_WINDOW, 0); }
    public static int sendMode(Context c) {
        int m = sp(c).getInt(SEND_MODE, 0);
        return (m < 0 || m >= SEND_MODE_LABELS.length) ? 0 : m;
    }

    public static void put(Context c, String key, boolean v) { sp(c).edit().putBoolean(key, v).apply(); }
    public static void put(Context c, String key, int v) { sp(c).edit().putInt(key, v).apply(); }

    /**
     * Số ký tự tên đường đồng hồ hiện được. Sunray dùng 13 ký tự cho đầu máy
     * IHU628G / IHU634G và 6 ký tự cho các đời khác (IHU623G, IHU624G).
     */
    public static int effectiveNameWindow(Context c) {
        int s = nameWindowSetting(c);
        if (s != 0) return s;
        return autoWindow();
    }

    private static volatile String sModel;

    /** Đời đầu máy, ví dụ IHU624G (đọc một lần rồi nhớ). */
    public static String headUnitModel() {
        String m = sModel;
        if (m == null) {
            m = SystemProps.get("ro.product.vendor.model", "");
            sModel = m;
        }
        return m;
    }

    public static int autoWindow() {
        String model = headUnitModel();
        return ("IHU628G".equals(model) || "IHU634G".equals(model)) ? 13 : 6;
    }

    public static String windowLabel(int s) {
        if (s == 0) return "Tự động (" + autoWindow() + " ký tự)";
        if (s < 0) return "Không giới hạn";
        return s + " ký tự";
    }
}

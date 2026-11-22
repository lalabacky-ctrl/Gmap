package vn.cockpit.gmaps;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/** Đọc thuộc tính hệ thống (ví dụ ro.product.vendor.model = IHU624G). */
public final class SystemProps {
    private SystemProps() {}

    public static String get(String key, String def) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Object v = c.getMethod("get", String.class, String.class).invoke(null, key, def);
            if (v instanceof String && !((String) v).isEmpty()) return (String) v;
        } catch (Throwable ignored) {
            // thử cách khác bên dưới
        }
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"getprop", key});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            r.close();
            if (line != null && !line.trim().isEmpty()) return line.trim();
        } catch (Throwable ignored) {
            // bỏ qua
        }
        return def;
    }
}

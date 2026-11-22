package vn.cockpit.gmaps;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** Nhật ký trong app (giữ 400 dòng gần nhất) để xem và gửi khi cần sửa lỗi. */
public final class AppLog {
    private static final String TAG = "GMapsDongHo";
    private static final int MAX = 400;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);

    private AppLog() {}

    public static void i(String msg) {
        Log.i(TAG, msg);
        add("I", msg);
    }

    public static void w(String msg, Throwable t) {
        Log.w(TAG, msg, t);
        add("W", t == null ? msg : msg + " — " + t.getClass().getSimpleName() + ": " + t.getMessage());
    }

    private static synchronized void add(String level, String msg) {
        String time;
        synchronized (FMT) {
            time = FMT.format(new Date());
        }
        LINES.addLast(time + " " + level + " " + msg);
        while (LINES.size() > MAX) LINES.removeFirst();
    }

    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String s : LINES) sb.append(s).append('\n');
        return sb.toString();
    }

    public static synchronized void clear() {
        LINES.clear();
    }
}

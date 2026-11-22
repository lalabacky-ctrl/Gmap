package vn.cockpit.gmaps;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ghi nhớ hình mũi tên:
 *  - "đã dạy": người dùng chọn hướng cho một hình -> lần sau gặp lại hình đó dùng đúng hướng ấy;
 *  - "đã gặp": danh sách hình mới gặp gần đây (kèm ảnh nhỏ) để người dùng dạy.
 */
final class IconStore {
    /** Hai hình lệch nhau tối đa bấy nhiêu điểm (trên 1024) vẫn coi là một. */
    private static final int MATCH_BITS = 40;
    private static final int MAX_SEEN = 60;

    static final class Seen {
        String sig;
        int count;
        Maneuver guess = Maneuver.UNKNOWN;
        String road = "";
        long lastSeen;
        File png;
    }

    private static IconStore sInstance;

    static synchronized IconStore get(Context c) {
        if (sInstance == null) sInstance = new IconStore(c.getApplicationContext());
        return sInstance;
    }

    private final SharedPreferences learned;
    private final File dir;
    private final Map<String, Seen> seen = new HashMap<>();
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable saveIndex = new Runnable() {
        @Override public void run() { writeIndex(); }
    };

    private IconStore(Context c) {
        learned = c.getSharedPreferences("bieu_tuong_da_day", Context.MODE_PRIVATE);
        dir = new File(c.getFilesDir(), "bieu_tuong");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        readIndex();
    }

    // ---------------- đã dạy ----------------

    /** Hướng đã dạy cho hình này (hoặc hình rất giống); null nếu chưa dạy. */
    synchronized Maneuver lookup(String sig) {
        if (sig == null) return null;
        String exact = learned.getString(sig, null);
        if (exact != null) return Maneuver.fromName(exact);
        int best = Integer.MAX_VALUE;
        String bestVal = null;
        for (Map.Entry<String, ?> e : learned.getAll().entrySet()) {
            int d = IconClassifier.hamming(sig, e.getKey());
            if (d < best) {
                best = d;
                bestVal = String.valueOf(e.getValue());
            }
        }
        return best <= MATCH_BITS ? Maneuver.fromName(bestVal) : null;
    }

    synchronized Maneuver learnedExact(String sig) {
        String v = learned.getString(sig, null);
        return v == null ? null : Maneuver.fromName(v);
    }

    synchronized void learn(String sig, Maneuver m) {
        learned.edit().putString(sig, m.name()).apply();
        AppLog.i("Đã dạy: hình " + shortId(sig) + " = " + m.label);
    }

    synchronized void forget(String sig) {
        learned.edit().remove(sig).apply();
    }

    synchronized void clearLearned() {
        learned.edit().clear().apply();
    }

    synchronized int learnedCount() {
        return learned.getAll().size();
    }

    // ---------------- đã gặp ----------------

    synchronized void recordSeen(String sig, Bitmap bmp, Maneuver guess, String road) {
        if (sig == null) return;
        Seen s = seen.get(sig);
        boolean isNew = s == null;
        if (isNew) {
            s = new Seen();
            s.sig = sig;
            s.png = new File(dir, shortId(sig) + ".png");
            seen.put(sig, s);
            if (bmp != null) savePng(bmp, s.png);
            trim();
        }
        s.count++;
        s.guess = guess;
        if (road != null && !road.isEmpty()) s.road = road;
        s.lastSeen = System.currentTimeMillis();
        h.removeCallbacks(saveIndex);
        h.postDelayed(saveIndex, isNew ? 500 : 10000);
    }

    synchronized List<Seen> listSeen() {
        List<Seen> out = new ArrayList<>(seen.values());
        Collections.sort(out, new Comparator<Seen>() {
            @Override public int compare(Seen a, Seen b) { return Long.compare(b.lastSeen, a.lastSeen); }
        });
        return out;
    }

    synchronized void clearSeen() {
        for (Seen s : seen.values()) {
            //noinspection ResultOfMethodCallIgnored
            s.png.delete();
        }
        seen.clear();
        writeIndex();
    }

    private void trim() {
        while (seen.size() > MAX_SEEN) {
            Seen oldest = null;
            for (Seen s : seen.values()) {
                if (learned.contains(s.sig)) continue; // giữ lại hình đã dạy
                if (oldest == null || s.lastSeen < oldest.lastSeen) oldest = s;
            }
            if (oldest == null) break;
            //noinspection ResultOfMethodCallIgnored
            oldest.png.delete();
            seen.remove(oldest.sig);
        }
    }

    private static void savePng(Bitmap bmp, File f) {
        try {
            Bitmap small = Bitmap.createScaledBitmap(bmp, 64, 64, true);
            FileOutputStream out = new FileOutputStream(f);
            small.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.close();
        } catch (Throwable t) {
            AppLog.w("Không lưu được ảnh biểu tượng", t);
        }
    }

    private synchronized void writeIndex() {
        try {
            FileWriter w = new FileWriter(new File(dir, "danh_sach.txt"));
            for (Seen s : seen.values()) {
                w.write(s.sig + "\t" + s.count + "\t" + s.guess.name() + "\t" + s.lastSeen + "\t"
                        + s.road.replace('\t', ' ').replace('\n', ' ') + "\n");
            }
            w.close();
        } catch (Throwable t) {
            AppLog.w("Không lưu được danh sách biểu tượng", t);
        }
    }

    private void readIndex() {
        File f = new File(dir, "danh_sach.txt");
        if (!f.exists()) return;
        try {
            BufferedReader r = new BufferedReader(new FileReader(f));
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t", -1);
                if (p.length < 5) continue;
                Seen s = new Seen();
                s.sig = p[0];
                s.count = Integer.parseInt(p[1]);
                s.guess = Maneuver.fromName(p[2]);
                s.lastSeen = Long.parseLong(p[3]);
                s.road = p[4];
                s.png = new File(dir, shortId(s.sig) + ".png");
                seen.put(s.sig, s);
            }
            r.close();
        } catch (Throwable t) {
            AppLog.w("Không đọc được danh sách biểu tượng", t);
        }
    }

    static String shortId(String sig) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(sig.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(sig.hashCode());
        }
    }
}

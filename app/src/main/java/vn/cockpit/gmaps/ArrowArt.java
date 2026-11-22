package vn.cockpit.gmaps;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

import java.util.EnumMap;
import java.util.Map;

/** Vẽ hình mũi tên đơn giản cho từng hướng rẽ (dùng khi gửi thử, hoặc khi không có hình của Google Maps). */
final class ArrowArt {
    private static final Map<Maneuver, Bitmap> CACHE = new EnumMap<>(Maneuver.class);

    private ArrowArt() {}

    static synchronized Bitmap get(Maneuver m) {
        Bitmap b = CACHE.get(m);
        if (b == null) {
            b = draw(glyph(m));
            CACHE.put(m, b);
        }
        return b;
    }

    private static String glyph(Maneuver m) {
        switch (m) {
            case LEFT: return "↰";          // ↰
            case RIGHT: return "↱";         // ↱
            case SLIGHT_LEFT: return "↖";   // ↖
            case SLIGHT_RIGHT: return "↗";  // ↗
            case SHARP_LEFT: return "↙";    // ↙
            case SHARP_RIGHT: return "↘";   // ↘
            case UTURN_LEFT: return "↶";    // ↶
            case UTURN_RIGHT: return "↷";   // ↷
            case ROUNDABOUT: return "⟳";    // ⟳
            case DESTINATION: return "⚑";   // ⚑
            default: return "↑";            // ↑
        }
    }

    private static Bitmap draw(String g) {
        int size = 128;
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setTextSize(size * 0.9f);
        p.setTextAlign(Paint.Align.CENTER);
        Rect r = new Rect();
        p.getTextBounds(g, 0, g.length(), r);
        c.drawText(g, size / 2f, size / 2f - r.exactCenterY(), p);
        return b;
    }
}

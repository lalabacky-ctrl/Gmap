package vn.cockpit.gmaps;

/** Một bước chỉ đường đã đọc được (từ Google Maps hoặc từ nút gửi thử). */
final class NavState {
    Maneuver maneuver = Maneuver.UNKNOWN;
    /** Hướng rẽ lấy từ đâu: "đã dạy", "chữ", "đoán hình", "thử"... */
    String source = "";
    int distM = -1;
    int remainDistM = -1;
    int remainSec = -1;
    String road = "";
    /** Hình mũi tên của Google Maps (có thể null). */
    android.graphics.Bitmap arrow;
    /** Dùng khi gửi thử một mã hướng rẽ bất kỳ. */
    Integer rawTurnId;

    int turnId() {
        return rawTurnId != null ? rawTurnId : maneuver.ecarxId;
    }

    String summary() {
        StringBuilder sb = new StringBuilder();
        if (rawTurnId != null) sb.append("Mã ").append(rawTurnId);
        else sb.append(maneuver.label);
        if (distM >= 0) sb.append(" · ").append(formatDistance(distM));
        if (road != null && !road.isEmpty()) sb.append(" · ").append(road);
        if (source != null && !source.isEmpty()) sb.append(" (").append(source).append(")");
        return sb.toString();
    }

    static String formatDistance(int m) {
        if (m < 1000) return m + " m";
        return String.format(java.util.Locale.ROOT, "%.1f km", m / 1000.0).replace('.', ',');
    }

    static NavState of(Maneuver m, int distM, String road) {
        NavState s = new NavState();
        s.maneuver = m;
        s.distM = distM;
        s.road = road;
        s.source = "thử";
        s.remainDistM = 5400;
        s.remainSec = 780;
        return s;
    }
}

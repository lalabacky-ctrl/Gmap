package vn.cockpit.gmaps;

import java.util.Calendar;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Đọc chữ trong thông báo dẫn đường của Google Maps.
 *
 * Thông báo của Google Maps thường có:
 *  - tiêu đề (title): khoảng cách tới chỗ rẽ, ví dụ "300 m", "1,2 km";
 *  - nội dung (text): tên đường, nhiều tên ngăn bởi " / ";
 *  - hướng rẽ chỉ nằm trong hình mũi tên (xem IconClassifier).
 * Một số bản Google Maps ghi cả câu chỉ dẫn ("Rẽ phải vào ..."), nên có thêm
 * phần dò từ khóa tiếng Việt / tiếng Anh.
 * Lớp này chỉ dùng Java thuần để có thể kiểm thử trên máy tính.
 */
public final class NotificationParser {

    public static final class Input {
        public String title, text, subText, bigText, infoText, summaryText, textLines, ticker;
    }

    public static final class Result {
        /** Khoảng cách tới chỗ rẽ (mét), -1 nếu không đọc được. */
        public int distanceM = -1;
        /** Tên đường sắp tới ("" nếu không có). */
        public String road = "";
        /** Hướng rẽ đọc được từ chữ, UNKNOWN nếu chữ không nói. */
        public Maneuver keywordManeuver = Maneuver.UNKNOWN;
        /** Quãng đường còn lại tới đích (mét), -1 nếu không có. */
        public int remainDistM = -1;
        /** Thời gian còn lại (giây), -1 nếu không có. */
        public int remainSec = -1;

        @Override public String toString() {
            return "kc=" + distanceM + "m, đường='" + road + "', chữ=" + keywordManeuver
                    + ", còn=" + remainDistM + "m/" + remainSec + "s";
        }
    }

    // Số kèm đơn vị: "300 m", "1,2 km", "0.5 mi", "500 ft", "2 dặm".
    private static final Pattern DIST = Pattern.compile(
            "(\\d+(?:[.,]\\d+)*)\\s*(km|m|mi|dặm|ft|yd)(?![\\p{L}])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern HOURS = Pattern.compile(
            "(\\d+)\\s*(giờ|tiếng|h|hr|hrs|hour|hours)(?![\\p{L}])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern MINUTES = Pattern.compile(
            "(\\d+)\\s*(phút|ph|min|mins|minute|minutes)(?![\\p{L}])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern CLOCK = Pattern.compile(
            "(?<![\\d:])(\\d{1,2}):(\\d{2})(?![\\d:])\\s*(SA|CH|AM|PM|sáng|chiều|tối)?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Các cụm đứng trước tên đường trong câu chỉ dẫn. */
    private static final String[] ROAD_MARKERS = {
            " vào đường ", " vào ", " về phía ", " hướng về ", " theo hướng ", " đi theo ",
            " trên đường ", " trên ", " onto ", " towards ", " toward ", " on ", " to "
    };
    private static final String[] LEADING_WORDS = {
            "về phía ", "hướng về ", "theo hướng ", "vào ", "trên ", "toward ", "towards ",
            "onto ", "on ", "to "
    };

    /**
     * Từ khóa -> hướng rẽ. Thứ tự quan trọng: cụm cụ thể (rẽ gắt, chếch, giữ làn,
     * quay đầu) phải đứng trước cụm chung (rẽ trái, rẽ phải).
     */
    private static final Object[][] KEYWORDS = {
            {Maneuver.DESTINATION, new String[]{"đến nơi", "điểm đến", "đích đến", "đã tới", "arrive", "destination"}},
            {Maneuver.FERRY, new String[]{"lên phà", "đi phà", "ferry"}},
            {Maneuver.ROUNDABOUT, new String[]{"vòng xuyến", "bùng binh", "vòng tròn", "roundabout", "traffic circle", "rotary"}},
            {Maneuver.UTURN_RIGHT, new String[]{"quay đầu bên phải", "quay đầu sang phải", "u-turn right", "uturn right"}},
            {Maneuver.UTURN_LEFT, new String[]{"quay đầu", "quay lại", "u-turn", "uturn", "make a u"}},
            {Maneuver.SHARP_LEFT, new String[]{"rẽ gắt trái", "rẽ gắt sang trái", "ngoặt trái", "quẹo gắt trái", "sharp left"}},
            {Maneuver.SHARP_RIGHT, new String[]{"rẽ gắt phải", "rẽ gắt sang phải", "ngoặt phải", "quẹo gắt phải", "sharp right"}},
            {Maneuver.SLIGHT_LEFT, new String[]{"chếch sang trái", "chếch trái", "rẽ nhẹ sang trái", "hơi rẽ trái",
                    "giữ bên trái", "giữ làn trái", "đi về bên trái", "đi bên trái", "đi theo làn bên trái",
                    "lối ra bên trái", "nhập làn bên trái", "slight left", "keep left", "bear left", "exit left", "fork left"}},
            {Maneuver.SLIGHT_RIGHT, new String[]{"chếch sang phải", "chếch phải", "rẽ nhẹ sang phải", "hơi rẽ phải",
                    "giữ bên phải", "giữ làn phải", "đi về bên phải", "đi bên phải", "đi theo làn bên phải",
                    "lối ra bên phải", "nhập làn bên phải", "slight right", "keep right", "bear right", "exit right", "fork right"}},
            {Maneuver.LEFT, new String[]{"rẽ trái", "rẽ sang trái", "quẹo trái", "turn left"}},
            {Maneuver.RIGHT, new String[]{"rẽ phải", "rẽ sang phải", "quẹo phải", "turn right"}},
            {Maneuver.STRAIGHT, new String[]{"đi thẳng", "tiếp tục đi", "chạy thẳng", "continue", "go straight", "head "}},
    };

    private NotificationParser() {}

    public static Result parse(Input in) {
        Result r = new Result();
        String title = TextUtil.collapseSpaces(in.title);
        String text = TextUtil.collapseSpaces(in.text);
        String big = TextUtil.collapseSpaces(in.bigText);

        // 1. Khoảng cách tới chỗ rẽ: ưu tiên tiêu đề, rồi nội dung.
        String stepField = null;
        int d = parseDistanceMeters(title);
        if (d >= 0) {
            stepField = title;
        } else {
            d = parseDistanceMeters(text);
            if (d >= 0) stepField = text;
        }
        r.distanceM = d;

        // 2. Tên đường: lấy ở trường không chứa khoảng cách.
        String roadSrc;
        if (stepField == title) {
            roadSrc = !text.isEmpty() ? text : removeDistance(title);
        } else if (stepField == text) {
            roadSrc = !title.isEmpty() ? title : removeDistance(text);
        } else {
            roadSrc = !text.isEmpty() ? text : title;
        }
        if (roadSrc.isEmpty() && !big.isEmpty()) roadSrc = big;
        r.road = extractRoad(roadSrc);

        // 3. Hướng rẽ từ chữ (nếu Google Maps có ghi).
        r.keywordManeuver = detectKeyword(title + " | " + text + " | " + big + " | "
                + TextUtil.collapseSpaces(in.ticker));

        // 4. Quãng đường và thời gian còn lại: nằm ở các dòng phụ.
        String trip = TextUtil.collapseSpaces(join(in.subText, in.infoText, in.summaryText, in.textLines));
        if (!trip.isEmpty()) {
            r.remainDistM = parseDistanceMeters(trip);
            r.remainSec = parseDurationSeconds(trip);
            if (r.remainSec < 0) r.remainSec = secondsUntilClock(trip);
        }
        return r;
    }

    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(p);
        }
        return sb.toString();
    }

    /** Khoảng cách đầu tiên trong chuỗi, đổi ra mét; -1 nếu không có. */
    public static int parseDistanceMeters(String s) {
        if (s == null || s.isEmpty()) return -1;
        Matcher m = DIST.matcher(s);
        if (!m.find()) return -1;
        String unit = m.group(2).toLowerCase(Locale.ROOT);
        double v = parseNumber(m.group(1), unit);
        if (v < 0) return -1;
        double meters;
        switch (unit) {
            case "km": meters = v * 1000.0; break;
            case "mi":
            case "dặm": meters = v * 1609.344; break;
            case "ft": meters = v * 0.3048; break;
            case "yd": meters = v * 0.9144; break;
            default: meters = v; break;
        }
        return (int) Math.round(meters);
    }

    /** "1,2" -> 1.2 ; "1.200" (m) -> 1200 ; "0.5" -> 0.5 */
    static double parseNumber(String num, String unit) {
        try {
            int lastSep = Math.max(num.lastIndexOf('.'), num.lastIndexOf(','));
            if (lastSep < 0) return Double.parseDouble(num);
            String intPart = num.substring(0, lastSep).replaceAll("[.,]", "");
            String frac = num.substring(lastSep + 1);
            boolean smallUnit = unit.equals("m") || unit.equals("ft") || unit.equals("yd");
            if (frac.length() == 3 && smallUnit) {
                return Double.parseDouble(intPart + frac); // dấu phân cách hàng nghìn
            }
            if (intPart.isEmpty()) intPart = "0";
            return Double.parseDouble(intPart + "." + frac);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** "1 giờ 5 phút" -> 3900 ; "15 min" -> 900 ; -1 nếu không có. */
    public static int parseDurationSeconds(String s) {
        if (s == null) return -1;
        int total = -1;
        Matcher h = HOURS.matcher(s);
        if (h.find()) total = Integer.parseInt(h.group(1)) * 3600;
        Matcher m = MINUTES.matcher(s);
        if (m.find()) total = Math.max(total, 0) + Integer.parseInt(m.group(1)) * 60;
        return total;
    }

    /** "Đến lúc 10:45" -> số giây từ bây giờ tới 10:45; -1 nếu không có. */
    public static int secondsUntilClock(String s) {
        Matcher m = CLOCK.matcher(s);
        if (!m.find()) return -1;
        int hh = Integer.parseInt(m.group(1));
        int mm = Integer.parseInt(m.group(2));
        String ap = m.group(3) == null ? "" : m.group(3).toLowerCase(Locale.ROOT);
        if ((ap.equals("ch") || ap.equals("pm") || ap.equals("chiều") || ap.equals("tối")) && hh < 12) hh += 12;
        if ((ap.equals("sa") || ap.equals("am") || ap.equals("sáng")) && hh == 12) hh = 0;
        if (hh > 23 || mm > 59) return -1;
        Calendar now = Calendar.getInstance();
        Calendar t = (Calendar) now.clone();
        t.set(Calendar.HOUR_OF_DAY, hh);
        t.set(Calendar.MINUTE, mm);
        t.set(Calendar.SECOND, 0);
        t.set(Calendar.MILLISECOND, 0);
        long diff = t.getTimeInMillis() - now.getTimeInMillis();
        if (diff < 0) diff += 24L * 3600 * 1000;
        return (int) (diff / 1000);
    }

    static String removeDistance(String s) {
        if (s == null) return "";
        return TextUtil.collapseSpaces(DIST.matcher(s).replaceAll(" ")).replaceAll("^[\\s·•,\\-–]+|[\\s·•,\\-–]+$", "");
    }

    /** Lấy tên đường từ chuỗi kiểu "Rẽ phải vào Nguyễn Huệ / QL1A". */
    static String extractRoad(String s) {
        if (s == null) return "";
        String r = removeDistance(s);
        int slash = r.indexOf(" / ");
        if (slash > 0) r = r.substring(0, slash);
        String lower = " " + r.toLowerCase(Locale.ROOT) + " ";
        int best = -1, bestLen = 0;
        for (String mk : ROAD_MARKERS) {
            int i = lower.lastIndexOf(mk);
            if (i > best) {
                best = i;
                bestLen = mk.length();
            }
        }
        if (best >= 0) {
            // lower có thêm 1 dấu cách ở đầu, nên vị trí trong r lệch 1
            int start = best + bestLen - 1;
            if (start >= 0 && start < r.length()) r = r.substring(start);
        } else {
            String l = r.toLowerCase(Locale.ROOT);
            for (String w : LEADING_WORDS) {
                if (l.startsWith(w)) {
                    r = r.substring(w.length());
                    break;
                }
            }
        }
        return r.replaceAll("^[\\s·•,:\\-–]+|[\\s·•,:\\-–.]+$", "").trim();
    }

    public static Maneuver detectKeyword(String s) {
        if (s == null) return Maneuver.UNKNOWN;
        String l = s.toLowerCase(Locale.ROOT);
        for (Object[] row : KEYWORDS) {
            for (String k : (String[]) row[1]) {
                if (l.contains(k)) return (Maneuver) row[0];
            }
        }
        return Maneuver.UNKNOWN;
    }
}

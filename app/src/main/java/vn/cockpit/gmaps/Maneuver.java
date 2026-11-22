package vn.cockpit.gmaps;

/**
 * Các kiểu hướng rẽ và mã hướng rẽ tương ứng của đồng hồ ECARX (DIM).
 *
 * Bảng mã lấy theo đúng bảng mà Sunray dùng (lớp y1.b trong bản 1.4.4):
 *   rẽ trái 102, rẽ phải 103, chếch trái 104, chếch phải 105,
 *   rẽ gắt trái 106, rẽ gắt phải 107, quay đầu trái 108, đi thẳng 109,
 *   vòng xuyến 112, đến nơi 115, quay đầu phải 119.
 * Sunray gửi -1 khi không biết hướng (ví dụ lên phà).
 */
public enum Maneuver {
    UNKNOWN("Chưa rõ hướng", -1),
    STRAIGHT("Đi thẳng", 109),
    LEFT("Rẽ trái", 102),
    RIGHT("Rẽ phải", 103),
    SLIGHT_LEFT("Chếch trái / giữ bên trái", 104),
    SLIGHT_RIGHT("Chếch phải / giữ bên phải", 105),
    SHARP_LEFT("Rẽ gắt trái", 106),
    SHARP_RIGHT("Rẽ gắt phải", 107),
    UTURN_LEFT("Quay đầu (vòng bên trái)", 108),
    UTURN_RIGHT("Quay đầu (vòng bên phải)", 119),
    ROUNDABOUT("Vòng xuyến / bùng binh", 112),
    DESTINATION("Đến nơi", 115),
    FERRY("Lên phà", -1);

    public final String label;
    public final int ecarxId;

    Maneuver(String label, int ecarxId) {
        this.label = label;
        this.ecarxId = ecarxId;
    }

    public static Maneuver fromName(String name) {
        if (name == null) return UNKNOWN;
        for (Maneuver m : values()) {
            if (m.name().equals(name)) return m;
        }
        return UNKNOWN;
    }
}

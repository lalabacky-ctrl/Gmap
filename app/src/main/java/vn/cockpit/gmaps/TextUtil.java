package vn.cockpit.gmaps;

import java.text.Normalizer;

/** Xử lý chữ: bỏ dấu tiếng Việt và chạy chữ (marquee) cho tên đường dài. */
public final class TextUtil {
    private TextUtil() {}

    /** "Nguyễn Văn Đậu" -> "Nguyen Van Dau". */
    public static String stripDiacritics(String s) {
        if (s == null || s.isEmpty()) return s;
        String n = Normalizer.normalize(s, Normalizer.Form.NFD);
        n = n.replaceAll("\\p{M}+", "");
        return n.replace('đ', 'd').replace('Đ', 'D');
    }

    public static String collapseSpaces(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\s\\u00A0]+", " ").trim();
    }

    /**
     * Chạy chữ giống Sunray (lớp y1.c): nếu chuỗi dài hơn "cửa sổ" thì mỗi lần gọi
     * next() dịch đi 1 ký tự. Khi chạy hết một vòng thì dừng lại vài nhịp ở đầu chuỗi.
     */
    public static final class Marquee {
        private String text = "";
        private int window;
        private int offset;
        private int pause;

        /** Trả về đoạn chữ cần hiển thị lúc này. window <= 0 nghĩa là không cắt. */
        public String next(String s, int window) {
            if (s == null) s = "";
            if (!s.equals(text) || window != this.window) {
                text = s;
                this.window = window;
                offset = 0;
                pause = 3;
            }
            if (window <= 0 || s.length() <= window) return s;

            String loop = s + "   ";
            StringBuilder sb = new StringBuilder(window);
            for (int i = 0; i < window; i++) {
                sb.append(loop.charAt((offset + i) % loop.length()));
            }
            if (pause > 0) {
                pause--;
            } else {
                offset++;
                if (offset >= loop.length()) {
                    offset = 0;
                    pause = 3;
                }
            }
            return sb.toString();
        }

        /** Chuỗi có cần chạy chữ không (để biết có cần gửi lại định kỳ). */
        public static boolean needsScrolling(String s, int window) {
            return s != null && window > 0 && s.length() > window;
        }
    }
}

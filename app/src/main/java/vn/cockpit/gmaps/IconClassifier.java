package vn.cockpit.gmaps;

import java.util.ArrayDeque;

/**
 * Đoán hướng rẽ từ hình mũi tên trong thông báo Google Maps.
 *
 * Google Maps không ghi hướng rẽ bằng chữ, chỉ có hình mũi tên. Lớp này:
 *  1. Tách mũi tên khỏi nền, thu về lưới 32x32 điểm đen/trắng ("chữ ký" của hình).
 *     Chữ ký dùng để ghi nhớ khi người dùng tự dạy hướng cho từng hình.
 *  2. Đoán hướng bằng hình học: tìm gốc mũi tên ở đáy hình, đi theo thân mũi tên
 *     tới điểm xa nhất (đầu mũi tên) rồi đo góc của đầu mũi tên.
 *     Có lỗ tròn kín ở giữa -> vòng xuyến.
 * Chỉ dùng Java thuần (mảng điểm ảnh ARGB) để có thể kiểm thử trên máy tính.
 */
public final class IconClassifier {

    public static final int N = 32;

    public static final class Result {
        /** 1024 bit dạng chuỗi hex (256 ký tự); null nếu hình trống. */
        public String signature;
        public Maneuver maneuver = Maneuver.UNKNOWN;
        /** Góc đầu mũi tên: 0 = lên, +90 = phải, -90 = trái, ±180 = xuống. */
        public double angle = Double.NaN;
        public String debug = "";
    }

    private IconClassifier() {}

    public static Result classify(int w, int h, int[] argb) {
        Result res = new Result();
        if (w <= 0 || h <= 0 || argb == null || argb.length < w * h) {
            res.debug = "không có ảnh";
            return res;
        }
        boolean[] fg = foregroundMask(w, h, argb);
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (fg[y * w + x]) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < 0) {
            res.debug = "ảnh trống";
            return res;
        }
        boolean[] g = normalize(w, h, fg, minX, minY, maxX, maxY);
        res.signature = toHex(g);
        classifyGrid(g, res);
        return res;
    }

    /** Điểm thuộc mũi tên: ảnh nền trong suốt -> theo độ đục; ảnh nền đặc -> khác màu nền. */
    static boolean[] foregroundMask(int w, int h, int[] p) {
        int border = 0, transparent = 0;
        long sr = 0, sg = 0, sb = 0;
        for (int x = 0; x < w; x++) {
            for (int y : new int[]{0, h - 1}) {
                int c = p[y * w + x];
                border++;
                if ((c >>> 24) < 200) transparent++;
                sr += (c >> 16) & 255; sg += (c >> 8) & 255; sb += c & 255;
            }
        }
        for (int y = 1; y < h - 1; y++) {
            for (int x : new int[]{0, w - 1}) {
                int c = p[y * w + x];
                border++;
                if ((c >>> 24) < 200) transparent++;
                sr += (c >> 16) & 255; sg += (c >> 8) & 255; sb += c & 255;
            }
        }
        boolean alphaMode = transparent * 2 >= border;
        int br = (int) (sr / border), bg = (int) (sg / border), bb = (int) (sb / border);
        boolean[] fg = new boolean[w * h];
        for (int i = 0; i < w * h; i++) {
            int c = p[i];
            int a = c >>> 24;
            if (alphaMode) {
                fg[i] = a >= 160; // nhánh mờ (đường không đi) có độ đục thấp nên bị loại
            } else {
                int d = Math.abs(((c >> 16) & 255) - br) + Math.abs(((c >> 8) & 255) - bg)
                        + Math.abs((c & 255) - bb);
                fg[i] = a >= 160 && d >= 120;
            }
        }
        return fg;
    }

    /** Cắt sát mũi tên, đặt vào ô vuông, lấy mẫu thành lưới 32x32. */
    static boolean[] normalize(int w, int h, boolean[] fg, int minX, int minY, int maxX, int maxY) {
        double bw = maxX - minX + 1, bh = maxY - minY + 1;
        double side = Math.max(bw, bh) * 1.08; // chừa lề nhỏ
        double cx = (minX + maxX + 1) / 2.0, cy = (minY + maxY + 1) / 2.0;
        double x0 = cx - side / 2, y0 = cy - side / 2, cell = side / N;
        boolean[] g = new boolean[N * N];
        for (int gy = 0; gy < N; gy++) {
            for (int gx = 0; gx < N; gx++) {
                int hit = 0;
                for (int sy = 0; sy < 4; sy++) {
                    for (int sx = 0; sx < 4; sx++) {
                        int x = (int) Math.floor(x0 + (gx + (sx + 0.5) / 4.0) * cell);
                        int y = (int) Math.floor(y0 + (gy + (sy + 0.5) / 4.0) * cell);
                        if (x >= 0 && y >= 0 && x < w && y < h && fg[y * w + x]) hit++;
                    }
                }
                g[gy * N + gx] = hit >= 6;
            }
        }
        return g;
    }

    static void classifyGrid(boolean[] g0, Result res) {
        boolean[] g = largestComponent(g0);
        int area = 0;
        for (boolean b : g) if (b) area++;
        if (area < 12) {
            res.debug = "hình quá nhỏ";
            return;
        }

        // Lỗ kín (vòng tròn) -> vòng xuyến.
        int hole = holeArea(g);
        if (hole >= 20) {
            res.maneuver = Maneuver.ROUNDABOUT;
            res.debug = "có lỗ kín " + hole + " ô";
            return;
        }

        // Gốc mũi tên: đoạn liền rộng nhất trong 3 hàng dưới cùng.
        int bottom = -1;
        for (int y = N - 1; y >= 0 && bottom < 0; y--) {
            for (int x = 0; x < N; x++) if (g[y * N + x]) { bottom = y; break; }
        }
        int seedRow = -1, seedStart = -1, seedLen = 0;
        for (int y = bottom; y >= Math.max(0, bottom - 2); y--) {
            int x = 0;
            while (x < N) {
                if (!g[y * N + x]) { x++; continue; }
                int s = x;
                while (x < N && g[y * N + x]) x++;
                int len = x - s;
                double centerDist = Math.abs((s + x) / 2.0 - N / 2.0);
                double bestCenter = seedStart < 0 ? 1e9 : Math.abs(seedStart + seedLen / 2.0 - N / 2.0);
                if (len > seedLen || (len == seedLen && centerDist < bestCenter)) {
                    seedRow = y; seedStart = s; seedLen = len;
                }
            }
        }
        if (seedRow < 0) {
            res.debug = "không thấy gốc";
            return;
        }

        // Đi theo thân mũi tên từ gốc (Dijkstra 8 hướng: ngang/dọc = 10, chéo = 14,
        // gần với khoảng cách thật nên đo góc chính xác hơn).
        int[] dist = new int[N * N];
        java.util.Arrays.fill(dist, Integer.MAX_VALUE);
        java.util.PriorityQueue<int[]> pq = new java.util.PriorityQueue<>(64,
                new java.util.Comparator<int[]>() {
                    @Override public int compare(int[] a, int[] b) { return Integer.compare(a[0], b[0]); }
                });
        for (int x = seedStart; x < seedStart + seedLen; x++) {
            int i = seedRow * N + x;
            dist[i] = 0;
            pq.add(new int[]{0, i});
        }
        while (!pq.isEmpty()) {
            int[] top = pq.poll();
            int i = top[1];
            if (top[0] > dist[i]) continue;
            int x = i % N, y = i / N;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0) continue;
                    int nx = x + dx, ny = y + dy;
                    if (nx < 0 || ny < 0 || nx >= N || ny >= N) continue;
                    int j = ny * N + nx;
                    if (!g[j]) continue;
                    int nd = dist[i] + ((dx == 0 || dy == 0) ? 10 : 14);
                    if (nd < dist[j]) {
                        dist[j] = nd;
                        pq.add(new int[]{nd, j});
                    }
                }
            }
        }
        int dmaxRaw = 0;
        for (int i = 0; i < N * N; i++) {
            if (dist[i] != Integer.MAX_VALUE && dist[i] > dmaxRaw) dmaxRaw = dist[i];
        }
        // đổi về đơn vị "ô" (1 ô = 10)
        for (int i = 0; i < N * N; i++) {
            dist[i] = dist[i] == Integer.MAX_VALUE ? -1 : (dist[i] + 5) / 10;
        }
        int dmax = (dmaxRaw + 5) / 10;
        if (dmax < 6) {
            res.debug = "mũi tên quá ngắn (" + dmax + ")";
            return;
        }

        // Đầu mũi tên = các điểm xa nhất; điểm lùi = đoạn thân ngay trước đầu.
        double tx = 0, ty = 0; int tn = 0;
        double bx = 0, by = 0; int bn = 0;
        int lo = dmax - Math.max(4, (int) Math.round(dmax * 0.40));
        int hi = dmax - Math.max(2, (int) Math.round(dmax * 0.18));
        for (int i = 0; i < N * N; i++) {
            int d = dist[i];
            if (d < 0) continue;
            int x = i % N, y = i / N;
            if (d >= dmax - 1) { tx += x; ty += y; tn++; }
            if (d >= lo && d <= hi) { bx += x; by += y; bn++; }
        }
        if (tn == 0 || bn == 0) {
            res.debug = "không tìm được đầu mũi tên";
            return;
        }
        tx /= tn; ty /= tn; bx /= bn; by /= bn;
        double vx = tx - bx, vy = ty - by;
        double ang = Math.toDegrees(Math.atan2(vx, -vy)); // 0 = lên, + = phải
        res.angle = ang;
        double a = Math.abs(ang);
        boolean left = ang < 0;
        double baseX = seedStart + seedLen / 2.0;

        Maneuver m;
        if (a <= 20) m = Maneuver.STRAIGHT;
        else if (a <= 66) m = left ? Maneuver.SLIGHT_LEFT : Maneuver.SLIGHT_RIGHT;
        else if (a <= 112) m = left ? Maneuver.LEFT : Maneuver.RIGHT;
        else if (a <= 148) m = left ? Maneuver.SHARP_LEFT : Maneuver.SHARP_RIGHT;
        else m = (tx < baseX) ? Maneuver.UTURN_LEFT : Maneuver.UTURN_RIGHT;

        // Mũi tên chỉ xuống nhưng đầu nằm cao hơn nửa hình -> rẽ gắt chứ không phải quay đầu.
        if ((m == Maneuver.UTURN_LEFT || m == Maneuver.UTURN_RIGHT) && ty < N * 0.45) {
            m = (tx < baseX) ? Maneuver.SHARP_LEFT : Maneuver.SHARP_RIGHT;
        }
        res.maneuver = m;
        res.debug = String.format(java.util.Locale.ROOT, "góc=%.0f° đầu=(%.1f,%.1f) gốc=%.1f dmax=%d",
                ang, tx, ty, baseX, dmax);
    }

    static boolean[] largestComponent(boolean[] g) {
        int[] label = new int[N * N];
        int best = 0, bestSize = 0, cur = 0;
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int s = 0; s < N * N; s++) {
            if (!g[s] || label[s] != 0) continue;
            cur++;
            int size = 0;
            label[s] = cur;
            q.add(s);
            while (!q.isEmpty()) {
                int i = q.poll();
                size++;
                int x = i % N, y = i / N;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx, ny = y + dy;
                        if (nx < 0 || ny < 0 || nx >= N || ny >= N) continue;
                        int j = ny * N + nx;
                        if (g[j] && label[j] == 0) {
                            label[j] = cur;
                            q.add(j);
                        }
                    }
                }
            }
            if (size > bestSize) {
                bestSize = size;
                best = cur;
            }
        }
        boolean[] out = new boolean[N * N];
        for (int i = 0; i < N * N; i++) out[i] = label[i] == best && best != 0;
        return out;
    }

    /** Số ô nền bị bao kín (không thông ra mép hình). */
    static int holeArea(boolean[] g) {
        boolean[] seen = new boolean[N * N];
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int i = 0; i < N; i++) {
            int[] edge = {i, (N - 1) * N + i, i * N, i * N + N - 1};
            for (int e : edge) {
                if (!g[e] && !seen[e]) {
                    seen[e] = true;
                    q.add(e);
                }
            }
        }
        while (!q.isEmpty()) {
            int i = q.poll();
            int x = i % N, y = i / N;
            int[][] nb = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] d : nb) {
                int nx = x + d[0], ny = y + d[1];
                if (nx < 0 || ny < 0 || nx >= N || ny >= N) continue;
                int j = ny * N + nx;
                if (!g[j] && !seen[j]) {
                    seen[j] = true;
                    q.add(j);
                }
            }
        }
        int holes = 0;
        for (int i = 0; i < N * N; i++) if (!g[i] && !seen[i]) holes++;
        return holes;
    }

    static String toHex(boolean[] g) {
        StringBuilder sb = new StringBuilder(N * N / 4);
        for (int i = 0; i < N * N; i += 4) {
            int v = (g[i] ? 8 : 0) | (g[i + 1] ? 4 : 0) | (g[i + 2] ? 2 : 0) | (g[i + 3] ? 1 : 0);
            sb.append(Character.forDigit(v, 16));
        }
        return sb.toString();
    }

    /** Số bit khác nhau giữa hai chữ ký; Integer.MAX_VALUE nếu không so được. */
    public static int hamming(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return Integer.MAX_VALUE;
        int d = 0;
        for (int i = 0; i < a.length(); i++) {
            int x = Character.digit(a.charAt(i), 16) ^ Character.digit(b.charAt(i), 16);
            d += Integer.bitCount(x);
        }
        return d;
    }

    /** Vẽ chữ ký ra dạng chữ (để xem trong nhật ký). */
    public static String asciiArt(String sig) {
        if (sig == null || sig.length() != N * N / 4) return "";
        StringBuilder sb = new StringBuilder();
        for (int y = 0; y < N; y++) {
            for (int x = 0; x < N; x++) {
                int i = y * N + x;
                int v = Character.digit(sig.charAt(i / 4), 16);
                boolean on = ((v >> (3 - (i % 4))) & 1) != 0;
                sb.append(on ? '#' : '.');
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}

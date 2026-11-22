package vn.cockpit.gmaps;

import android.content.Context;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Cầu nối tới màn hình đồng hồ (DIM) qua thư viện ECARX AdaptAPI có sẵn trong đầu máy.
 *
 * Viết lại theo đúng cách Sunray 1.4.4 làm (lớp a2.p / a2.o / a2.n):
 *   DimInteraction.create(context) -> IConnectable.registerConnectWatcher + connect()
 *   -> getNaviInteraction() -> notifyNavigationStatus(2)
 *   -> updateNavigationInfo(INavigationInfo) -> updateDriveDirection(0)
 *   Khi dừng: updateNavigationInfo(trạng thái 0), notifyTurnByTurnStopped(),
 *   notifyNavigationStatus(0), updateAddresses(3, danh sách rỗng).
 * Thư viện không đi kèm app mà nằm sẵn trong hệ thống đầu máy, nên mọi lời gọi
 * đều qua phản chiếu (reflection).
 */
final class DimBridge {
    static final String CLS_DIM = "com.ecarx.xui.adaptapi.diminteraction.DimInteraction";
    static final String CLS_NAVI = "com.ecarx.xui.adaptapi.diminteraction.INaviInteraction";
    static final String CLS_INFO = "com.ecarx.xui.adaptapi.diminteraction.INaviInteraction$INavigationInfo";
    static final String CLS_CONNECTABLE = "com.ecarx.xui.adaptapi.binder.IConnectable";
    static final String CLS_WATCHER = "com.ecarx.xui.adaptapi.binder.IConnectable$IConnectWatcher";

    /** Trạng thái dẫn đường gửi cho đồng hồ (Sunray dùng 2 = đang dẫn đường, 0 = không). */
    static final int STATUS_IDLE = 0;
    static final int STATUS_NAVIGATING = 2;

    interface Callback {
        void onConnectionChanged(boolean connected);
    }

    /** Một bản chụp dữ liệu gửi lên đồng hồ. */
    static final class Info {
        final int status;
        final int turnId;
        final String name;
        final long distNextM;
        final long distDestM;
        final long etaSec;
        /** Hình mũi tên gửi kèm (có thể null). */
        android.graphics.Bitmap arrow;

        Info(int status, int turnId, String name, long distNextM, long distDestM, long etaSec) {
            this.status = status;
            this.turnId = turnId;
            this.name = name == null ? "" : name;
            this.distNextM = distNextM;
            this.distDestM = distDestM;
            this.etaSec = etaSec;
        }

        static Info idle() {
            return new Info(STATUS_IDLE, 0, "", 0, 0, 0);
        }
    }

    private final Context ctx;
    private final Callback callback;
    private Object dim;
    private volatile Object navi;
    private boolean watcherRegistered;
    private boolean apiDumped;
    private Object naviCallbackFor;
    private android.graphics.Bitmap lastArrow;
    private final Set<String> newStyleBroken = Collections.synchronizedSet(new HashSet<String>());
    private volatile boolean libraryMissing;
    private volatile String lastError = "";
    private final Set<String> loggedOnce = Collections.synchronizedSet(new HashSet<String>());

    DimBridge(Context ctx, Callback callback) {
        this.ctx = ctx.getApplicationContext();
        this.callback = callback;
    }

    boolean isConnected() {
        return navi != null;
    }

    boolean isLibraryMissing() {
        return libraryMissing;
    }

    String lastError() {
        return lastError;
    }

    /** Kiểm tra nhanh máy có thư viện ECARX không (không kết nối). */
    static boolean libraryPresent() {
        try {
            Class.forName(CLS_DIM);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Tạo kết nối nếu chưa có. Trả về true nếu đã có giao diện điều khiển đồng hồ. */
    synchronized boolean ensure() {
        if (navi != null) return true;
        if (libraryMissing) return false;
        try {
            if (dim == null) {
                Class<?> c = Class.forName(CLS_DIM);
                dim = c.getMethod("create", Context.class).invoke(null, ctx);
                AppLog.i("DIM: đã tạo DimInteraction (" + (dim == null ? "null" : dim.getClass().getName()) + ")");
                registerWatcher();
            }
            loadNavi();
            if (navi == null) lastError = "Đồng hồ chưa sẵn sàng (đang chờ kết nối)";
        } catch (ClassNotFoundException e) {
            libraryMissing = true;
            lastError = "Đầu máy này không có thư viện ECARX AdaptAPI";
            AppLog.w(lastError, e);
        } catch (Throwable t) {
            lastError = describe(t);
            AppLog.w("DIM: lỗi khởi tạo", unwrap(t));
        }
        return navi != null;
    }

    private void registerWatcher() {
        if (watcherRegistered || dim == null) return;
        try {
            Class<?> conn = Class.forName(CLS_CONNECTABLE);
            if (!conn.isInstance(dim)) {
                watcherRegistered = true;
                return;
            }
            Class<?> w = Class.forName(CLS_WATCHER);
            Object watcher = Proxy.newProxyInstance(w.getClassLoader(), new Class<?>[]{w},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method m, Object[] args) {
                            String n = m.getName();
                            if ("onConnected".equals(n)) {
                                AppLog.i("DIM: đã kết nối");
                                synchronized (DimBridge.this) {
                                    try {
                                        loadNavi();
                                    } catch (Throwable t) {
                                        AppLog.w("DIM: không lấy được NaviInteraction", unwrap(t));
                                    }
                                }
                                callback.onConnectionChanged(navi != null);
                                return null;
                            }
                            if ("onDisConnected".equals(n) || "onDisconnected".equals(n)) {
                                AppLog.i("DIM: mất kết nối");
                                synchronized (DimBridge.this) {
                                    navi = null;
                                }
                                callback.onConnectionChanged(false);
                                return null;
                            }
                            return objectMethod(proxy, m, args, "DimWatcher");
                        }
                    });
            conn.getMethod("registerConnectWatcher", w).invoke(dim, watcher);
            conn.getMethod("connect").invoke(dim);
            watcherRegistered = true;
            AppLog.i("DIM: đã đăng ký theo dõi kết nối");
        } catch (Throwable t) {
            AppLog.w("DIM: không đăng ký được theo dõi kết nối", unwrap(t));
        }
    }

    private void loadNavi() throws Exception {
        if (dim == null) return;
        Method m = resolve(dim, CLS_DIM, "getNaviInteraction");
        Object n = m.invoke(dim);
        if (n != null && navi == null) {
            AppLog.i("DIM: đã có NaviInteraction (" + n.getClass().getName() + ")");
            if (!apiDumped) {
                apiDumped = true;
                dumpApi(dim, n);
            }
            registerNaviCallback(n);
        }
        navi = n;
        if (n != null) lastError = "";
    }

    // ---------------- các lệnh gửi lên đồng hồ ----------------

    synchronized boolean notifyStatus(int status) {
        return call("notifyNavigationStatus", new Class<?>[]{int.class}, status);
    }

    synchronized boolean updateDriveDirection(int dir) {
        return call("updateDriveDirection", new Class<?>[]{int.class}, dir);
    }

    /**
     * Gửi thêm từng giá trị bằng lệnh riêng (đầu máy đời mới như IHU689G có các lệnh này).
     * Lệnh nào đầu máy không có thì bỏ qua.
     */
    synchronized void updateIndividual(Info info) {
        boolean active = info.status == STATUS_NAVIGATING;
        // Chỉ gửi giá trị nào đổi (thư viện ECARX ghi log mỗi lần gọi, gửi liên tục làm trôi logcat).
        sendIfChanged("updateTurnByTurnArrow", int.class, active ? info.turnId : 0);
        sendIfChanged("updateDistanceToNextGuidancePoint", int.class, (int) (active ? info.distNextM : 0));
        sendIfChanged("updateNextGuidancePointName", String.class, active ? info.name : "");
        sendIfChanged("updateDistanceToDestination", int.class, (int) (active ? info.distDestM : 0));
        sendIfChanged("updateETA", int.class, (int) (active ? info.etaSec : 0));
        if (!active) lastSent.clear();
        // Hình mũi tên: chỉ gửi khi đổi hình (ảnh gửi qua binder khá nặng).
        if (active && info.arrow != null && info.arrow != lastArrow) {
            if (call("updateTurnByTurnArrow", new Class<?>[]{android.graphics.Bitmap.class}, info.arrow)) {
                lastArrow = info.arrow;
            }
        }
        if (!active) lastArrow = null;
        sendNewStyle(info);
    }

    // ---------- kiểu lệnh mới: updateNaviStatus(NaviStatus) / updateNaviInfo(NaviInfo) ----------

    /**
     * Đầu máy đời mới có thêm updateNaviStatus(NaviStatus) và updateNaviInfo(NaviInfo) dùng lớp dữ liệu
     * cụ thể. Chưa biết cấu trúc lớp, nên ghi cấu trúc vào Nhật ký rồi điền theo tên trường/tên hàm.
     */
    private void sendNewStyle(Info info) {
        Object target = navi;
        if (target == null) return;
        sendFilled(target, "updateNaviStatus", info);
        sendFilled(target, "updateNaviInfo", info);
    }

    private void sendFilled(Object target, String method, Info info) {
        if (newStyleBroken.contains(method)) return;
        try {
            Method m = null;
            for (Method x : target.getClass().getMethods()) {
                if (method.equals(x.getName()) && x.getParameterTypes().length == 1) {
                    m = x;
                    break;
                }
            }
            if (m == null) {
                newStyleBroken.add(method);
                return;
            }
            Class<?> c = m.getParameterTypes()[0];
            if (loggedOnce.add("struct:" + c.getName())) AppLog.i("Cấu trúc " + describeClass(c));
            Object obj = build(c, info);
            if (obj == null) {
                if (loggedOnce.add("nobuild:" + method)) AppLog.i("DIM: chưa tạo được " + c.getSimpleName() + " cho " + method);
                newStyleBroken.add(method);
                return;
            }
            m.setAccessible(true);
            m.invoke(target, obj);
            if (loggedOnce.add("ok:" + method)) AppLog.i("DIM: lệnh " + method + " chạy được: " + dumpObject(obj));
        } catch (Throwable t) {
            newStyleBroken.add(method);
            AppLog.w("DIM: " + method + " lỗi", unwrap(t));
        }
    }

    private Object build(Class<?> c, Info info) throws Exception {
        boolean active = info.status == STATUS_NAVIGATING;
        if (c.isEnum()) {
            Object[] cs = c.getEnumConstants();
            if (cs == null || cs.length == 0) return null;
            for (Object e : cs) {
                String n = e.toString().toUpperCase(java.util.Locale.ROOT);
                boolean stopLike = n.contains("IDLE") || n.contains("STOP") || n.contains("NONE") || n.contains("END");
                if (active && !stopLike && (n.contains("NAVI") || n.contains("GUID") || n.contains("START") || n.contains("ACTIVE"))) return e;
                if (!active && stopLike) return e;
            }
            return null;
        }
        Object obj = null;
        for (java.lang.reflect.Constructor<?> k : c.getDeclaredConstructors()) {
            Class<?>[] ps = k.getParameterTypes();
            if (ps.length == 0) {
                k.setAccessible(true);
                obj = k.newInstance();
                break;
            }
        }
        if (obj == null) {
            for (java.lang.reflect.Constructor<?> k : c.getDeclaredConstructors()) {
                Class<?>[] ps = k.getParameterTypes();
                if (ps.length == 1 && (ps[0] == int.class)) {
                    k.setAccessible(true);
                    obj = k.newInstance(info.status);
                    break;
                }
            }
        }
        if (obj == null) return null;
        // Điền bằng hàm set...
        for (Method s : c.getMethods()) {
            if (!s.getName().startsWith("set") || s.getParameterTypes().length != 1) continue;
            Object v = valueFor(s.getName().substring(3), s.getParameterTypes()[0], info);
            if (v == null) continue;
            try {
                s.invoke(obj, v);
            } catch (Throwable ignored) {
                // bỏ qua trường không điền được
            }
        }
        // Điền thẳng vào trường (nếu không có hàm set)
        for (java.lang.reflect.Field f : c.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            Object v = valueFor(f.getName(), f.getType(), info);
            if (v == null) continue;
            try {
                f.setAccessible(true);
                f.set(obj, v);
            } catch (Throwable ignored) {
                // bỏ qua
            }
        }
        return obj;
    }

    /** Đoán giá trị cho một trường theo tên. */
    private static Object valueFor(String name, Class<?> t, Info info) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        boolean active = info.status == STATUS_NAVIGATING;
        boolean str = t == String.class || t == CharSequence.class;
        if (t == android.graphics.Bitmap.class) return (n.contains("icon") || n.contains("arrow") || n.contains("turn")) ? info.arrow : null;
        if (str) {
            if (n.contains("road") || n.contains("street") || n.contains("name") || n.contains("guid")) return active ? info.name : "";
            return null;
        }
        if (!(t.isPrimitive() || Number.class.isAssignableFrom(t) || t == Boolean.class)) return null;
        if (n.contains("status") || n.contains("state")) return coerce(info.status, t);
        if (n.contains("svg")) return null;
        if (n.contains("turn") || n.contains("icon") || n.contains("arrow") || n.contains("maneuver")) {
            return coerce(active ? info.turnId : 0, t);
        }
        if (n.contains("dist") || n.contains("mileage")) {
            if (n.contains("dest") || n.contains("remain") || n.contains("total") || n.contains("all")) {
                return coerce(active ? info.distDestM : 0, t);
            }
            return coerce(active ? info.distNextM : 0, t);
        }
        if (n.equals("eta") || n.contains("time") || n.contains("eta")) return coerce(active ? info.etaSec : 0, t);
        return null;
    }

    private static String describeClass(Class<?> c) {
        StringBuilder sb = new StringBuilder(c.getName());
        if (c.isEnum()) {
            sb.append(" enum {");
            Object[] cs = c.getEnumConstants();
            if (cs != null) for (Object e : cs) sb.append(e).append(", ");
            return sb.append('}').toString();
        }
        sb.append(" | khởi tạo: ");
        for (java.lang.reflect.Constructor<?> k : c.getDeclaredConstructors()) {
            sb.append('(');
            Class<?>[] ps = k.getParameterTypes();
            for (int i = 0; i < ps.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(ps[i].getSimpleName());
            }
            sb.append(") ");
        }
        sb.append("| trường: ");
        for (java.lang.reflect.Field f : c.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            sb.append(f.getType().getSimpleName()).append(' ').append(f.getName()).append("; ");
        }
        sb.append("| hàm: ").append(methods(c));
        return sb.toString();
    }

    private static String dumpObject(Object o) {
        if (o == null) return "null";
        if (o.getClass().isEnum()) return o.toString();
        StringBuilder sb = new StringBuilder(o.getClass().getSimpleName()).append('{');
        for (java.lang.reflect.Field f : o.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            try {
                f.setAccessible(true);
                Object v = f.get(o);
                if (v instanceof android.graphics.Bitmap) v = "Bitmap";
                sb.append(f.getName()).append('=').append(v).append(", ");
            } catch (Throwable ignored) {
                // bỏ qua
            }
        }
        return sb.append('}').toString();
    }

    private final java.util.Map<String, Object> lastSent = new java.util.HashMap<>();

    private void sendIfChanged(String name, Class<?> type, Object value) {
        Object prev = lastSent.get(name);
        if (prev != null && prev.equals(value)) return;
        if (call(name, new Class<?>[]{type}, value)) lastSent.put(name, value);
    }

    /** Quên các giá trị đã gửi (lần sau gửi lại hết). */
    synchronized void resetSent() {
        lastSent.clear();
        lastArrow = null;
    }

    /** Đăng ký nhận phản hồi từ đồng hồ (để biết đồng hồ muốn gì). */
    private void registerNaviCallback(Object n) {
        if (n == null || naviCallbackFor == n) return;
        try {
            Method reg = null;
            for (Method m : n.getClass().getMethods()) {
                if ("registerNavigationInteractionCallback".equals(m.getName())
                        && m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0].isInterface()) {
                    reg = m;
                    break;
                }
            }
            if (reg == null) return;
            Class<?> cb = reg.getParameterTypes()[0];
            Object proxy = Proxy.newProxyInstance(cb.getClassLoader(), new Class<?>[]{cb},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object p, Method m, Object[] args) {
                            String name = m.getName();
                            if ("hashCode".equals(name) || "equals".equals(name) || "toString".equals(name)) {
                                return objectMethod(p, m, args, "NaviCallback");
                            }
                            StringBuilder a = new StringBuilder();
                            if (args != null) {
                                for (int i = 0; i < args.length; i++) {
                                    if (i > 0) a.append(", ");
                                    a.append(args[i]);
                                }
                            }
                            String line = "Đồng hồ báo: " + name + "(" + a + ")";
                            if (loggedOnce.size() < 2000 && loggedOnce.add("cb:" + line)) AppLog.i(line);
                            return defaultFor(m.getReturnType());
                        }
                    });
            reg.setAccessible(true);
            reg.invoke(n, proxy);
            naviCallbackFor = n;
            AppLog.i("DIM: đã đăng ký nhận phản hồi đồng hồ (" + cb.getName() + ")");
        } catch (Throwable t) {
            AppLog.w("DIM: không đăng ký được phản hồi đồng hồ", unwrap(t));
        }
    }

    // ---------- DimMenuInteraction: chuyển chế độ / tab của đồng hồ ----------

    private Object menu;

    private Object menu() throws Exception {
        if (menu == null && dim != null) {
            menu = dim.getClass().getMethod("getDimMenuInteraction").invoke(dim);
        }
        return menu;
    }

    /**
     * Gọi một lệnh của DimMenuInteraction theo tên, với các tham số số nguyên.
     * Trả về chuỗi mô tả kết quả (để ghi Nhật ký và hiện lên màn hình).
     */
    synchronized String menuCall(String name, long... args) {
        if (!ensure()) return "chưa kết nối được đồng hồ";
        try {
            Object m = menu();
            if (m == null) return "đầu máy không có DimMenuInteraction";
            Method found = null;
            for (Method x : m.getClass().getMethods()) {
                if (x.getName().equals(name) && x.getParameterTypes().length == args.length) {
                    found = x;
                    break;
                }
            }
            if (found == null) return "không có lệnh " + name + " với " + args.length + " tham số";
            Class<?>[] ps = found.getParameterTypes();
            Object[] a = new Object[args.length];
            for (int i = 0; i < args.length; i++) a[i] = coerce(args[i], ps[i]);
            found.setAccessible(true);
            Object r = found.invoke(m, a);
            StringBuilder sb = new StringBuilder(name).append('(');
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(args[i]);
            }
            sb.append(") = ").append(valueToString(r));
            AppLog.i("DIM menu: " + sb);
            return sb.toString();
        } catch (Throwable t) {
            String e = name + " lỗi: " + describe(t);
            AppLog.w("DIM menu: " + e, unwrap(t));
            return e;
        }
    }

    /** Một số đầu máy cần báo "bắt đầu dẫn đường từng bước" (không có thì bỏ qua). */
    synchronized boolean turnByTurnStarted() {
        return call("notifyTurnByTurnStarted", new Class<?>[0]);
    }

    /** Ghi vào nhật ký danh sách lệnh thật của thư viện đồng hồ trên đầu máy này. */
    private static void dumpApi(Object dimObj, Object naviObj) {
        try {
            AppLog.i("Thư viện đồng hồ nằm ở: " + naviObj.getClass().getClassLoader());
            AppLog.i("API DimInteraction (" + dimObj.getClass().getName() + "): " + methods(dimObj.getClass()));
            AppLog.i("API NaviInteraction (" + naviObj.getClass().getName() + "): " + methods(naviObj.getClass()));
            try {
                AppLog.i("API INavigationInfo: " + methods(Class.forName(CLS_INFO)));
            } catch (Throwable t) {
                AppLog.i("API INavigationInfo: không tìm thấy lớp " + CLS_INFO);
            }
        } catch (Throwable t) {
            AppLog.w("DIM: không liệt kê được API", t);
        }
        dumpFeatures(dimObj);
    }

    /** Ghi các cờ tính năng của đầu máy và các nhóm lệnh khác của đồng hồ (để biết đồng hồ có bật dẫn đường không). */
    private static void dumpFeatures(Object dimObj) {
        // 1. Cờ tính năng của nền tảng ECARX
        try {
            Class<?> pf = Class.forName("com.ecarx.xui.adaptapi.util.PlatformFeature");
            StringBuilder sb = new StringBuilder();
            java.util.TreeMap<String, String> vals = new java.util.TreeMap<>();
            for (Method m : pf.getMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers()) || m.getParameterTypes().length != 0) continue;
                Class<?> rt = m.getReturnType();
                if (!(rt.isPrimitive() || rt == String.class || Number.class.isAssignableFrom(rt) || rt == Boolean.class)) continue;
                if (rt == void.class) continue;
                try {
                    vals.put(m.getName(), String.valueOf(m.invoke(null)));
                } catch (Throwable t) {
                    vals.put(m.getName(), "lỗi");
                }
            }
            for (java.util.Map.Entry<String, String> e : vals.entrySet()) sb.append(e.getKey()).append('=').append(e.getValue()).append("; ");
            AppLog.i("Cờ tính năng (PlatformFeature): " + sb);
        } catch (Throwable t) {
            AppLog.i("Không đọc được PlatformFeature: " + describe(t));
        }
        // 2. Các nhóm lệnh khác của đồng hồ (menu, vendor...) và giá trị đọc được
        if (dimObj == null) return;
        String[] getters = {"getShowPresentationOption", "getSupportedRankingType"};
        for (String g : getters) {
            try {
                Object v = dimObj.getClass().getMethod(g).invoke(dimObj);
                AppLog.i("DIM " + g + "() = " + valueToString(v));
            } catch (Throwable t) {
                AppLog.i("DIM " + g + "(): " + describe(t));
            }
        }
        try {
            Object mi = dimObj.getClass().getMethod("getDimMenuInteraction").invoke(dimObj);
            if (mi != null) {
                Object nm = mi.getClass().getMethod("getNaviMode").invoke(mi);
                AppLog.i("DIM menu: getNaviMode() = " + valueToString(nm) + " (lúc mở app)");
            }
        } catch (Throwable t) {
            AppLog.i("DIM menu: getNaviMode(): " + describe(t));
        }
        String[] groups = {"getDimMenuInteraction", "getVendorInteraction"};
        for (String g : groups) {
            try {
                Object o = dimObj.getClass().getMethod(g).invoke(dimObj);
                if (o == null) {
                    AppLog.i("DIM " + g + "() = null");
                    continue;
                }
                AppLog.i("API " + g + " (" + o.getClass().getName() + "): " + methods(o.getClass()));
            } catch (Throwable t) {
                AppLog.i("DIM " + g + "(): " + describe(t));
            }
        }
    }

    private static String valueToString(Object v) {
        if (v == null) return "null";
        if (v.getClass().isArray()) {
            StringBuilder sb = new StringBuilder("[");
            int n = Array.getLength(v);
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append(", ");
                sb.append(Array.get(v, i));
            }
            return sb.append(']').toString();
        }
        return String.valueOf(v);
    }

    private static String methods(Class<?> c) {
        StringBuilder sb = new StringBuilder();
        java.util.TreeSet<String> seen = new java.util.TreeSet<>();
        for (Method m : c.getMethods()) {
            if (m.getDeclaringClass() == Object.class) continue;
            StringBuilder one = new StringBuilder(m.getName()).append('(');
            Class<?>[] ps = m.getParameterTypes();
            for (int i = 0; i < ps.length; i++) {
                if (i > 0) one.append(',');
                one.append(ps[i].getSimpleName());
            }
            seen.add(one.append(')').toString());
        }
        for (String x : seen) sb.append(x).append("; ");
        return sb.toString();
    }

    synchronized boolean turnByTurnStopped() {
        return call("notifyTurnByTurnStopped", new Class<?>[0]);
    }

    synchronized boolean clearAddresses() {
        return call("updateAddresses", new Class<?>[]{int.class, List.class}, 3, Collections.EMPTY_LIST);
    }

    synchronized boolean updateInfo(final Info info) {
        if (navi == null) return false;
        try {
            Class<?> infoCls = Class.forName(CLS_INFO);
            Object proxy = Proxy.newProxyInstance(infoCls.getClassLoader(), new Class<?>[]{infoCls},
                    new InfoHandler(info));
            return call("updateNavigationInfo", new Class<?>[]{infoCls}, proxy);
        } catch (Throwable t) {
            lastError = describe(t);
            AppLog.w("DIM: updateNavigationInfo lỗi", unwrap(t));
            return false;
        }
    }

    private boolean call(String name, Class<?>[] types, Object... args) {
        Object target = navi;
        if (target == null) return false;
        try {
            Method m = resolve(target, CLS_NAVI, name, types);
            m.invoke(target, args);
            if (loggedOnce.add("ok:" + name)) AppLog.i("DIM: lệnh " + name + " chạy được");
            return true;
        } catch (NoSuchMethodException e) {
            if (loggedOnce.add("nsm:" + name)) AppLog.i("DIM: đầu máy không có lệnh " + name);
            return false;
        } catch (Throwable t) {
            Throwable c = unwrap(t);
            lastError = name + ": " + describe(c);
            AppLog.w("DIM: " + name + " lỗi", c);
            if (c != null && "android.os.DeadObjectException".equals(c.getClass().getName())) {
                navi = null; // dịch vụ đồng hồ đã khởi động lại, lần sau lấy lại
            }
            return false;
        }
    }

    /** Tìm phương thức trên kiểu công khai đã biết trước, nếu không có thì trên lớp thực. */
    private static Method resolve(Object target, String knownType, String name, Class<?>... types)
            throws NoSuchMethodException {
        try {
            Class<?> k = Class.forName(knownType);
            if (k.isInstance(target)) return k.getMethod(name, types);
        } catch (ClassNotFoundException | NoSuchMethodException ignored) {
            // thử trên lớp thực bên dưới
        }
        Method m = target.getClass().getMethod(name, types);
        try {
            m.setAccessible(true);
        } catch (Throwable ignored) {
            // không sao
        }
        return m;
    }

    /** Trả lời các câu hỏi của đồng hồ về chuyến đi (giống a2.o của Sunray). */
    private final class InfoHandler implements InvocationHandler {
        private final Info info;

        InfoHandler(Info info) {
            this.info = info;
        }

        @Override
        public Object invoke(Object proxy, Method m, Object[] args) {
            String n = m.getName();
            Class<?> rt = m.getReturnType();
            boolean active = info.status == STATUS_NAVIGATING;
            Object v = answer(n, rt, active, proxy, m, args);
            if (!"toString".equals(n) && !"hashCode".equals(n) && !"equals".equals(n)
                    && loggedOnce.add("get:" + n)) {
                AppLog.i("DIM đọc: " + n + "() = " + v);
            }
            return v;
        }

        private Object answer(String n, Class<?> rt, boolean active, Object proxy, Method m, Object[] args) {
            switch (n) {
                case "toString":
                    return "GMapsNavInfo{status=" + info.status + ", turn=" + info.turnId + "}";
                case "hashCode":
                case "equals":
                    return objectMethod(proxy, m, args, "NavInfo");
                case "getNavigationStatus":
                    return coerce(info.status, rt);
                case "getNavigationTurnId":
                    return coerce(active ? info.turnId : 0, rt);
                case "getNavigationTurnSVG":
                    return null;
                case "getNextGuidancePointName":
                    return active ? info.name : "";
                case "getDistanceToNextGuidancePoint":
                    return coerce(active ? info.distNextM : 0, rt);
                case "getDistanceToDestination":
                    return coerce(active ? info.distDestM : 0, rt);
                case "getETA":
                    return coerce(active ? info.etaSec : 0, rt);
                case "getDrivingDirection":
                case "getMuteState":
                case "getDayNightMode":
                    return coerce(0, rt);
                case "getLaneInfo":
                    return rt.isArray() ? Array.newInstance(rt.getComponentType(), 0) : null;
                default:
                    // lệnh chưa biết: trả giá trị mặc định (dòng "DIM đọc" sẽ ghi lại tên lệnh)
                    return defaultFor(rt);
            }
        }
    }

    private static Object objectMethod(Object proxy, Method m, Object[] args, String label) {
        switch (m.getName()) {
            case "hashCode":
                return System.identityHashCode(proxy);
            case "equals":
                return args != null && args.length == 1 && proxy == args[0];
            case "toString":
                return label;
            default:
                return defaultFor(m.getReturnType());
        }
    }

    static Object coerce(long v, Class<?> rt) {
        if (rt == int.class || rt == Integer.class) return (int) v;
        if (rt == long.class || rt == Long.class) return v;
        if (rt == float.class || rt == Float.class) return (float) v;
        if (rt == double.class || rt == Double.class) return (double) v;
        if (rt == short.class || rt == Short.class) return (short) v;
        if (rt == byte.class || rt == Byte.class) return (byte) v;
        if (rt == boolean.class || rt == Boolean.class) return v != 0;
        if (rt == String.class || rt == CharSequence.class) return String.valueOf(v);
        if (rt == void.class) return null;
        return (int) v;
    }

    static Object defaultFor(Class<?> rt) {
        if (rt == void.class) return null;
        if (rt.isPrimitive()) return coerce(0, rt);
        if (rt.isArray()) return Array.newInstance(rt.getComponentType(), 0);
        if (rt == String.class || rt == CharSequence.class) return "";
        return null;
    }

    static Throwable unwrap(Throwable t) {
        while (t instanceof InvocationTargetException && t.getCause() != null) t = t.getCause();
        return t;
    }

    static String describe(Throwable t) {
        Throwable c = unwrap(t);
        if (c == null) return "lỗi không rõ";
        String msg = c.getMessage();
        return c.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }
}

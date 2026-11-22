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
                    // không khóa ở đây: thư viện có thể gọi từ luồng khác
                    if (loggedOnce.add("info:" + n)) {
                        AppLog.i("DIM hỏi thêm: " + n + "() -> trả giá trị mặc định");
                    }
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

package vn.cockpit.gmaps;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;

/** Màn hình chính: trạng thái, công tắc bật/tắt và các nút thử. */
public class MainActivity extends Activity {
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView status;
    private Button windowBtn;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            updateStatus();
            h.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Google Maps → Đồng hồ");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        status.setPadding(0, dp(12), 0, dp(12));
        status.setLineSpacing(0, 1.2f);
        root.addView(status);

        section(root, "Bật / tắt");
        toggle(root, "Đẩy chỉ đường Google Maps lên đồng hồ", Prefs.ENABLED, true);
        toggle(root, "Hiện tên đường trên đồng hồ", Prefs.SHOW_NAME, true);
        toggle(root, "Bỏ dấu tiếng Việt (nếu đồng hồ không hiện được dấu)", Prefs.STRIP_DIACRITICS, true);
        toggle(root, "Tự đoán hướng rẽ từ hình mũi tên", Prefs.USE_HEURISTIC, true);

        windowBtn = button(root, "", new View.OnClickListener() {
            @Override public void onClick(View v) { cycleWindow(); }
        });
        updateWindowLabel();

        section(root, "Quyền và kết nối");
        button(root, "Cấp quyền đọc thông báo", new View.OnClickListener() {
            @Override public void onClick(View v) { requestAccess(); }
        });
        button(root, "Cấp quyền bằng máy tính (lệnh adb)", new View.OnClickListener() {
            @Override public void onClick(View v) { showAdbDialog(); }
        });

        section(root, "Thử trên đồng hồ (không cần chạy xe)");
        button(root, "Gửi thử các hướng rẽ (khoảng 25 giây)", new View.OnClickListener() {
            @Override public void onClick(View v) {
                ClusterController.get(MainActivity.this).runTest();
                toast("Nhìn lên đồng hồ: mỗi hướng hiện 2,5 giây");
            }
        });

        LinearLayout raw = new LinearLayout(this);
        raw.setOrientation(LinearLayout.HORIZONTAL);
        final EditText idInput = new EditText(this);
        idInput.setHint("Mã hướng rẽ, ví dụ 102");
        idInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        raw.addView(idInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button sendRaw = new Button(this);
        sendRaw.setText("Gửi mã");
        sendRaw.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    int id = Integer.parseInt(idInput.getText().toString().trim());
                    ClusterController.get(MainActivity.this).sendRawTurnId(id);
                    toast("Đang gửi mã " + id + " trong 8 giây");
                } catch (NumberFormatException e) {
                    toast("Nhập một con số");
                }
            }
        });
        raw.addView(sendRaw);
        root.addView(raw);

        button(root, "Xóa hiển thị trên đồng hồ ngay", new View.OnClickListener() {
            @Override public void onClick(View v) {
                ClusterController.get(MainActivity.this).clearNow();
                toast("Đã xóa");
            }
        });

        section(root, "Hướng rẽ");
        button(root, "Dạy hướng rẽ cho các hình mũi tên", new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, TeachActivity.class));
            }
        });

        section(root, "Khác");
        button(root, "Mở Google Maps", new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = null;
                for (String pkg : MapsListenerService.KNOWN_MAPS_PKGS) {
                    i = getPackageManager().getLaunchIntentForPackage(pkg);
                    if (i != null) break;
                }
                if (i == null) toast("Đầu máy chưa cài Google Maps");
                else startActivity(i);
            }
        });
        button(root, "Nhật ký và dữ liệu chẩn đoán", new View.OnClickListener() {
            @Override public void onClick(View v) { showLog(); }
        });

        TextView help = new TextView(this);
        help.setPadding(0, dp(16), 0, 0);
        help.setText("Lưu ý:\n"
                + "• Google Maps phải chạy ngay trên đầu máy. Nếu dùng Android Auto / CarPlay thì "
                + "Google Maps chạy trên điện thoại, app này không đọc được.\n"
                + "• Nếu đồng hồ hiện sai hướng, vào \"Dạy hướng rẽ\" chọn lại hướng cho hình đó; "
                + "lần sau gặp hình ấy sẽ đúng.\n"
                + "• Nếu đồng hồ không hiện gì khi bấm \"Gửi thử\", mở \"Nhật ký\" xem dòng lỗi DIM.");
        root.addView(help);

        setContentView(scroll);
        ClusterController.get(this).connect();
    }

    @Override
    protected void onResume() {
        super.onResume();
        h.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(refresh);
    }

    private void updateStatus() {
        ClusterController c = ClusterController.get(this);
        boolean granted = ListenerAccess.isGranted(this);
        StringBuilder sb = new StringBuilder();
        sb.append("• Quyền đọc thông báo: ").append(granted ? "ĐÃ CẤP" : "CHƯA CẤP");
        if (granted && !MapsListenerService.sConnected) sb.append(" (dịch vụ chưa chạy)");
        sb.append('\n');
        sb.append("• Thư viện đồng hồ ECARX: ")
                .append(DimBridge.libraryPresent() ? "CÓ" : "KHÔNG CÓ trên đầu máy này").append('\n');
        sb.append("• Kết nối đồng hồ (DIM): ").append(c.isDimConnected() ? "ĐÃ KẾT NỐI" : "CHƯA");
        String err = c.lastError();
        if (!c.isDimConnected() && err != null && !err.isEmpty()) sb.append(" — ").append(err);
        sb.append('\n');
        sb.append("• Google Maps: ").append(c.isMapsNavigating() ? "ĐANG DẪN ĐƯỜNG" : "không dẫn đường").append('\n');
        String shown = c.shown();
        sb.append("• Đang hiện: ").append(shown == null || shown.isEmpty() ? "—" : shown).append('\n');
        sb.append("• Kiểu cài đặt: ")
                .append(ListenerAccess.isSystemUid() ? "app hệ thống (giống Sunray)" : "app thường")
                .append(" · đầu máy ").append(Prefs.headUnitModel().isEmpty() ? "?" : Prefs.headUnitModel());
        status.setText(sb.toString());
    }

    private void cycleWindow() {
        int cur = Prefs.nameWindowSetting(this);
        int[] ch = Prefs.WINDOW_CHOICES;
        int idx = 0;
        for (int i = 0; i < ch.length; i++) if (ch[i] == cur) idx = i;
        Prefs.put(this, Prefs.NAME_WINDOW, ch[(idx + 1) % ch.length]);
        updateWindowLabel();
        ClusterController.get(this).refresh();
    }

    private void updateWindowLabel() {
        windowBtn.setText("Độ dài tên đường trên đồng hồ: " + Prefs.windowLabel(Prefs.nameWindowSetting(this))
                + " (bấm để đổi)");
    }

    private void requestAccess() {
        if (ListenerAccess.isGranted(this)) {
            ListenerAccess.rebind(this);
            toast("Đã có quyền đọc thông báo");
            return;
        }
        if (ListenerAccess.trySelfGrant(this)) {
            toast("Đã tự cấp quyền đọc thông báo");
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            toast("Bật mục \"Google Maps → Đồng hồ\" trong danh sách rồi quay lại");
        } catch (Throwable t) {
            showAdbDialog();
        }
    }

    private void showAdbDialog() {
        final String cmd = ListenerAccess.adbCommand(this) + "\n"
                + "adb shell pm grant " + getPackageName() + " android.permission.WRITE_SECURE_SETTINGS";
        TextView tv = new TextView(this);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(20), dp(12), dp(20), dp(12));
        tv.setText("Nếu đầu máy không có màn hình cấp quyền đọc thông báo, cắm máy tính vào đầu máy "
                + "(bật gỡ lỗi USB / ADB) rồi chạy:\n\n" + cmd
                + "\n\nDòng đầu cấp quyền đọc thông báo. Dòng thứ hai cho phép app tự cấp lại quyền sau này.");
        new AlertDialog.Builder(this)
                .setTitle("Cấp quyền bằng lệnh adb")
                .setView(tv)
                .setPositiveButton("Sao chép lệnh", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { copy(cmd); }
                })
                .setNegativeButton("Đóng", null)
                .show();
    }

    private void showLog() {
        final String text = "=== Nhật ký ===\n" + AppLog.dump()
                + "\n=== Thông báo Google Maps gần nhất ===\n"
                + (MapsListenerService.sLastDump.isEmpty() ? "(chưa có)" : MapsListenerService.sLastDump);
        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setTextIsSelectable(true);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setPadding(dp(16), dp(12), dp(16), dp(12));
        tv.setText(text);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("Nhật ký")
                .setView(sv)
                .setPositiveButton("Sao chép", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { copy(text); }
                })
                .setNeutralButton("Lưu ra file", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { saveLog(text); }
                })
                .setNegativeButton("Đóng", null)
                .show();
    }

    private void saveLog(String text) {
        File dir = getExternalFilesDir(null);
        if (dir == null) dir = getFilesDir();
        File f = new File(dir, "nhat_ky_dong_ho.txt");
        try {
            FileOutputStream out = new FileOutputStream(f);
            out.write(text.getBytes("UTF-8"));
            out.close();
            toast("Đã lưu: " + f.getAbsolutePath());
        } catch (Throwable t) {
            toast("Không lưu được: " + t.getMessage());
        }
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("nhat ky", text));
            toast("Đã sao chép");
        }
    }

    // ---------------- dựng giao diện ----------------

    private void section(LinearLayout root, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(18), 0, dp(6));
        root.addView(t);
    }

    private void toggle(LinearLayout root, String label, final String key, boolean def) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        s.setPadding(0, dp(10), 0, dp(10));
        s.setChecked(Prefs.sp(this).getBoolean(key, def));
        s.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                Prefs.put(MainActivity.this, key, on);
                ClusterController.get(MainActivity.this).refresh();
            }
        });
        root.addView(s, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private Button button(LinearLayout root, String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setOnClickListener(l);
        root.addView(b, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}

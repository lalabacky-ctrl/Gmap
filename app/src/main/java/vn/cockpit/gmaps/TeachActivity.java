package vn.cockpit.gmaps;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Danh sách các hình mũi tên Google Maps đã gửi gần đây. Bấm vào một hình để chọn
 * hướng đúng cho nó; app sẽ nhớ và dùng hướng này mỗi khi gặp lại hình đó.
 */
public class TeachActivity extends Activity {
    private final List<IconStore.Seen> items = new ArrayList<>();
    private Adapter adapter;
    private TextView header;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        header = new TextView(this);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        header.setPadding(0, 0, 0, dp(8));
        root.addView(header);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button clearSeen = new Button(this);
        clearSeen.setAllCaps(false);
        clearSeen.setText("Xóa danh sách hình");
        clearSeen.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                confirm("Xóa danh sách hình đã gặp? (hướng đã dạy vẫn giữ)", new Runnable() {
                    @Override public void run() {
                        IconStore.get(TeachActivity.this).clearSeen();
                        reload();
                    }
                });
            }
        });
        Button clearLearned = new Button(this);
        clearLearned.setAllCaps(false);
        clearLearned.setText("Quên mọi hướng đã dạy");
        clearLearned.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                confirm("Quên toàn bộ hướng đã dạy?", new Runnable() {
                    @Override public void run() {
                        IconStore.get(TeachActivity.this).clearLearned();
                        reload();
                    }
                });
            }
        });
        buttons.addView(clearSeen, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        buttons.addView(clearLearned, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(buttons);

        ListView list = new ListView(this);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                choose(items.get(pos));
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        setTitle("Dạy hướng rẽ");
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        IconStore store = IconStore.get(this);
        items.clear();
        items.addAll(store.listSeen());
        header.setText("Các hình mũi tên Google Maps đã gặp: " + items.size()
                + " · đã dạy: " + store.learnedCount()
                + "\nBấm vào hình để chọn hướng đúng. Hình hiện trên nền tối cho dễ nhìn.");
        adapter.notifyDataSetChanged();
    }

    private void choose(final IconStore.Seen s) {
        final Maneuver[] all = Maneuver.values();
        String[] labels = new String[all.length + 1];
        for (int i = 0; i < all.length; i++) {
            labels[i] = all[i].label + (all[i].ecarxId >= 0 ? "  (mã " + all[i].ecarxId + ")" : "");
        }
        labels[all.length] = "Bỏ hướng đã dạy cho hình này";
        new AlertDialog.Builder(this)
                .setTitle("Hình này là hướng nào?")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        IconStore store = IconStore.get(TeachActivity.this);
                        if (which == all.length) store.forget(s.sig);
                        else store.learn(s.sig, all[which]);
                        reload();
                    }
                })
                .setNegativeButton("Đóng", null)
                .show();
    }

    private void confirm(String msg, final Runnable yes) {
        new AlertDialog.Builder(this)
                .setMessage(msg)
                .setPositiveButton("Đồng ý", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { yes.run(); }
                })
                .setNegativeButton("Thôi", null)
                .show();
    }

    private final class Adapter extends BaseAdapter {
        private final SimpleDateFormat fmt = new SimpleDateFormat("dd/MM HH:mm", Locale.ROOT);

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout row;
            ImageView img;
            TextView txt;
            if (convert instanceof LinearLayout) {
                row = (LinearLayout) convert;
                img = (ImageView) row.getChildAt(0);
                txt = (TextView) row.getChildAt(1);
            } else {
                row = new LinearLayout(TeachActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(6), 0, dp(6));
                img = new ImageView(TeachActivity.this);
                img.setBackgroundColor(0xFF2B2B2B);
                img.setPadding(dp(4), dp(4), dp(4), dp(4));
                row.addView(img, new LinearLayout.LayoutParams(dp(72), dp(72)));
                txt = new TextView(TeachActivity.this);
                txt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                txt.setPadding(dp(12), 0, 0, 0);
                row.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            IconStore.Seen s = items.get(pos);
            Bitmap bm = s.png.exists() ? BitmapFactory.decodeFile(s.png.getAbsolutePath()) : null;
            img.setImageBitmap(bm);
            Maneuver taught = IconStore.get(TeachActivity.this).learnedExact(s.sig);
            StringBuilder sb = new StringBuilder();
            if (taught != null) sb.append("ĐÃ DẠY: ").append(taught.label);
            else sb.append("Chưa dạy · app đoán: ").append(s.guess.label);
            sb.append("\nGặp ").append(s.count).append(" lần · lần cuối ").append(fmt.format(new Date(s.lastSeen)));
            if (s.road != null && !s.road.isEmpty()) sb.append("\nĐường: ").append(s.road);
            txt.setText(sb.toString());
            return row;
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

package com.devhider.lsp.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.devhider.lsp.R;
import com.devhider.lsp.util.RootUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 目标 App 选择 + 写入 /data/adb/devhider/packages.txt（Zygisk 模块读这份名单）。
 * LSPosed 侧的作用域仍需在 LSPosed 管理器里勾选同样的 App。
 */
public class MainActivity extends Activity {

    public static final String TARGET_FILE = "/data/adb/devhider/packages.txt";
    private static final String PREF = "targets";
    private static final String K_SET = "set";

    private final List<AppInfo> all = new ArrayList<>();
    private final List<AppInfo> shown = new ArrayList<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private AppAdapter adapter;
    private TextView tvStatus;
    private String filter = "";

    private static final String[] FINANCE_KEYS = {
            "bank", "hsbc", "pay", "wallet", "card", "finance", "invest", "broker",
            "证券", "银行", "金融", "支付", "钱包", "信用卡", "保险", "理财", "股票"
    };

    public static final class AppInfo {
        public String pkg;
        public String label;
        public boolean system;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        EditText et = findViewById(R.id.etSearch);
        ListView lv = findViewById(R.id.lvApps);
        adapter = new AppAdapter();
        lv.setAdapter(adapter);

        selected.addAll(loadSaved());

        et.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
                filter = s.toString().trim().toLowerCase(Locale.ROOT);
                applyFilter();
            }

            public void afterTextChanged(Editable s) {
            }
        });

        CheckBox cbLog = findViewById(R.id.cbLog);
        cbLog.setChecked(getSharedPreferences("config", MODE_PRIVATE).getBoolean("debug_log", false));
        cbLog.setOnCheckedChangeListener((buttonView, isChecked) ->
                getSharedPreferences("config", MODE_PRIVATE).edit()
                        .putBoolean("debug_log", isChecked).apply());

        findViewById(R.id.btnSave).setOnClickListener(v -> save());
        findViewById(R.id.btnSelfCheck).setOnClickListener(v ->
                startActivity(new Intent(this, SelfCheckActivity.class)));
        findViewById(R.id.btnBanks).setOnClickListener(v -> {
            int n = 0;
            for (AppInfo a : all) {
                if (isFinance(a) && selected.add(a.pkg)) n++;
            }
            saveLocal();
            applyFilter();
            Toast.makeText(this, "已选中 " + n + " 个", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btnClear).setOnClickListener(v -> {
            selected.clear();
            saveLocal();
            applyFilter();
        });

        loadApps();
    }

    private void loadApps() {
        new Thread(() -> {
            List<AppInfo> tmp = new ArrayList<>();
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> infos = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            for (ApplicationInfo ai : infos) {
                AppInfo a = new AppInfo();
                a.pkg = ai.packageName;
                try {
                    CharSequence l = pm.getApplicationLabel(ai);
                    a.label = (l == null || l.length() == 0) ? a.pkg : l.toString();
                } catch (Throwable t) {
                    a.label = a.pkg;
                }
                a.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                tmp.add(a);
            }
            tmp.sort(Comparator.<AppInfo>comparingInt(o -> o.system ? 1 : 0)
                    .thenComparing(o -> o.label.toLowerCase(Locale.ROOT)));
            final List<AppInfo> result = tmp;
            runOnUiThread(() -> {
                all.clear();
                all.addAll(result);
                applyFilter();
                tvStatus.setText("共 " + all.size() + " 个应用，已选 " + selected.size()
                        + " 个 —— 还要去 LSPosed 里对这些应用勾选作用域");
            });
        }).start();
    }

    private static boolean isFinance(AppInfo a) {
        String s = (a.pkg + " " + a.label).toLowerCase(Locale.ROOT);
        for (String k : FINANCE_KEYS) {
            if (s.contains(k)) return true;
        }
        return false;
    }

    private void applyFilter() {
        shown.clear();
        for (AppInfo a : all) {
            if (filter.isEmpty()
                    || a.pkg.toLowerCase(Locale.ROOT).contains(filter)
                    || a.label.toLowerCase(Locale.ROOT).contains(filter)) {
                shown.add(a);
            }
        }
        adapter.notifyDataSetChanged();
    }

    private void saveLocal() {
        SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);
        sp.edit().putStringSet(K_SET, new LinkedHashSet<>(selected)).apply();
    }

    private Set<String> loadSaved() {
        SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);
        Set<String> s = sp.getStringSet(K_SET, null);
        return s == null ? new LinkedHashSet<>() : new LinkedHashSet<>(s);
    }

    private void save() {
        saveLocal();
        StringBuilder sb = new StringBuilder();
        sb.append("# DevOptsHider target packages\n");
        for (String p : selected) {
            sb.append(p).append('\n');
        }
        String content = sb.toString();
        new Thread(() -> {
            boolean ok = RootUtil.pushText(content, TARGET_FILE, getFilesDir());
            String msg = ok ? ("已写入 " + TARGET_FILE + "（" + selected.size() + " 个）")
                    : "写入失败：请确认已授予 root 权限";
            runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
        }).start();
    }

    private class AppAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_app, parent, false);
            }
            AppInfo a = shown.get(position);
            TextView tvLabel = v.findViewById(R.id.tvLabel);
            TextView tvPkg = v.findViewById(R.id.tvPkg);
            TextView tvSys = v.findViewById(R.id.tvSys);
            CheckBox cb = v.findViewById(R.id.cb);
            tvLabel.setText(a.label);
            tvPkg.setText(a.pkg);
            tvSys.setVisibility(a.system ? View.VISIBLE : View.GONE);
            cb.setOnCheckedChangeListener(null);
            cb.setChecked(selected.contains(a.pkg));
            cb.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked) selected.add(a.pkg);
                else selected.remove(a.pkg);
                saveLocal();
            });
            v.setOnClickListener(x -> cb.toggle());
            return v;
        }
    }
}

package com.devhider.lsp.ui;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.devhider.lsp.R;
import com.devhider.lsp.Rules;
import com.devhider.lsp.util.RootUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 把「真值」和「目标 App 里将读到的值」并排列出来，用来确认伪造规则确实命中了。 */
public class SelfCheckActivity extends Activity {

    private final List<Row> rows = new ArrayList<>();
    private final Map<String, String> props = new LinkedHashMap<>();
    private BaseAdapter adapter;

    public static final class Row {
        public String name;
        public String real;
        public String fake;
        public boolean hit;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_selfcheck);

        ListView lv = findViewById(R.id.lvCheck);
        adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return rows.size();
            }

            @Override
            public Object getItem(int position) {
                return rows.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = convertView;
                if (v == null) {
                    v = LayoutInflater.from(SelfCheckActivity.this)
                            .inflate(R.layout.item_check, parent, false);
                }
                Row r = rows.get(position);
                ((TextView) v.findViewById(R.id.tvName)).setText(r.name);
                ((TextView) v.findViewById(R.id.tvReal))
                        .setText(getString(R.string.real_value) + ": " + r.real);
                TextView tvFake = v.findViewById(R.id.tvFake);
                tvFake.setText(getString(R.string.fake_value) + ": " + r.fake);
                tvFake.setTextColor(r.hit ? 0xFF2E7D32 : 0xFF888888);
                return v;
            }
        };
        lv.setAdapter(adapter);

        findViewById(R.id.btnReload).setOnClickListener(v -> run());
        run();
    }

    private void run() {
        new Thread(() -> {
            List<Row> tmp = new ArrayList<>();

            String[] propKeys = {
                    "ro.debuggable", "ro.secure", "ro.build.type", "ro.build.tags",
                    "ro.build.fingerprint", "sys.usb.state", "sys.usb.config",
                    "persist.sys.usb.config", "init.svc.adbd", "service.adb.tcp.port",
                    "persist.adb.tcp.port", "ro.adb.secure",
            };
            StringBuilder names = new StringBuilder();
            for (String k : propKeys) names.append(k).append(' ');
            String out = RootUtil.su("for p in " + names
                    + "; do printf '%s=%s\\n' \"$p\" \"$(getprop $p)\"; done").out;
            props.clear();
            for (String line : out.split("\n")) {
                int i = line.indexOf('=');
                if (i > 0) props.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
            }
            for (String k : propKeys) {
                String real = props.get(k);
                tmp.add(make("prop: " + k, real, Rules.propFake(k, real, true)));
            }

            String[] settingKeys = {
                    "adb_enabled", "development_settings_enabled", "adb_wifi_enabled",
                    "adb_allowed_connection_time", "debug_app", "wait_for_debugger",
                    "mock_location", "show_touches", "pointer_location",
                    "window_animation_scale", "animator_duration_scale",
                    "enable_gpu_debug_layers", "bugreport_in_power_menu",
                    "stay_on_while_plugged_in", "always_finish_activities", "oem_unlock_allowed",
            };
            for (String k : settingKeys) {
                tmp.add(make("settings: " + k, getSetting(k), Rules.settingsFake(k, true)));
            }

            tmp.add(make("Build.TYPE", Build.TYPE, "user"));
            tmp.add(make("Build.TAGS", Build.TAGS, Rules.stripDebugWord(Build.TAGS)));
            tmp.add(make("Build.FINGERPRINT", Build.FINGERPRINT, Rules.stripDebugWord(Build.FINGERPRINT)));

            runOnUiThread(() -> {
                rows.clear();
                rows.addAll(tmp);
                adapter.notifyDataSetChanged();
            });
        }).start();
    }

    private String getSetting(String key) {
        try {
            String v = Settings.Global.getString(getContentResolver(), key);
            if (v == null) v = Settings.Secure.getString(getContentResolver(), key);
            if (v == null) v = Settings.System.getString(getContentResolver(), key);
            return v == null ? "(null)" : v;
        } catch (Throwable t) {
            return "ERR " + t;
        }
    }

    private static Row make(String name, String real, String fake) {
        Row r = new Row();
        r.name = name;
        r.real = real == null ? "(null)" : real;
        r.fake = fake == null ? "（不改）" : fake;
        r.hit = fake != null && !r.real.equals(r.fake);
        return r;
    }
}

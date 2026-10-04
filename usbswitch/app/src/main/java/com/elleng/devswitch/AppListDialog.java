package com.elleng.devswitch;

import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 联动应用选择 —— 原生 AlertDialog 弹窗。
 * 内含搜索框 + 可滚动列表 + 轻量下拉刷新（无第三方依赖，仅用 framework 控件）。
 * 下拉到顶后继续下拉可重新扫描已安装应用，反映新装/卸载的变化。
 */
public class AppListDialog {

    /** 下拉刷新触发阈值（px） */
    private static final int PTR_THRESHOLD = 140;
    /** 刷新提示区高度（px），与布局中 ptrHeader 的 56dp 对应 */
    private int headerHeightPx;

    private final Context context;
    private final OnAppChosenListener listener;
    private final PackageManager pm;

    private final ArrayList<AppInfo> all = new ArrayList<>();
    private final ArrayList<AppInfo> filtered = new ArrayList<>();

    private ListView listView;
    private TextView ptrHeader;
    private TextView loadingView;
    private FrameLayout ptrContainer;
    private AppAdapter adapter;
    private AlertDialog dialog;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int iconSizePx;

    private float startY = -1;
    private boolean pulling = false;
    private boolean refreshing = false;

    public interface OnAppChosenListener {
        void onAppChosen(String pkg);
    }

    private static class AppInfo {
        final String label;
        final String pkg;
        final ApplicationInfo ai;
        volatile Bitmap icon;
        volatile boolean iconLoading;

        AppInfo(String label, String pkg, ApplicationInfo ai) {
            this.label = label;
            this.pkg = pkg;
            this.ai = ai;
        }
    }

    private AppListDialog(Context context, OnAppChosenListener listener) {
        this.context = context;
        this.listener = listener;
        this.pm = context.getPackageManager();
    }

    public static void show(Context context, OnAppChosenListener listener) {
        try {
            new AppListDialog(context, listener).build();
        } catch (Throwable t) {
            // 兜底：绝不让选择器静默失败（表现为「点了没反应」）
            android.util.Log.e("AppListDialog", "show failed", t);
            Toast.makeText(context, R.string.picker_load_fail, Toast.LENGTH_LONG).show();
        }
    }

    private void build() {
        float density = context.getResources().getDisplayMetrics().density;
        headerHeightPx = (int) (56 * density + 0.5f);
        iconSizePx = (int) (40 * density + 0.5f);

        View root = LayoutInflater.from(context).inflate(R.layout.dialog_app_picker, null);
        ptrContainer = root.findViewById(R.id.ptrContainer);
        ptrHeader = root.findViewById(R.id.ptrHeader);
        loadingView = root.findViewById(R.id.tvLoading);
        listView = root.findViewById(R.id.lvApps);
        EditText et = root.findViewById(R.id.etSearch);

        // 初始：刷新提示隐藏在顶部之上
        ptrHeader.setTranslationY(-headerHeightPx);

        adapter = new AppAdapter();
        listView.setAdapter(adapter);

        listView.setOnItemClickListener((AdapterView<?> parent, View view, int position, long id) -> {
            AppInfo info = filtered.get(position);
            if (listener != null) {
                listener.onAppChosen(info.pkg);
            }
            if (dialog != null) {
                dialog.dismiss();
            }
        });

        listView.setOnTouchListener((View v, MotionEvent e) -> handleTouch(e));

        et.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                applyFilter(s.toString());
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
            }
        });

        dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.picker_title)
                .setView(root)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        // 先把弹窗显示出来，再去后台扫描应用。
        // 之前 loadApps() 在主线程同步跑（几百个 loadIcon），而 dialog.show() 写在它后面，
        // 导致点击后界面长时间无反应（Android 17 上尤其明显）。
        loadingView.setVisibility(View.VISIBLE);
        dialog.show();

        scanAppsAsync(false);
    }

    /** 后台扫描已安装应用，完成后回主线程刷新列表 */
    private void scanAppsAsync(boolean pullRefresh) {
        new Thread(() -> {
            ArrayList<AppInfo> result;
            boolean ok;
            try {
                result = loadApps();
                ok = true;
            } catch (Throwable t) {
                android.util.Log.e("AppListDialog", "loadApps failed", t);
                result = new ArrayList<>();
                ok = false;
            }
            final boolean success = ok;
            final ArrayList<AppInfo> data = result;
            main.post(() -> {
                if (dialog == null || !dialog.isShowing()) {
                    return;
                }
                all.clear();
                all.addAll(data);
                loadingView.setVisibility(View.GONE);
                applyFilter("");
                if (!success) {
                    Toast.makeText(context, R.string.picker_load_fail, Toast.LENGTH_LONG).show();
                }
                if (pullRefresh) {
                    refreshing = false;
                    animateBack();
                    if (success) {
                        Toast.makeText(context, R.string.ptr_done, Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }, "app-picker-scan").start();
    }

    // —— 下拉刷新手势处理 ——

    private boolean handleTouch(MotionEvent e) {
        if (refreshing) {
            return false; // 刷新中，列表正常滚动
        }
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (atListTop()) {
                    startY = e.getRawY();
                    pulling = false;
                } else {
                    startY = -1;
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (startY < 0) {
                    return false;
                }
                float delta = e.getRawY() - startY;
                if (delta > 0 && atListTop()) {
                    // 下拉：同时把提示区和列表往下推，露出刷新提示
                    pulling = true;
                    float reveal = Math.min(delta, headerHeightPx);
                    ptrHeader.setTranslationY(-headerHeightPx + reveal);
                    listView.setTranslationY(reveal);
                    ptrHeader.setText(delta >= PTR_THRESHOLD
                            ? R.string.ptr_release : R.string.ptr_pull);
                    return true; // 消费事件，阻止列表自身滚动
                } else if (delta <= 0) {
                    resetPull();
                    return false;
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (pulling) {
                    float dy = e.getRawY() - startY;
                    if (dy >= PTR_THRESHOLD) {
                        doRefresh();
                    } else {
                        animateBack();
                    }
                    pulling = false;
                    startY = -1;
                    return true;
                }
                startY = -1;
                break;
        }
        return false;
    }

    private boolean atListTop() {
        if (listView.getChildCount() == 0) {
            return true;
        }
        if (listView.getFirstVisiblePosition() > 0) {
            return false;
        }
        View first = listView.getChildAt(0);
        return first != null && first.getTop() >= 0;
    }

    private void resetPull() {
        ptrHeader.setTranslationY(-headerHeightPx);
        listView.setTranslationY(0);
    }

    private void animateBack() {
        ptrHeader.animate().translationY(-headerHeightPx).setDuration(200).start();
        listView.animate().translationY(0).setDuration(200).start();
    }

    private void doRefresh() {
        refreshing = true;
        // 固定露出刷新提示
        ptrHeader.setTranslationY(0);
        listView.setTranslationY(headerHeightPx);
        ptrHeader.setText(R.string.ptr_refreshing);
        scanAppsAsync(true);
    }

    // —— 应用列表加载 / 过滤 ——

    /** 扫描已安装应用（必须在后台线程调用）；图标懒加载，避免一次性解码几百个图标 */
    private ArrayList<AppInfo> loadApps() {
        ArrayList<AppInfo> out = new ArrayList<>();
        String self = context.getPackageName();
        List<ApplicationInfo> apps = pm.getInstalledApplications(0);
        for (ApplicationInfo ai : apps) {
            if (ai.packageName.equals(self)) {
                continue;
            }
            if (pm.getLaunchIntentForPackage(ai.packageName) == null) {
                continue; // 只列可启动的应用
            }
            CharSequence lbl = ai.loadLabel(pm);
            String label = (lbl == null) ? ai.packageName : lbl.toString();
            out.add(new AppInfo(label, ai.packageName, ai));
        }
        final Collator coll = Collator.getInstance(Locale.getDefault());
        Collections.sort(out, (x, y) -> coll.compare(x.label, y.label));
        return out;
    }

    /** 后台把应用图标渲染成方形 Bitmap（Drawable 不能跨线程用，Bitmap 可以） */
    private Bitmap decodeIcon(ApplicationInfo ai, int size) {
        try {
            android.graphics.drawable.Drawable d = ai.loadIcon(pm);
            if (d instanceof android.graphics.drawable.BitmapDrawable) {
                Bitmap src = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
                return Bitmap.createScaledBitmap(src, size, size, true);
            }
            Bitmap bmp = Bitmap.createBitmap(Math.max(1, size), Math.max(1, size),
                    Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(bmp);
            d.setBounds(0, 0, size, size);
            d.draw(c);
            return bmp;
        } catch (Throwable t) {
            return null;
        }
    }

    private void applyFilter(String q) {
        q = q.trim().toLowerCase(Locale.getDefault());
        filtered.clear();
        for (AppInfo a : all) {
            if (q.isEmpty()
                    || a.label.toLowerCase(Locale.getDefault()).contains(q)
                    || a.pkg.toLowerCase(Locale.getDefault()).contains(q)) {
                filtered.add(a);
            }
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private class AppAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return filtered.size();
        }

        @Override
        public Object getItem(int i) {
            return filtered.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(context)
                        .inflate(R.layout.item_app, parent, false);
            }
            AppInfo info = filtered.get(i);
            ((TextView) convertView.findViewById(R.id.tvLabel)).setText(info.label);
            ((TextView) convertView.findViewById(R.id.tvPkg)).setText(info.pkg);
            final ImageView iv = convertView.findViewById(R.id.ivIcon);
            final String key = info.pkg;
            iv.setTag(key);
            iv.setImageBitmap(info.icon);
            // 后台解码图标，完成后回主线程填充（Bitmap 不可变，跨线程安全）
            if (info.icon == null && !info.iconLoading) {
                info.iconLoading = true;
                final AppInfo target = info;
                new Thread(() -> {
                    Bitmap bmp = decodeIcon(target.ai, iconSizePx);
                    target.icon = bmp;
                    target.iconLoading = false;
                    main.post(() -> {
                        if (dialog == null || !dialog.isShowing()) {
                            return;
                        }
                        ImageView cur = listView.findViewWithTag(key);
                        if (cur != null) {
                            cur.setImageBitmap(bmp);
                        }
                    });
                }, "app-icon").start();
            }
            return convertView;
        }
    }
}

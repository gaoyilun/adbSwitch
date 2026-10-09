package com.elleng.adbswitch;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 快捷应用选择 —— 原生 AlertDialog 弹窗。
 * 内含搜索框 + 可滚动列表 + 轻量下拉刷新（无第三方依赖，仅用 framework 控件）。
 * 下拉到顶后继续下拉可重新扫描已安装应用，反映新装/卸载的变化。
 *
 * 两种模式：
 *  - 单选（{@link #show}）：点列表项即选定并关闭（用于一次性选择）；
 *  - 多选（{@link #showMulti}）：每项带勾选框，预勾已选应用，确认后返回勾选集合，
 *    用于「管理快捷应用」——勾上即加入、取消即移除，一次搞定多个 app 的增删。
 */
public class AppListDialog {

    /** 下拉刷新触发阈值（px） */
    private static final int PTR_THRESHOLD = 140;
    /** 刷新提示区高度（px），与布局中 ptrHeader 的 56dp 对应 */
    private int headerHeightPx;

    private final Context context;
    private final boolean multi;
    private final List<String> preselected;
    private final OnAppChosenListener singleListener;
    private final OnAppsChosenListener multiListener;
    private final PackageManager pm;

    /** 多选模式下被勾选的包名集合（保序：先按列表顺序） */
    private final Set<String> checked = new LinkedHashSet<>();

    private final ArrayList<AppInfo> all = new ArrayList<>();
    private final ArrayList<AppInfo> filtered = new ArrayList<>();

    private ListView listView;
    private TextView ptrHeader;
    private TextView loadingView;
    private EditText searchBox;
    private FrameLayout ptrContainer;
    private AppAdapter adapter;
    private AlertDialog dialog;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int iconSizePx;
    /**
     * 图标解码的共享后台线程。
     * 原先 getView 里每行 new Thread：快速滑动会瞬间起几十上百个线程（解码图标是重活），
     * 既抢 CPU 又可能 OOM。改成一个串行线程排队解码，配合 ViewHolder 复用行即可。
     */
    private final java.util.concurrent.ExecutorService iconLoader =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    /** 列表是否静止：快速滑动时不解码图标（避免成百个解码任务堆积、可见行图标迟迟不出来），
     *  停下（SCROLL_STATE_IDLE）时才补解码当前可见行。 */
    private boolean listIdle = true;

    private float startY = -1;
    private boolean pulling = false;
    private boolean refreshing = false;

    public interface OnAppChosenListener {
        void onAppChosen(String pkg);
    }

    /** 多选模式的回调：返回最终勾选的包名集合（顺序即列表顺序）。 */
    public interface OnAppsChosenListener {
        void onAppsChosen(List<String> pkgs);
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

    private AppListDialog(Context context, boolean multi, List<String> preselected,
                          OnAppChosenListener singleListener, OnAppsChosenListener multiListener) {
        this.context = context;
        this.multi = multi;
        this.preselected = preselected;
        this.singleListener = singleListener;
        this.multiListener = multiListener;
        this.pm = context.getPackageManager();
        if (multi && preselected != null) {
            checked.addAll(preselected);
        }
    }

    /** 单选：点列表项即选定并关闭。 */
    public static void show(Context context, OnAppChosenListener listener) {
        try {
            new AppListDialog(context, false, null, listener, null).build();
        } catch (Throwable t) {
            // 兜底：绝不让选择器静默失败（表现为「点了没反应」）
            android.util.Log.e("AppListDialog", "show failed", t);
            Toast.makeText(context, R.string.picker_load_fail, Toast.LENGTH_LONG).show();
        }
    }

    /** 多选：预勾 {@code preselected} 中的包名，确认后回传勾选集合（用于管理联动应用）。 */
    public static void showMulti(Context context, List<String> preselected,
                                 OnAppsChosenListener listener) {
        try {
            new AppListDialog(context, true, preselected, null, listener).build();
        } catch (Throwable t) {
            android.util.Log.e("AppListDialog", "showMulti failed", t);
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
        searchBox = et;

        // 初始：刷新提示隐藏在顶部之上
        ptrHeader.setTranslationY(-headerHeightPx);

        adapter = new AppAdapter();
        listView.setAdapter(adapter);

        listView.setOnItemClickListener((AdapterView<?> parent, View view, int position, long id) -> {
            AppInfo info = filtered.get(position);
            if (multi) {
                // 多选：切换勾选态，刷新该行（整行点击与勾选框同效）
                if (checked.contains(info.pkg)) {
                    checked.remove(info.pkg);
                } else {
                    checked.add(info.pkg);
                }
                updateRowCheckbox(view, info.pkg);
            } else {
                if (singleListener != null) {
                    singleListener.onAppChosen(info.pkg);
                }
                if (dialog != null) {
                    dialog.dismiss();
                }
            }
        });

        // 滚动状态监听：滑动中标记 listIdle=false（不触发新解码），停下后补解码当前可见行，
        // 避免快速滑动时成百上千个图标解码任务排队、可见行图标迟迟浮现。
        listView.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                listIdle = (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_IDLE);
                if (listIdle) {
                    int first = view.getFirstVisiblePosition();
                    int last = view.getLastVisiblePosition();
                    for (int i = first; i <= last && i < filtered.size(); i++) {
                        AppInfo info = filtered.get(i);
                        if (info.icon == null && !info.iconLoading) {
                            submitDecode(info);
                        }
                    }
                }
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem,
                                 int visibleItemCount, int totalItemCount) {
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

        AlertDialog.Builder b = new AlertDialog.Builder(context)
                .setTitle(multi ? R.string.picker_title_multi : R.string.picker_title)
                .setView(root)
                .setNegativeButton(android.R.string.cancel, null);
        if (multi) {
            b.setPositiveButton(R.string.picker_confirm, (d, which) -> {
                if (multiListener != null) {
                    multiListener.onAppsChosen(new ArrayList<>(checked));
                }
            });
        }
        dialog = b.create();

        // 弹窗关闭时释放线程与 dialog 引用，避免 Activity 被 dialog 长期持有
        dialog.setOnDismissListener(d -> release());

        // 先把弹窗显示出来，再去后台扫描应用。
        // 之前 loadApps() 在主线程同步跑（几百个 loadIcon），而 dialog.show() 写在它后面，
        // 导致点击后界面长时间无反应（Android 17 上尤其明显）。
        loadingView.setVisibility(View.VISIBLE);
        dialog.show();

        scanAppsAsync(false);
    }

    /** 仅刷新某一行的勾选框（避免整列表 notify 造成滚动跳动） */
    private void updateRowCheckbox(View row, String pkg) {
        if (row == null) {
            return;
        }
        CheckBox cb = row.findViewById(R.id.cbCheck);
        if (cb != null) {
            cb.setChecked(checked.contains(pkg));
        }
    }

    private void release() {
        iconLoader.shutdownNow();
        dialog = null;
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
                // 必须用搜索框里的当前文本重新过滤：写死空串会把用户已输入的关键字冲掉，
                // 表现为「下拉刷新一下，搜索条件莫名失效」。
                applyFilter(searchBox != null ? searchBox.getText().toString() : "");
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

    /**
     * 扫描已安装应用（必须在后台线程调用）；图标懒加载，避免一次性解码几百个图标。
     *
     * 性能要点：可启动性的判定原本是逐个 {@code pm.getLaunchIntentForPackage(...)}，
     * 那是对每个已安装应用各做一次跨进程 resolveActivity —— 装了几百个应用时就是几百次 IPC，
     * 打开选择器要白屏等好几秒。改成**一次** queryIntentActivities 把「有桌面图标的应用」
     * 整批取回再做集合判定，IPC 从 N 次降到 1 次。
     */
    private ArrayList<AppInfo> loadApps() {
        ArrayList<AppInfo> out = new ArrayList<>();
        String self = context.getPackageName();

        Set<String> launchable = new HashSet<>();
        try {
            Intent q = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> ris = pm.queryIntentActivities(q, 0);
            if (ris != null) {
                for (ResolveInfo ri : ris) {
                    if (ri.activityInfo != null && ri.activityInfo.packageName != null) {
                        launchable.add(ri.activityInfo.packageName);
                    }
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("AppListDialog", "queryIntentActivities failed", t);
        }
        // 极端情况下（个别 ROM 返回空列表）退回逐个判定，保证列表不会空掉
        final boolean batchOk = !launchable.isEmpty();

        List<ApplicationInfo> apps = pm.getInstalledApplications(0);
        for (ApplicationInfo ai : apps) {
            if (ai.packageName.equals(self)) {
                continue;
            }
            if (batchOk) {
                if (!launchable.contains(ai.packageName)) {
                    continue; // 只列可启动的应用
                }
            } else if (pm.getLaunchIntentForPackage(ai.packageName) == null) {
                continue;
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

    /** 提交一次图标解码：仅在列表静止时调用（getView / 滚动停顿回调），避免任务堆积 */
    private void submitDecode(AppInfo info) {
        info.iconLoading = true;
        final AppInfo target = info;
        try {
            iconLoader.execute(() -> {
                if (iconLoader.isShutdown()) {
                    return;
                }
                Bitmap bmp = decodeIcon(target.ai, iconSizePx);
                target.icon = bmp;
                target.iconLoading = false;
                main.post(() -> {
                    if (dialog == null || !dialog.isShowing()) {
                        return;
                    }
                    ImageView cur = listView.findViewWithTag(target.pkg);
                    if (cur != null) {
                        cur.setImageBitmap(bmp);
                    }
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // 弹窗已关闭、线程已停：图标不加载也不影响功能
            target.iconLoading = false;
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
            ViewHolder h;
            if (convertView == null) {
                convertView = LayoutInflater.from(context)
                        .inflate(R.layout.item_app, parent, false);
                h = new ViewHolder();
                h.label = convertView.findViewById(R.id.tvLabel);
                h.pkg = convertView.findViewById(R.id.tvPkg);
                h.icon = convertView.findViewById(R.id.ivIcon);
                h.check = convertView.findViewById(R.id.cbCheck);
                convertView.setTag(h);
            } else {
                h = (ViewHolder) convertView.getTag();
            }
            AppInfo info = filtered.get(i);
            h.label.setText(info.label);
            h.pkg.setText(info.pkg);
            final String key = info.pkg;
            h.icon.setTag(key);
            h.icon.setImageBitmap(info.icon);
            // 多选模式：显示勾选框并据集合绑定状态
            if (h.check != null) {
                if (multi) {
                    h.check.setVisibility(View.VISIBLE);
                    h.check.setChecked(checked.contains(key));
                } else {
                    h.check.setVisibility(View.GONE);
                }
            }
            // 后台解码图标，完成后回主线程填充（Bitmap 不可变，跨线程安全）。
            // 仅在列表静止时解码：滑动过程中不提交新任务，避免任务堆积。
            if (info.icon == null && !info.iconLoading && listIdle) {
                submitDecode(info);
            }
            return convertView;
        }
    }

    /** 行复用：避免每次 getView 都 findViewById */
    private static class ViewHolder {
        TextView label;
        TextView pkg;
        ImageView icon;
        CheckBox check;
    }
}

package com.elleng.adbswitch;

import com.elleng.adbswitch.util.DrawableUtils;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
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
 * 内含搜索框 + 可滚动列表 + 下拉刷新（标准控件 SwipeRefreshLayout，不自研手势）。
 * 下拉刷新可重新扫描已安装应用，反映新装/卸载的变化。
 *
 * 两种模式：
 *  - 单选（{@link #show}）：点列表项即选定并关闭（用于一次性选择）；
 *  - 多选（{@link #showMulti}）：每项带勾选框，预勾已选应用，确认后返回勾选集合，
 *    用于「管理快捷应用」——勾上即加入、取消即移除，一次搞定多个 app 的增删。
 */
public class AppListDialog {

    private final Context context;
    private final boolean multi;
    private final List<String> preselected;
    private final OnAppsChosenListener multiListener;
    private final PackageManager pm;

    /** 多选模式下被勾选的包名集合（保序：按勾选先后顺序） */
    private final Set<String> checked = new LinkedHashSet<>();

    private final ArrayList<AppInfo> all = new ArrayList<>();
    private final ArrayList<AppInfo> filtered = new ArrayList<>();

    private ListView listView;
    private TextView loadingView;
    private EditText searchBox;
    /** 标准下拉刷新容器（SwipeRefreshLayout）；未读取到列表时整体隐藏、露出错误提示区 */
    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipeRefresh;
    /** 未读取到应用列表时的提示区（提示授权 + 「刷新重试」按钮） */
    private View errorView;
    private AppAdapter adapter;
    private AlertDialog dialog;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int iconSizePx;
    /** 列表区占屏幕高度的比例（响应式：手机 / 平板按同一比例缩放） */
    private static final float LIST_HEIGHT_RATIO = 0.5f;
    /** 列表高度校正次数上限：防止反复布局抖动（正常 1 次就收敛） */
    private int fixAttempts;
    /** 是否已排了一次「布局后校正」任务，避免同一帧重复排队 */
    private boolean fixScheduled;
    /**
     * 图标解码的共享后台线程。
     * 原先 getView 里每行 new Thread：快速滑动会瞬间起几十上百个线程（解码图标是重活），
     * 既抢 CPU 又可能 OOM。改成一个串行线程排队解码，配合 ViewHolder 复用行即可。
     * 队列有界（64）：快速滑动时 getView 对每条绑定过的行都提交任务，无界队列会让
     * 当前盯着的行排在几十个过期任务后面。队满即拒绝（走下方 catch 复位 iconLoading），
     * 行下次绑定时重新提交，可见行不会饿死。
     */
    private final java.util.concurrent.ExecutorService iconLoader =
            new java.util.concurrent.ThreadPoolExecutor(1, 1, 0L,
                    java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<>(64));

    /** 在途扫描线程：弹窗关闭时中断，避免关闭后仍跑完并持有 Activity 数秒。 */
    private volatile Thread scanThread;
    /** 扫描进行中：下拉刷新与「刷新重试」共用入口，防止两次扫描并发、按完成顺序互相覆盖。 */
    private volatile boolean scanning;

    /** 多选模式的回调：返回最终勾选的包名集合（按勾选先后顺序，原样落盘决定联动列表展示顺序）。 */
    public interface OnAppsChosenListener {
        void onAppsChosen(List<String> pkgs);
    }

    private static class AppInfo {
        final String label;
        final String pkg;
        final ApplicationInfo ai;
        /** 预存小写副本，过滤时不再每行每次 toLowerCase（P3） */
        final String labelLower;
        final String pkgLower;
        volatile Bitmap icon;
        volatile boolean iconLoading;

        AppInfo(String label, String pkg, ApplicationInfo ai) {
            this.label = label;
            this.pkg = pkg;
            this.ai = ai;
            this.labelLower = label.toLowerCase(Locale.getDefault());
            this.pkgLower = pkg.toLowerCase(Locale.getDefault());
        }
    }

    /**
     * 图标跨会话缓存：Bitmap 解码较重，选择器反复打开时复用已解码的图标，
     * 避免每次都重新 loadIcon + 缩放。按包名索引，进程存活期间有效。
     * 静态字段 → 跨对话框实例（多次打开「管理快捷应用」）共享。
     *
     * 容量按内存预算而非条数：40dp@3x ≈ 120px ⇒ ARGB_8888 约 57KB/张，
     * 按条数计（200 条）会常驻 ~11.5MB。现 sizeOf 返回 KB、maxSize 取
     * 进程堆上限的 1/16（KB），内存账由 LruCache 自己算。
     */
    private static final android.util.LruCache<String, Bitmap> ICON_CACHE =
            new android.util.LruCache<String, Bitmap>(
                    (int) (Runtime.getRuntime().maxMemory() / 16 / 1024)) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return Math.max(1, value.getAllocationByteCount() / 1024); // KB
                }
            };

    private AppListDialog(Context context, boolean multi, List<String> preselected,
                          OnAppsChosenListener multiListener) {
        this.context = context;
        this.multi = multi;
        this.preselected = preselected;
        this.multiListener = multiListener;
        this.pm = context.getPackageManager();
        if (multi && preselected != null) {
            checked.addAll(preselected);
        }
    }

    /** 多选：预勾 {@code preselected} 中的包名，确认后回传勾选集合（用于管理联动应用）。 */
    public static void showMulti(Context context, List<String> preselected,
                                 OnAppsChosenListener listener) {
        try {
            new AppListDialog(context, true, preselected, listener).build();
        } catch (Throwable t) {
            android.util.Log.e("AppListDialog", "showMulti failed", t);
            Toast.makeText(context, R.string.picker_load_fail, Toast.LENGTH_LONG).show();
        }
    }

    private void build() {
        float density = context.getResources().getDisplayMetrics().density;
        iconSizePx = (int) (40 * density + 0.5f);

        View root = LayoutInflater.from(context).inflate(R.layout.dialog_app_picker, null);
        swipeRefresh = root.findViewById(R.id.swipeRefresh);
        // 列表区高度按「屏幕高度的比例」定，不写死 dp：手机 / 平板按同一比例缩放，
        // 大屏自然更长。弹窗里还要放标题、搜索框和底部按钮，比例过大就会顶出窗口被裁掉
        // （实测比例 0.6 时列表高 1500px、而可视区只有 1363px，最后一行被切掉）。
        // 取整到整数行，底部不会露出半行。
        int listPx = (int) (context.getResources().getDisplayMetrics().heightPixels
                * LIST_HEIGHT_RATIO);
        int initPitch = measureRowPitch(density);
        if (initPitch > 1) {
            int rows0 = listPx / initPitch;
            if (rows0 >= 2) {
                listPx = rows0 * initPitch;
            }
        }
        ViewGroup.LayoutParams listLp = swipeRefresh.getLayoutParams();
        if (listLp != null) {
            listLp.height = listPx;
            swipeRefresh.setLayoutParams(listLp);
        }
        loadingView = root.findViewById(R.id.tvLoading);
        listView = root.findViewById(R.id.lvApps);
        errorView = root.findViewById(R.id.llError);
        View retry = root.findViewById(R.id.btnRetry);
        if (retry != null) {
            retry.setOnClickListener(v -> {
                // 刷新重试：重新扫描（例如用户刚到系统设置里授权了应用列表权限）
                errorView.setVisibility(View.GONE);
                swipeRefresh.setVisibility(View.VISIBLE);
                loadingView.setVisibility(View.VISIBLE);
                scanAppsAsync();
            });
        }
        EditText et = root.findViewById(R.id.etSearch);
        searchBox = et;

        // 下拉刷新走标准控件回调，手势/进度圈由 SwipeRefreshLayout 自己管
        swipeRefresh.setOnRefreshListener(this::scanAppsAsync);

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
                updateTitle();
            }
        });

        et.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // P3：搜索 300ms 防抖，避免每敲一个字符就全量过滤一次
                scheduleFilter();
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
            }
        });

        // 多选标题右侧带上数量（如「管理快捷应用（0个）」），随勾选实时变化（见 updateTitle）
        CharSequence title = multi
                ? context.getString(R.string.picker_title_multi_count,
                        preselected == null ? 0 : preselected.size())
                : context.getString(R.string.picker_title);
        AlertDialog.Builder b = new MaterialAlertDialogBuilder(context)
                .setTitle(title)
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

        // 先显示弹窗，再后台扫描应用：扫描几百个图标耗时较长，若等扫完才 show，
        // 点击后界面会长时间无响应。
        loadingView.setVisibility(View.VISIBLE);
        dialog.show();
        // 布局完再按「真实可视高度」收拾列表：不能在此之前算，原因见 alignListAfterLayout
        alignListAfterLayout();

        scanAppsAsync();
    }

    /**
     * 列表高度兜底校正：布局完成后确认 ListView 的高度没有超出「真正能看到的高度」。
     *
     * <p>为什么要兜底：屏幕比例只是估算，小屏 / 横屏 / 分屏 / 大字体下弹窗留给列表的空间
     * 可能比估算值更小。而 SwipeRefreshLayout 会把自身的测量规格直接传给子视图
     * （measureChild），{@code match_parent} 的 ListView 因此会被量成「比可视区更高」：
     * 多出来的部分被父容器裁掉，但 ListView 自己以为还有那么高，于是滚动范围缩水，
     * 表现就是「能滚，但滚到头最后一行仍然缺一截」，怎么滑都出不来。
     * 所以布局后把 ListView 与 SwipeRefreshLayout 的高度一起钉成真实可用高度，
     * 滚动范围才与可视区域一致，滚到底最后一行才会完整贴底。
     */
    private void alignListAfterLayout() {
        if (listView == null) {
            return;
        }
        listView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                scheduleListHeightFix();
            }
        });
    }

    /** 布局期间算不出祖先高度（此刻还是 0），推迟到本次布局遍历结束再算；同一时间只排一次 */
    private void scheduleListHeightFix() {
        if (listView == null || fixScheduled) {
            return;
        }
        fixScheduled = true;
        listView.post(() -> {
            fixScheduled = false;
            fixListHeightToVisible(listView == null ? 0 : listView.getHeight());
        });
    }

    private void fixListHeightToVisible(int listHeight) {
        if (swipeRefresh == null || listView == null || listHeight <= 0) {
            return;
        }
        int avail = availableListHeight();
        if (avail <= 0 || fixAttempts >= 4) {
            return;
        }
        float density = context.getResources().getDisplayMetrics().density;
        int pitch = measureRowPitch(density);
        int target = avail;
        if (pitch > 1) {
            int rows = avail / pitch;
            if (rows >= 2) {
                target = rows * pitch;
            }
        }
        // 只有明显放不下（超出整整一行）才收：布局早期拿到的可用高度可能是瞬时值，
        // 差一行以内不动，避免把本来放得下的列表无故压矮。
        if (listHeight <= avail + (pitch > 1 ? pitch : 0)) {
            return;
        }
        fixAttempts++;
        // 放到下一帧再改，避免在 layout 过程中触发 requestLayout
        final int t = target;
        listView.post(() -> {
            if (listView == null || swipeRefresh == null) {
                return;
            }
            ViewGroup.LayoutParams llp = listView.getLayoutParams();
            if (llp != null && llp.height != t) {
                llp.height = t;
                listView.setLayoutParams(llp);
            }
            ViewGroup.LayoutParams slp = swipeRefresh.getLayoutParams();
            if (slp != null && slp.height != t) {
                slp.height = t;
                swipeRefresh.setLayoutParams(slp);
            }
        });
    }

    /**
     * 列表真正能用的高度：向上遍历祖先，取「各层容器底部到列表顶部」的最小距离。
     *
     * <p>不能只看直接父容器：AlertDialog 的自定义区内嵌了 custom → customPanel →
     * parentPanel 几层，其中内层会跟着内容一起被撑高，真正把内容裁掉的是更外层。
     * 只看父容器会算出偏大的值，等于没修。取各层最小值就不会漏掉真正裁剪的那层。
     *
     * <p>坐标统一用 getLocationInWindow，嵌套层级多也不会算偏。
     */
    private int availableListHeight() {
        if (listView == null) {
            return 0;
        }
        int[] lvLoc = new int[2];
        listView.getLocationInWindow(lvLoc);
        int lvTop = lvLoc[1];
        int best = Integer.MAX_VALUE;
        View v = listView;
        while (v != null) {
            int[] loc = new int[2];
            v.getLocationInWindow(loc);
            int space = loc[1] + v.getHeight() - lvTop;
            if (v.getHeight() > 0 && space > 0 && space < best) {
                best = space;
            }
            android.view.ViewParent p = v.getParent();
            v = (p instanceof View) ? (View) p : null;
        }
        // 再和窗口可视区域取小，避免弹窗比屏幕还高
        if (dialog != null && dialog.getWindow() != null) {
            android.graphics.Rect r = new android.graphics.Rect();
            dialog.getWindow().getDecorView().getWindowVisibleDisplayFrame(r);
            int space = r.bottom - lvTop;
            if (space > 0 && space < best) {
                best = space;
            }
        }
        if (best == Integer.MAX_VALUE || best <= 0) {
            return swipeRefresh == null ? 0 : swipeRefresh.getHeight();
        }
        return best;
    }

    /**
     * 实测一行的「行距」= 行高 + 1dp 分隔线，用于把列表视口高度对齐到整数行。
     *
     * <p>行高不是写死的：取决于 40dp 图标、两行文字、以及多选时 48dp 的勾选框谁更高
     * （+ 上下各 10dp 内边距），还会随系统字体缩放变化，所以这里真的把 item_app 量一遍，
     * 而不是用常量估算。多选模式要先把勾选框置为可见，否则量出来的是单选行的行高。
     */
    private int measureRowPitch(float density) {
        try {
            View probe = LayoutInflater.from(context)
                    .inflate(R.layout.item_app, null, false);
            CheckBox cb = probe.findViewById(R.id.cbCheck);
            if (cb != null) {
                cb.setVisibility(multi ? View.VISIBLE : View.GONE);
            }
            // 行宽：弹窗宽度（屏宽减两侧外边距）再减列表左右各 8dp 内边距，取个偏保守的值即可
            int width = context.getResources().getDisplayMetrics().widthPixels
                    - (int) (96 * density + 0.5f);
            probe.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int h = probe.getMeasuredHeight();
            if (h <= 0) {
                return 0;
            }
            return h + Math.max(1, (int) (density + 0.5f));
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 多选模式：标题数量随勾选实时更新（如「管理快捷应用（2个）」） */
    private void updateTitle() {
        if (!multi || dialog == null) {
            return;
        }
        dialog.setTitle(context.getString(R.string.picker_title_multi_count, checked.size()));
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
        main.removeCallbacks(filterTask);
        iconLoader.shutdownNow();
        Thread t = scanThread;
        if (t != null) {
            t.interrupt();
        }
        dialog = null;
    }

    /** 300ms 防抖的过滤任务：每次输入先撤掉上一次的待执行，避免连敲时重复全量过滤 */
    private final Runnable filterTask = () -> {
        if (searchBox != null) {
            applyFilter(searchBox.getText().toString());
        }
    };

    private void scheduleFilter() {
        main.removeCallbacks(filterTask);
        main.postDelayed(filterTask, 300);
    }

    /** 后台扫描已安装应用，完成后回主线程刷新列表 */
    private void scanAppsAsync() {
        if (scanning) {
            return; // 已有扫描在途：不并发，完成时统一收刷新圈
        }
        scanning = true;
        Thread t = new Thread(() -> {
            try {
                ArrayList<AppInfo> result;
                try {
                    result = loadApps();
                } catch (Throwable t2) {
                    android.util.Log.e("AppListDialog", "loadApps failed", t2);
                    result = new ArrayList<>();
                }
                final ArrayList<AppInfo> data = result;
                main.post(() -> {
                    if (dialog == null || !dialog.isShowing()) {
                        return;
                    }
                    all.clear();
                    all.addAll(data);
                    loadingView.setVisibility(View.GONE);
                    // 未读取到应用列表（扫描失败或一个应用都没读出来，常见于「应用列表」权限未授权）：
                    // 直接提示需要授权并给「刷新重试」，不再对着空列表干等。
                    boolean empty = data.isEmpty();
                    if (empty) {
                        swipeRefresh.setVisibility(View.GONE);
                        if (errorView != null) {
                            errorView.setVisibility(View.VISIBLE);
                        }
                    } else {
                        if (errorView != null) {
                            errorView.setVisibility(View.GONE);
                        }
                        swipeRefresh.setVisibility(View.VISIBLE);
                    }
                    // 必须用搜索框里的当前文本重新过滤：写死空串会把用户已输入的关键字冲掉，
                    // 表现为「下拉刷新一下，搜索条件莫名失效」。
                    applyFilter(searchBox != null ? searchBox.getText().toString() : "");
                    // 收掉标准控件的刷新进度圈（不再弹「已刷新应用列表」Toast：进度圈本身就是反馈）
                    swipeRefresh.setRefreshing(false);
                });
            } finally {
                scanning = false;
            }
        }, "app-picker-scan");
        scanThread = t;
        t.start();
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
            if (Thread.currentThread().isInterrupted()) {
                break; // 弹窗已关闭（release 中断了扫描线程）：立即止损
            }
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
        // 排序：打开弹窗前已设置的快捷应用置顶，其余按名称排后。
        // 置顶依据是打开时的快照（preselected）而不是当前勾选集合（checked）——
        // 否则用户本次弹窗里勾选/取消会实时重排行，列表跳来跳去没法点。
        final Set<String> pinned = new HashSet<>(
                preselected == null ? java.util.Collections.emptyList() : preselected);
        Collections.sort(out, (x, y) -> {
            boolean xp = pinned.contains(x.pkg);
            boolean yp = pinned.contains(y.pkg);
            if (xp != yp) {
                return xp ? -1 : 1;
            }
            return coll.compare(x.label, y.label);
        });
        return out;
    }

    /** 后台把应用图标渲染成方形 Bitmap（Drawable 不能跨线程用，Bitmap 可以） */
    private Bitmap decodeIcon(ApplicationInfo ai, int size) {
        try {
            return DrawableUtils.toBitmap(ai.loadIcon(pm), size);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 提交一次图标解码：getView 绑定行时立即调用（实时加载），由共享线程串行执行 */
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
                if (bmp != null) {
                    ICON_CACHE.put(target.pkg, bmp);
                }
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
            if (q.isEmpty() || a.labelLower.contains(q) || a.pkgLower.contains(q)) {
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
            // 先查跨会话缓存（命中即省去解码）；仅当本进程内还没解码过时才去取
            if (info.icon == null) {
                Bitmap cached = ICON_CACHE.get(key);
                if (cached != null) {
                    info.icon = cached;
                }
            }
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
            // 行一绑定就提交解码（实时加载）：不要按滚动状态延迟，
            // 否则滑动/刚打开时图标长时间空白，体验反而差。
            if (info.icon == null && !info.iconLoading) {
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

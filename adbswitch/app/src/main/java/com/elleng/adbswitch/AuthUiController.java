package com.elleng.adbswitch;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.elleng.adbswitch.util.RootUtil;
import com.elleng.adbswitch.util.ShizukuUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import rikka.shizuku.Shizuku;

/**
 * 授权 UI：探测结论落地（状态行 / kill 开关可用性 / 底部提示）、模式选择、
 * WSS 一次性授权引导（手动 adb / root 代跑 / Shizuku 代跑）、Shizuku 授权请求与重试。
 *
 * 探测本身在 AuthCoordinator（后台 + 节流 + 会话闸门），写入在 DebugController；
 * 本类只做「结论 → UI」与「引导用户补授权」这一层。
 * Shizuku 生命周期监听的注册/移除仍在 MainActivity（与 Activity 生命周期成对），
 * 结果经 check / maybeRequestShizukuPermission / onShizukuPermissionResult 回到这里。
 */
final class AuthUiController {

    private static final String TAG = "AdbSwitch";

    /** 提示条与列表末尾之间的呼吸间距（dp）：贴着条上沿仍算"被压"，留一点更自然 */
    private static final int PAD_GAP_DP = 12;

    private final MainActivity activity;

    private com.google.android.material.snackbar.Snackbar authSnackbar = null;
    /** WSS 未授权的一次性授权引导是否已自动弹过（每次会话只自动弹一次，见 check） */
    private boolean wssBootstrapShown = false;
    /** WSS 引导中「用 Shizuku 代跑」：Shizuku 授权弹窗返回结果后，是否接着跑一次 pm grant */
    private boolean pendingWssGrantAfterShizuku = false;
    /** 上次请求 Shizuku 授权的时间戳（防抖，避免请求->结果->重测->请求的循环） */
    private long lastShizukuRequestAt = 0;
    /**
     * 授权探测是否已回过结论。冷启动到「授权探测回来」之间 privilegedAvailable 仍是初始值、
     * isRootCapable() 还是 false，此时 updateKillPrefState 会算出 canKill=false，把
     * 「开启调试自动关闭快捷应用」先强制关掉并置灰，等探测回来才变回开——用户看到的就是
     * 「一进来是关的、过一会儿自己变开」。有这个标志就等结论回来再接管显示，
     * 首帧用布局声明的默认值（开）。
     */
    private boolean authProbeDone = false;

    AuthUiController(MainActivity activity) {
        this.activity = activity;
    }

    /**
     * 双模式授权检测（探测逻辑见 {@link AuthCoordinator}：模式探测 + 30s 节流 + 会话闸门）。
     * 本方法只负责把 {@link AuthCoordinator.AuthResult} 应用到 UI：刷新授权状态行 / 控件可用性，
     * 并按模式弹引导（WSS 首次进入一次性引导）/ 请求 Shizuku 授权。
     *
     * 与旧实现的三点差异（已沉淀到 AuthCoordinator）：
     *  1. **只探测当前模式对应的后端**：Shizuku 模式不再 fork su；
     *  2. **失败也节流**：失败不反复 fork su 干等；
     *  3. 探测结论区分「没有 su」与「有 su 未授权」，文案据此分流。
     */
    void check() {
        // 本实例尚无探测结论时必须 force。AuthCoordinator 的会话闸门 sCheckedThisSession 是
        // static（进程级），Activity 重建后仍为 true，而 privilegedAvailable / authProbeDone /
        // cameFromBackground 都是实例字段、已复位——若这里走非 force，探测会被闸门静默吞掉，
        // 新实例就停在陈旧的 privilegedAvailable=true + authProbeDone=false 假授权态。
        // force 绕过闸门与节流，保证冷启动与每次重建都拿到一次真实结论；onResume 的后台返回
        // 路径本来就是 force，与之保持一致。
        check(!authProbeDone);
    }

    /** @param force true=忽略会话闸门与节流，立即重测（切模式 / 点重试 / 授权结果回调） */
    void check(boolean force) {
        if (!activity.prefsLoaded) {
            return;
        }
        AuthCoordinator.check(activity, force, result -> {
            if (activity.isDestroyed() || activity.isFinishing()) {
                return; // Activity 已销毁，避免 UI/窗口泄漏
            }
            authProbeDone = true;
            activity.privilegedAvailable = result.available;
            // 确认成功才记到本启动周期：失败不缓存，下次仍会重测（授权可能稍后补上）
            if (result.available) {
                AuthCoordinator.markSessionChecked();
            }
            activity.applyRootState();
            updateAuthStatusUI();
            updateKillPrefState();
            if (!result.available) {
                // 未授权按模式给出引导。WSS 首次进入自动弹一次性授权引导（每会话一次，避免叠层）；
                // Shizuku 模式且服务在跑却没授权：主动弹 Shizuku 授权对话框。
                if (result.mode == AuthManager.Mode.SECURE_SETTINGS) {
                    // WSS 分支的 shizukuRunning 恒按 false 传（与旧实现一致：该模式不依赖 Shizuku 文案）
                    if (!wssBootstrapShown) {
                        wssBootstrapShown = true;
                        // 弹窗关闭后由默认回调恢复底部常驻「重试」提示
                        showWssBootstrapDialog();
                    } else {
                        showAuthNotice(result.mode, false, RootUtil.RootStatus.DENIED);
                    }
                } else {
                    if (result.mode == AuthManager.Mode.SHIZUKU) {
                        maybeRequestShizukuPermission(false);
                    }
                    showAuthNotice(result.mode, result.shizukuRunning, result.rootStatus);
                }
            }
        });
    }

    /** 弹出授权模式选择对话框（WSS 推荐 / root / Shizuku），选中后持久化并立即重测 */
    void chooseMode() {
        final AuthManager.Mode cur = AuthManager.getMode(activity);
        final int checked = cur == AuthManager.Mode.ROOT ? 1
                : cur == AuthManager.Mode.SHIZUKU ? 2 : 0;
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.auth_choose_title)
                .setSingleChoiceItems(
                        new CharSequence[]{activity.getString(R.string.auth_mode_wss),
                                activity.getString(R.string.auth_mode_root),
                                activity.getString(R.string.auth_mode_shizuku)},
                        checked,
                        (d, which) -> {
                            AuthManager.Mode mode = which == 1 ? AuthManager.Mode.ROOT
                                    : which == 2 ? AuthManager.Mode.SHIZUKU
                                    : AuthManager.Mode.SECURE_SETTINGS;
                            AuthManager.setMode(activity, mode);
                            // 执行通道变了（WSS 直写 ↔ su ↔ Shizuku），旧状态缓存作废并立即用新通道重新校准：
                            // 不作废的话，切换后 UI 仍按旧缓存渲染，且校准可能被节流挡住导致卡在旧值。
                            DebugController.invalidateAndRefresh(activity);
                            // 切换模式后授权状态必变，重置缓存、强制重新检测
                            AuthCoordinator.resetSession();
                            // 旧通道的「杀应用能力」结论对新通道无意义（root 与 Shizuku 是否授权各自独立）
                            DebugController.clearKillCapability(activity);
                            // 重新武装「WSS 未授权自动引导」：切到 WSS 时应当再自动弹一次
                            wssBootstrapShown = false;
                            d.dismiss();
                            updateAuthStatusUI();
                            if (mode == AuthManager.Mode.SECURE_SETTINGS) {
                                // 选了推荐通道：还没授权就给一次性引导；已授权则按新通道重测
                                if (!AuthManager.hasWriteSecureSettings(activity)) {
                                    showWssBootstrapDialog();
                                } else {
                                    check(true);
                                }
                                return;
                            }
                            // 切到 root / Shizuku：Shizuku 模式先主动请求授权（force，用户是刚明确切过来的）
                            maybeRequestShizukuPermission(true);
                            // force：用户刚切了模式，旧探测结论作废，必须立刻按新通道重测
                            check(true);
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * 写入失败的统一收口（主 / 子开关三条回调共用）。
     *
     * 免root模式尚未授权（REASON_NEED_GRANT）时不弹笼统 Toast，直接打开一次性授权引导 ——
     * 用户缺的是一次 pm grant，既不是 root 也不是 Shizuku，别把人的注意力带偏
     * （磁贴 / 桌面快捷方式等无界面入口走 DebugController.failureMessage 的对应文案）。
     */
    void handleWriteFailure(int reason) {
        if (reason == RootUtil.REASON_NEED_GRANT) {
            showWssBootstrapDialog();
            return;
        }
        Toast.makeText(activity, DebugController.failureMessage(activity, reason),
                Toast.LENGTH_SHORT).show();
        // 授权类失败（没有 su / su 被拒 / Shizuku 没起 / Shizuku 没授权）：说明之前探测到的
        // 「已授权」已经失效（后台被撤销、root 被关掉、Shizuku 被杀）。此时只弹一句 Toast 不够——
        // 「授权模式」那一行仍显示已授权、底部也没有常驻重试入口，用户不知道要去重新授权。
        // 强制重测一次，让状态行变红并恢复底部「重试」提示，与真实授权态对齐。
        if (reason == RootUtil.REASON_NO_SU || reason == RootUtil.REASON_DENIED
                || reason == RootUtil.REASON_NO_BINDER
                || reason == RootUtil.REASON_NO_PERMISSION) {
            AuthCoordinator.resetSession();
            check(true);
        }
    }

    /**
     * WRITE_SECURE_SETTINGS 一次性授权引导（三种等价方式，实现都在 AuthManager）：
     *   · 手动 adb   —— 用户复制命令到电脑执行一次（本文只负责复制）
     *   · Shizuku 代跑 —— 本应用借 Shizuku 的 shell(uid 2000) 跑一次 pm grant
     *   · root 代跑   —— 本应用借 su 跑一次 pm grant
     * 授权粘滞：跨重启 / 软重启保留，仅卸载重装会撤销，故无需重复引导。
     */
    private void showWssBootstrapDialog() {
        // 默认在弹窗关闭后恢复底部常驻「重试」提示（仍处于未授权时）。
        // 1.0.4 的 bug：旧 no-arg 版本 onDismiss=null，用户从「重试」重开弹窗后再次关闭，
        // 既不弹新提示、也没有任何恢复入口，界面彻底静默。改为统一挂恢复回调后，
        // 「关弹窗 → 出现常驻重试提示 → 点重试 → 重开弹窗」形成闭环，直到授权成功才消失。
        showWssBootstrapDialog(this::restoreAuthNoticeIfStillUnauthorized);
    }

    /**
     * 一次性授权引导弹窗关闭后，若仍处于「WSS 未授权」：恢复底部常驻提示（带「重试」）。
     * 任何弹窗关闭路径（取消 / 代跑失败 / 从重试重开）都走这里，保证用户始终有恢复入口。
     * 已授权 / 非 WSS 模式不做事——授权成功后由 {@link #updateAuthStatusUI()} 收起提示。
     */
    private void restoreAuthNoticeIfStillUnauthorized() {
        if (activity.isDestroyed() || activity.isFinishing()) {
            return;
        }
        if (AuthManager.getMode(activity) == AuthManager.Mode.SECURE_SETTINGS
                && !AuthManager.hasWriteSecureSettings(activity)) {
            showAuthNotice(AuthManager.Mode.SECURE_SETTINGS, false, RootUtil.RootStatus.DENIED);
        }
    }

    /**
     * 弹出一次性授权引导弹窗。
     * @param onDismiss 弹窗消失后的回调（用于 §4.4：弹窗优先，消失后再显示底部 Snackbar 常驻入口）；
     *                  为 null 时（如代跑失败重显）不额外做事。
     */
    private void showWssBootstrapDialog(Runnable onDismiss) {
        final String cmd = AuthManager.grantCommand(activity);
        android.view.View v = activity.getLayoutInflater()
                .inflate(R.layout.dialog_auth_bootstrap, null);
        ((TextView) v.findViewById(R.id.wss_body)).setText(R.string.auth_bootstrap_body);
        ((TextView) v.findViewById(R.id.wss_cmd)).setText(cmd);

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.auth_bootstrap_title)
                .setView(v)
                .create();

        // 四个动作必须是真实 Button：此前用 setItems 配合 setMessage 时，列表项不呈现为可点条目
        // （观感是纯文字、点击无回调），且 AlertDialog 最多只支持三个按钮，放不下这四个动作。
        v.findViewById(R.id.btn_wss_root).setOnClickListener(x -> {
            dialog.dismiss();
            runGrantAsync(false);       // 借 root 跑一次 pm grant
        });
        final View btnShizuku = v.findViewById(R.id.btn_wss_shizuku);
        btnShizuku.setOnClickListener(x -> {
            dialog.dismiss();
            // 走三段式：未获 Shizuku 授权时先弹授权框，授权回来再代跑 pm grant
            grantWssViaShizuku();
        });
        // 按设备实际能力置灰：Shizuku 服务没在跑时，「用 Shizuku 代跑」点了也只会拿到一句
        // 「请先启动 Shizuku」——不如直接置灰，把注意力留给可用的两条路（手动 adb / root）。
        // pingBinder 是本地 JNI 探活、不发 IPC，主线程调用安全。
        // root 按钮不预探测：RootUtil.probe() 是一次真实 su fork，未授权设备上会弹 Magisk
        // 授权框——只是「看看选项」不该被弹窗打扰；它点了失败的后果由 grant 失败重显弹窗兜住。
        if (!ShizukuUtil.running()) {
            btnShizuku.setEnabled(false);
        }
        v.findViewById(R.id.btn_wss_copy).setOnClickListener(x -> copyToClipboard(cmd));
        v.findViewById(R.id.btn_wss_cancel).setOnClickListener(x -> dialog.dismiss());
        if (onDismiss != null) {
            dialog.setOnDismissListener(d -> onDismiss.run());
        }
        dialog.show();
    }

    private void copyToClipboard(String text) {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("pm grant", text));
                Toast.makeText(activity, R.string.auth_copy_done, Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "copy grant command failed", t);
        }
    }

    /**
     * 借 Shizuku / root 代跑一次 pm grant（后台线程：pm grant 是一次阻塞的 shell 调用）。
     * 成功后立刻按新状态重测 —— 授权刚拿到，旧探测结论已作废。
     */
    private void runGrantAsync(boolean viaShizuku) {
        new Thread(() -> {
            final boolean ok = viaShizuku
                    ? AuthManager.grantViaShizuku(activity.getApplicationContext())
                    : AuthManager.grantViaRoot(activity.getApplicationContext());
            new Handler(Looper.getMainLooper()).post(() -> {
                if (activity.isDestroyed() || activity.isFinishing()) {
                    return;
                }
                Toast.makeText(activity,
                        ok ? R.string.auth_grant_ok : R.string.auth_grant_fail,
                        Toast.LENGTH_SHORT).show();
                if (!ok) {
                    // 代跑失败（没有 su / Shizuku 没起来 / 被拒）：引导弹窗已关，只剩一个 Toast，
                    // 用户没有补救入口——直接重显引导弹窗，换一条路或取消。
                    showWssBootstrapDialog();
                }
                AuthCoordinator.resetSession();
                DebugController.invalidateAndRefresh(activity);
                check(true);
                updateAuthStatusUI();
            });
        }, "pm-grant").start();
    }

    /** 渲染合并的「授权模式」行（标题=授权模式，副标题直接显示「root模式 · 未授权」等）
     *  规则：未授权 → 摘要整段改红字作警告（不挂圆形状态图标）；已授权 → 还原默认样式，
     *  并关掉底部未授权的 Snackbar。 */
    void updateAuthStatusUI() {
        if (activity.authPref == null) {
            return;
        }
        AuthManager.Mode mode = AuthManager.getMode(activity);
        boolean ok = activity.privilegedAvailable;
        int statusRes;
        if (mode == AuthManager.Mode.SECURE_SETTINGS) {
            statusRes = ok ? R.string.auth_status_wss_ok : R.string.auth_status_wss_no;
        } else if (mode == AuthManager.Mode.SHIZUKU) {
            statusRes = ok ? R.string.auth_status_shizuku_ok : R.string.auth_status_shizuku_no;
        } else {
            statusRes = ok ? R.string.auth_status_root_ok : R.string.auth_status_root_no;
        }

        if (ok) {
            // 已授权：还原默认样式，不单独变色、不挂图标
            activity.authPref.setSummary(statusRes);
            // 授权成功后关掉底部未授权提示（含「重试」那条）
            dismissAuthSnackbar();
        } else {
            // 未授权：整段摘要改红字警告（行首不加圆形指示图标）
            SpannableString sp = new SpannableString(activity.getString(statusRes));
            sp.setSpan(new ForegroundColorSpan(
                            activity.getResources().getColor(R.color.error, activity.getTheme())),
                    0, sp.length(), SpannableString.SPAN_INCLUSIVE_INCLUSIVE);
            activity.authPref.setSummary(sp);
        }
        updateKillPrefState();
    }

    /**
     * 「开启调试自动关闭快捷应用」开关的可用性判定与展示（§3.3 + 1.0.4 修正）：
     * 能否真正关闭其他应用 = 「可写调试开关」(privilegedAvailable) 且「可杀进程（Shizuku 或 root）」。
     * 体验（按用户澄清的口径）：
     *  - 有 kill 权限：默认开启；仅当用户此前手动关闭过（{@code killUserDisabled}）才保持关闭。
     *    免 root 模式下若同时具备 root/Shizuku 授权，同样可以关闭——旧文案错写成「免 root 模式不行」。
     *  - 无 kill 权限（纯免 root 且 root/Shizuku 皆不可用）：直接关掉并置灰，文案说明原因；
     *    显示值由本方法接管，不写用户偏好，以便权限回来后恢复默认开。
     */
    void updateKillPrefState() {
        if (activity.swKillPref == null) {
            return;
        }
        // 授权结论还没回来：按用户持久偏好摆显示值，而不是无条件保持布局默认（开）。
        // 重建后 killUserDisabled 已从 cfg 读回，若用户此前手动关过（true），
        // 这里必须显示关——否则探测回来前会短暂出现「显示开、实际 cfg 是关」的错位。
        // 可用性（enabled / 置灰）仍等探测结论，避免按陈旧权限误判。
        if (!authProbeDone) {
            activity.swKillPref.setChecked(!activity.killUserDisabled);
            return;
        }
        boolean shizukuOk = ShizukuUtil.available();
        boolean canKill = activity.privilegedAvailable
                && (shizukuOk || DebugController.isRootCapable());
        if (canKill) {
            activity.swKillPref.setEnabled(true);
            boolean wantOn = !activity.killUserDisabled;
            activity.swKillPref.setChecked(wantOn);
            // 显示值必须与实际执行值一致：DebugController 只认 kill_before_open。
            // 若历史数据残留 false 而界面显示「开」，就会出现「开关开着却不杀应用」的错位
            // （控件 persistent=false 不会自己落盘，只能在这里显式对齐）。
            SharedPreferences sp = activity.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
            if (sp.getBoolean(MainActivity.PREF_KILL_BEFORE_OPEN, true) != wantOn) {
                sp.edit().putBoolean(MainActivity.PREF_KILL_BEFORE_OPEN, wantOn).apply();
            }
            // 免 root 模式不 fork su 探测（避免弹 Magisk 授权框），root 侧能力靠「上次真实执行结果」
            // 判定：上次被拒过就在这里明说，而不是继续显示一切正常、实际静默失败。
            // 开关保持可拨：用户补好授权后下次开调试会重试，成功即自动恢复为正常文案。
            boolean rootDenied = !shizukuOk
                    && DebugController.killCapability(activity) == DebugController.KILL_CAP_DENIED;
            activity.swKillPref.setSummary(rootDenied
                    ? R.string.kill_before_open_sum_last_failed
                    : R.string.kill_before_open_sum);
        } else {
            activity.swKillPref.setEnabled(false);
            activity.swKillPref.setChecked(false);
            activity.swKillPref.setSummary(R.string.kill_before_open_sum_nocap);
        }
    }

    /** 关掉底部未授权提示（带「重试」的那条） */
    private void dismissAuthSnackbar() {
        if (authSnackbar != null) {
            authSnackbar.dismiss();
            authSnackbar = null;
        }
    }

    /**
     * WSS 引导里的「用 Shizuku 代跑」：借 Shizuku 的 shell(uid 2000) 跑一次 pm grant。
     *
     * 三段式，缺一不可：
     *  1) Shizuku 服务没起 → 引导用户去 Shizuku 里启动（此时没人能代跑）；
     *  2) 服务在、但本应用**还没被 Shizuku 授权** → 必须先弹 Shizuku 授权框
     *     （复用既有的 requestShizukuPermissionRaw）；授权结果回到 shizukuPermListener 后，
     *     由它再触发真正的 pm grant；
     *  3) 已授权 → 直接跑 pm grant。
     *
     * ⚠️ 不能只调 AuthManager.grantViaShizuku()：它原先以 ShizukuUtil.available()
     * （= 服务在跑 && 已授权）为前提，而用户点这个按钮的原因恰恰是「还没授权」，
     * 条件恒为 false → 静默返回、点了没反应。这是本按钮点击无反应的根因之一。
     */
    private void grantWssViaShizuku() {
        if (!ShizukuUtil.running()) {
            Toast.makeText(activity, R.string.auth_need_start, Toast.LENGTH_LONG).show();
            return;
        }
        if (!ShizukuUtil.permissionGranted()) {
            pendingWssGrantAfterShizuku = true;
            requestShizukuPermissionRaw(true);
            return; // 结果由 shizukuPermListener 收回后再跑 pm grant
        }
        runGrantAsync(true);
    }

    /**
     * Shizuku 模式且尚未授权时，向 Shizuku 管理器请求授权（弹出 Shizuku 授权对话框）。
     *
     * 触发时机：① Shizuku 服务就绪（binder 到达）；② 切到 Shizuku 模式；③ Shizuku 模式点「重试」。
     * 前置条件：Shizuku 已在运行（pingBinder）—— 服务没起来时请求无意义，Shizuku 会直接拒绝、也不弹窗。
     *
     * @param force true=忽略防抖立即请求（用户主动动作：切模式 / 点重试）；
     *              false=走 5s 防抖。防抖是必要的：授权结果回调会再次触发 check，
     *              若未授权就无防抖地再次请求，会形成「请求 -> 结果 -> 重测 -> 请求」的循环。
     */
    void maybeRequestShizukuPermission(boolean force) {
        if (AuthManager.getMode(activity) != AuthManager.Mode.SHIZUKU) {
            return;
        }
        requestShizukuPermissionRaw(force);
    }

    /**
     * Shizuku 授权弹窗结果回到 MainActivity 的监听器后转入这里：
     * WSS 引导里「用 Shizuku 代跑」只有拿到授权才接着跑 pm grant，
     * 否则清掉挂起标记（用户拒绝 → 就此结束，不要残留状态下次误触发）。
     */
    void onShizukuPermissionResult(boolean granted) {
        if (pendingWssGrantAfterShizuku) {
            pendingWssGrantAfterShizuku = false;
            if (granted) {
                runGrantAsync(true);
            }
        }
    }

    /**
     * 真正发起 Shizuku 授权请求（**不带模式门槛**）。
     *
     * 抽这一层的缘由：WSS 引导里的「用 Shizuku 代跑」同样需要弹出 Shizuku 授权框，
     * 但那时当前授权模式是 WSS 而不是 Shizuku；若直接走 maybeRequestShizukuPermission，
     * 会被它开头「模式必须是 Shizuku」的判定挡掉 —— 表现就是点了没反应。
     */
    private void requestShizukuPermissionRaw(boolean force) {
        if (!ShizukuUtil.running()) {
            android.util.Log.i(TAG, "Shizuku binder not alive, skip permission request");
            return;
        }
        if (ShizukuUtil.permissionGranted()) {
            return;
        }
        // 官方权限三分支里的第三支（Shizuku-API README 示例用 shouldShowRequestPermissionRationale
        // 区分「还能请求」与「用户选过拒绝且不再询问」）。
        //
        // ⚠️ 这个标志的语义两侧并不一致：官方 README 示例把它注成
        // "Users choose 'Deny and don't ask again'"，而 Android 原生同名 API 的语义是
        // 「应该向用户解释、但仍可再次请求」。服务端到底按哪种填无法从客户端确认，
        // 因此这里**不据此阻断请求** —— 阻断的风险是：若实际是原生语义，
        // 用户只是拒绝过一次，就会被永久挡在授权弹窗之外，点「重试」毫无反应。
        // 折中做法：请求照发（永久拒绝时它自然不会弹窗），另外给用户一条自救指引，
        // 且只在用户主动动作（force）时提示，避免每次进页面都弹 Toast 扰民。
        boolean deniedBefore = false;
        try {
            deniedBefore = Shizuku.shouldShowRequestPermissionRationale();
        } catch (Throwable t) {
            // 老版本 Shizuku 没有这个查询：按「可再次请求」处理，退回原逻辑
            android.util.Log.w(TAG, "shouldShowRequestPermissionRationale unavailable", t);
        }
        if (deniedBefore) {
            android.util.Log.i(TAG, "Shizuku permission rationale set, showing manual guide");
            if (force) {
                Toast.makeText(activity, R.string.toast_shizuku_denied_forever, Toast.LENGTH_LONG).show();
            }
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (!force && now - lastShizukuRequestAt < 5000) {
            return;
        }
        lastShizukuRequestAt = now;
        android.util.Log.i(TAG, "requesting Shizuku permission (force=" + force + ")");
        try {
            Shizuku.requestPermission(AuthManager.SHIZUKU_PERM_REQUEST);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "requestPermission failed", t);
        }
    }

    /** 未授权时「重试」：root 模式直接重测；Shizuku 模式先确认服务在跑，未授权则弹授权请求 */
    private void onAuthRetry() {
        AuthManager.Mode mode = AuthManager.getMode(activity);
        if (mode == AuthManager.Mode.SECURE_SETTINGS) {
            // WSS 模式缺的是那一次性的 pm grant：重试 = 重新打开引导弹窗。
            // 不能只走 check——它的自动弹窗有「每会话一次」的闸门
            // （wssBootstrapShown），用户手动点「重试」会被静默挡成一句红字 Snackbar。
            if (!AuthManager.hasWriteSecureSettings(activity)) {
                showWssBootstrapDialog();
                return;
            }
            check(true);
            return;
        }
        if (mode == AuthManager.Mode.SHIZUKU) {
            if (!ShizukuUtil.running()) {
                Toast.makeText(activity, R.string.auth_need_start, Toast.LENGTH_LONG).show();
                return;
            }
            if (!ShizukuUtil.permissionGranted()) {
                // force=true：用户明确点了「重试」，必须立即弹授权
                maybeRequestShizukuPermission(true);
                return; // 结果由 OnRequestPermissionResultListener 收后再重测
            }
        }
        // force=true：重试是明确的用户动作，不能被「本周期已确认」或 30s 节流挡住
        // （旧实现走无参版本，一旦 authCheckedThisSession 还是 true 就会静默 return，点了没反应）
        check(true);
    }

    /**
     * 底部未授权提示（原生 Snackbar，带「重试」）。
     *
     * 文案严格绑定当前授权模式 —— 底部提示与「授权模式」那一行是同一件事的两种呈现，
     * 不能出现「选了 root 模式却提示 Shizuku 已授权」这种错位引导（旧实现的 bug：
     * 它按双检测结果挑「哪个后端可用」来提示，跟用户选的模式无关）。
     * Shizuku 模式下再细分「服务没启动」与「已启动但没授权」，否则只说"未授权"用户无从下手；
     * root 模式下细分「根本没有 su」与「有 su 但未授权」——前者换模式才有意义，
     * 后者才该去 Magisk 点允许。
     * WSS 模式下缺的既不是 root 也不是 Shizuku，而是那**一次性的 pm grant** ——
     * 必须给独立文案（旧实现会落进 root 分支，把用户往 Magisk 那边带偏）。
     */
    private void showAuthNotice(AuthManager.Mode mode, boolean shizukuRunning,
                                RootUtil.RootStatus rootStatus) {
        final int textRes;
        if (mode == AuthManager.Mode.SECURE_SETTINGS) {
            textRes = R.string.toast_wss_no_perm;
        } else if (mode == AuthManager.Mode.SHIZUKU) {
            textRes = shizukuRunning ? R.string.auth_toast_shizuku_no : R.string.auth_need_start;
        } else if (rootStatus == RootUtil.RootStatus.NO_SU) {
            textRes = R.string.toast_no_su;
        } else {
            textRes = R.string.auth_toast_root_no;
        }
        activity.runOnUiThread(() -> showAuthSnackbar(activity.getString(textRes), true));
    }

    /** 真正的 Snackbar：M3 默认反色样式（浅色模式深底 / 深色模式浅底），未授权文字标红作警告 */
    private void showAuthSnackbar(String text, boolean unauthorized) {
        try {
            View root = activity.getWindow().getDecorView().findViewById(android.R.id.content);
            if (root == null) {
                // 兜底：拿不到内容视图时退化为普通 Toast
                Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
                return;
            }
            // 同一时刻只保留一个提示
            if (authSnackbar != null) {
                authSnackbar.dismiss();
                authSnackbar = null;
            }
            Snackbar sb = Snackbar.make(root, text,
                    unauthorized ? Snackbar.LENGTH_INDEFINITE : Snackbar.LENGTH_LONG);
            View v = sb.getView();
            TextView tv = v.findViewById(com.google.android.material.R.id.snackbar_text);
            if (tv != null && unauthorized) {
                // 未授权红字警告。条底恒为反色 surface（浅色模式=深条 / 深色模式=浅条），
                // 必须用按模式反转的 error 档 errorInverse（深条→浅红 / 浅条→深红）；
                // 若用当前模式的 error 档，明暗正好与条底同侧、对比度不足。
                // 已授权提示不改文字色，走 M3 默认 colorOnSurfaceInverse（两种模式都正确）。
                tv.setTextColor(activity.getResources().getColor(R.color.errorInverse, activity.getTheme()));
            }
            if (unauthorized) {
                // 重试按钮与红字同色系：同样必须用反色档（硬编码白色在深色模式浅底条上看不清）
                sb.setActionTextColor(activity.getResources().getColor(R.color.errorInverse, activity.getTheme()));
                sb.setAction(R.string.auth_retry, x -> onAuthRetry());
            }
            authSnackbar = sb;
            sb.addCallback(new Snackbar.Callback() {
                @Override
                public void onShown(Snackbar s) {
                    // 持久条会常驻压住列表最下方两行（授权模式 / 关于），
                    // 显示后把它的实际高度让给列表做底部内边距
                    applyListPadForSnackbar(s, unauthorized);
                }

                @Override
                public void onDismissed(Snackbar transientBottomBar, int event) {
                    if (authSnackbar == transientBottomBar) {
                        authSnackbar = null;
                    }
                    // 被别人替换时（旧条淡出晚于新条显示）不能清占位，否则新条又压住末尾两行
                    if (authSnackbar == null && !activity.isDestroyed() && !activity.isFinishing()) {
                        activity.clearListBottomOverlay();
                    }
                }
            });
            sb.show();
        } catch (Throwable t) {
            // Snackbar 在个别主题/ROM 下可能 inflate 失败：绝不因此闪退，降级为 Toast
            android.util.Log.w(TAG, "Snackbar show failed, fallback to Toast", t);
            Toast.makeText(activity, text, unauthorized ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 让列表为持久提示条让出底部空间（见 SettingsFragment#reserveBottomForOverlay）。
     *
     * 只对持久条（未授权、LENGTH_INDEFINITE）生效：短提示 4 秒后自己消失，
     * 为它抬一次列表会让内容上下抖一下，得不偿失。
     *
     * 传的是提示条**顶边的窗口坐标**而非高度：Snackbar 自己会加 bottomMargin 让开导航栏，
     * 而列表内边距里已含同一份 inset，用高度相加会把导航栏算两遍。
     */
    private void applyListPadForSnackbar(Snackbar sb, boolean persistent) {
        if (!persistent) {
            return;
        }
        View v = sb.getView();
        if (v == null) {
            return;
        }
        if (v.getHeight() <= 0) {
            // 极端时序下 onShown 时还没量出高度：等一次布局再算（一次性监听，用完即摘）
            v.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View view, int l, int t, int r, int b,
                                           int ol, int ot, int or, int ob) {
                    view.removeOnLayoutChangeListener(this);
                    applyListPadForSnackbar(sb, true);
                }
            });
            return;
        }
        int[] loc = new int[2];
        v.getLocationInWindow(loc);
        int gap = Math.round(PAD_GAP_DP * activity.getResources().getDisplayMetrics().density);
        activity.reserveListBottomForOverlay(loc[1], gap);
    }
}
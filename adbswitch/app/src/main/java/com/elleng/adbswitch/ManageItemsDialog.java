package com.elleng.adbswitch;

import android.app.Activity;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「管理开关项」弹窗：配置每项是否显示、是否跟随主开关、是否加入桌面快捷菜单，拖动柄排序。
 * 确认时落盘 SwitchConfig（显示 / 跟随 / 快捷菜单 / 顺序）并标记快捷方式待重建，
 * 界面重建由调用方经 onApplied 回调完成（弹窗不持有主界面刷新逻辑）。
 *
 * 「快捷打开」开启时沿用旧行为——桌面长按菜单不发布子开关，此时快捷菜单勾选项
 * 对用户隐藏（勾了也不生效，不如不显示），但原始值仍随弹窗确认一起保留。
 *
 * 快捷菜单勾选项还受桌面可见槽位限制：长按菜单实测最多显示 4 条（主开关 1 条 +
 * 启动应用 0/1 条 + 子开关），达到上限后其余未勾选项置灰，取消一项即自动恢复可选，
 * 避免用户勾了却看不到、误以为已生效。
 */
public final class ManageItemsDialog {

    private ManageItemsDialog() {
    }

    /** @param onApplied 确认落盘后的主界面重建回调（主线程） */
    public static void show(Activity activity, Runnable onApplied) {
        boolean linkage = activity.getSharedPreferences("cfg", Context.MODE_PRIVATE)
                .getBoolean("linkage_enabled", false);
        String pkg = LinkagePrefs.getDefault(activity);
        boolean launchInMenu = SwitchConfig.isLaunchInMenu(activity);
        final Adapter adapter = new Adapter(activity, linkage, pkg, launchInMenu);
        final RecyclerView rv = new RecyclerView(activity);
        rv.setLayoutManager(new LinearLayoutManager(activity));
        rv.setAdapter(adapter);
        final ItemTouchHelper touchHelper = new ItemTouchHelper(
                new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
                    @Override
                    public boolean onMove(RecyclerView recyclerView,
                                          RecyclerView.ViewHolder viewHolder,
                                          RecyclerView.ViewHolder target) {
                        adapter.move(viewHolder.getAdapterPosition(), target.getAdapterPosition());
                        return true;
                    }

                    @Override
                    public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
                    }

                    @Override
                    public boolean isLongPressDragEnabled() {
                        return false; // 只用右侧拖动柄拖；整行长按不触发，避免误触
                    }
                });
        touchHelper.attachToRecyclerView(rv);
        adapter.dragStarter = touchHelper;

        int pad = (int) (20 * activity.getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad / 2, pad, 0);
        root.addView(rv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.manage_items_title)
                .setView(root)
                .setPositiveButton(R.string.picker_confirm, (d, w) -> {
                    for (SwitchItem item : SwitchItem.values()) {
                        SwitchConfig.setShown(activity, item,
                                Boolean.TRUE.equals(adapter.shownMap.get(item)));
                        SwitchConfig.setFollowMaster(activity, item,
                                Boolean.TRUE.equals(adapter.followMap.get(item)));
                        SwitchConfig.setShortcut(activity, item,
                                Boolean.TRUE.equals(adapter.shortcutMap.get(item)));
                    }
                    SwitchConfig.setOrder(activity, adapter.order);
                    ToggleShortcuts.markDirty(activity);
                    if (onApplied != null) {
                        onApplied.run();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 弹窗行适配器：两行（显示勾选框+标题 / 缩进的跟随与快捷菜单）+ 右侧拖动柄 */
    private static final class Adapter extends RecyclerView.Adapter<Adapter.H> {
        final Context ctx;
        /** 快捷打开开启时隐藏快捷菜单勾选项（该模式下不发布子开关） */
        final boolean linkage;
        final List<SwitchItem> order = new ArrayList<>();
        final Map<SwitchItem, Boolean> shownMap = new HashMap<>();
        final Map<SwitchItem, Boolean> followMap = new HashMap<>();
        final Map<SwitchItem, Boolean> shortcutMap = new HashMap<>();
        /** 长按菜单可容纳的子开关数：主开关占 1 条，显示默认快捷应用再占 1 条 */
        final String pkg;
        int maxShortcutItems;
        boolean shortcutFull;
        ItemTouchHelper dragStarter;

        Adapter(Context ctx, boolean linkage, String pkg, boolean launchInMenu) {
            this.ctx = ctx;
            this.linkage = linkage;
            this.pkg = pkg;
            order.addAll(SwitchConfig.getOrder(ctx));
            for (SwitchItem it : SwitchItem.values()) {
                shownMap.put(it, SwitchConfig.isShown(ctx, it));
                followMap.put(it, SwitchConfig.rawFollow(ctx, it));
                shortcutMap.put(it, SwitchConfig.rawShortcut(ctx, it));
            }
            updateSlotLimit(launchInMenu);
        }

        /** 按当前默认快捷应用设置计算槽位 */
        void updateSlotLimit(boolean launchInMenu) {
            boolean occupies = launchInMenu && pkg != null && !pkg.isEmpty();
            maxShortcutItems = 4 - 1 - (occupies ? 1 : 0);
            refreshShortcutFull();
        }

        /** 只统计真正会发布的项（隐藏项保留的原值不占槽位） */
        void refreshShortcutFull() {
            int n = 0;
            for (SwitchItem it : SwitchItem.values()) {
                if (it.supported()
                        && Boolean.TRUE.equals(shownMap.get(it))
                        && Boolean.TRUE.equals(shortcutMap.get(it))) {
                    n++;
                }
            }
            shortcutFull = n >= maxShortcutItems;
        }

        static final class H extends RecyclerView.ViewHolder {
            final TextView title;
            final View headRow;
            final CheckBox shown;
            final View followSlot;
            final CheckBox follow;
            final View shortcutSlot;
            final CheckBox shortcut;
            final ImageView handle;

            H(View v) {
                super(v);
                title = v.findViewById(R.id.manage_title);
                headRow = v.findViewById(R.id.manage_head_row);
                shown = v.findViewById(R.id.manage_shown_box);
                followSlot = v.findViewById(R.id.manage_follow_slot);
                follow = v.findViewById(R.id.manage_follow_box);
                shortcutSlot = v.findViewById(R.id.manage_shortcut_slot);
                shortcut = v.findViewById(R.id.manage_shortcut_box);
                handle = v.findViewById(R.id.manage_drag);
            }
        }

        @Override
        public H onCreateViewHolder(ViewGroup parent, int viewType) {
            return new H(android.view.LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.manage_item_row, parent, false));
        }

        @Override
        public void onBindViewHolder(H h, int position) {
            final SwitchItem item = order.get(position);
            h.title.setText(itemTitle(ctx, item));
            // 摘掉旧监听再摆状态，避免 RecyclerView 复用时误触发
            h.shown.setOnCheckedChangeListener(null);
            h.follow.setOnCheckedChangeListener(null);
            h.shortcut.setOnCheckedChangeListener(null);
            boolean shown = Boolean.TRUE.equals(shownMap.get(item));
            h.shown.setChecked(shown);
            h.follow.setChecked(Boolean.TRUE.equals(followMap.get(item)));
            h.shortcut.setChecked(Boolean.TRUE.equals(shortcutMap.get(item)));
            h.shown.setEnabled(item.supported());
            // 点标题或首行空白 = 点勾选框，扩大触发面积
            h.headRow.setOnClickListener(v -> {
                if (item.supported()) {
                    h.shown.toggle();
                }
            });
            boolean shortcutChecked = Boolean.TRUE.equals(shortcutMap.get(item));
            // 「跟随」「快捷菜单」只在「显示」勾上时可配（隐藏 = 功能关闭，不存在从属项独立生效）。
            // 快捷菜单达上限后，未勾选项置灰；已勾选的仍可点掉以释放槽位。
            h.follow.setEnabled(item.supported() && shown);
            h.shortcut.setVisibility(linkage ? View.GONE : View.VISIBLE);
            h.shortcut.setEnabled(item.supported() && shown
                    && (shortcutChecked || !shortcutFull));
            h.shown.setOnCheckedChangeListener((b, on) -> {
                shownMap.put(item, on);
                // 隐藏只置灰从属框，不改它们的值：重新勾上显示时原设置自然恢复
                h.follow.setEnabled(on && item.supported());
                h.shortcut.setEnabled(on && item.supported()
                        && (Boolean.TRUE.equals(shortcutMap.get(item)) || !shortcutFull));
                // 隐藏一个已勾选的子开关会释放槽位，其余项需即时恢复可选
                refreshShortcutFull();
                notifyDataSetChanged();
            });
            h.follow.setOnCheckedChangeListener((b, on) -> followMap.put(item, on));
            h.shortcut.setOnCheckedChangeListener((b, on) -> {
                shortcutMap.put(item, on);
                refreshShortcutFull();
                notifyDataSetChanged();
            });
            // 点半行空白处等同点复选框；禁用/隐藏时不响应
            h.followSlot.setOnClickListener(v -> {
                if (h.follow.isEnabled()) {
                    h.follow.toggle();
                }
            });
            h.shortcutSlot.setOnClickListener(v -> {
                if (h.shortcut.isEnabled() && h.shortcut.getVisibility() == View.VISIBLE) {
                    h.shortcut.toggle();
                }
            });
            h.handle.setOnTouchListener((v, ev) -> {
                if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && dragStarter != null) {
                    dragStarter.startDrag(h);
                }
                return false;
            });
        }

        @Override
        public int getItemCount() {
            return order.size();
        }

        void move(int from, int to) {
            if (from < 0 || to < 0 || from >= order.size() || to >= order.size() || from == to) {
                return;
            }
            SwitchItem it = order.remove(from);
            order.add(to, it);
            notifyItemMoved(from, to);
        }
    }

    private static String itemTitle(Context ctx, SwitchItem item) {
        switch (item) {
            case USB:
                return ctx.getString(R.string.usb_title);
            case WIRELESS:
                return ctx.getString(R.string.wireless_title);
            case DEV:
                return ctx.getString(R.string.devopts_title);
            case A11Y:
                return ctx.getString(R.string.a11y_title);
            default:
                return item.id;
        }
    }
}
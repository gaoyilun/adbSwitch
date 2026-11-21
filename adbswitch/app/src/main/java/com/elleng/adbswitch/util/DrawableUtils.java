package com.elleng.adbswitch.util;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;

/**
 * Drawable → Bitmap 的集中实现（P3 去重）。
 *
 * 图标解码/绘制原本散落在 AppListDialog.decodeIcon、MainActivity.badgedShortcutIcon、
 * ToggleShortcuts.appIcon 三处，逻辑同构、各写一份。统一到这里：
 *   - BitmapDrawable 走直接缩放（不清算、不重新栅格化，最快）；
 *   - 其余 Drawable 走 Canvas 绘制成方形 Bitmap。
 *   - Drawable 不能跨线程用，Bitmap 可以 —— 选择器/快捷方式都在后台线程构图标，必须转 Bitmap。
 * 失败返回 null，由调用方决定回退（默认图标 / 占位）。
 */
public final class DrawableUtils {

    private DrawableUtils() {
    }

    /** 把任意 Drawable 渲染成 size×size 的方形 Bitmap；size<=0 或失败返回 null。 */
    public static Bitmap toBitmap(Drawable d, int size) {
        if (d == null || size <= 0) {
            return null;
        }
        try {
            // PackageManager 返回的 Drawable 共享 ConstantState：不 mutate 就 setBounds
            // 会改掉其它使用方（桌面/选择器列表）的 bounds
            d = d.mutate();
            if (d instanceof BitmapDrawable) {
                Bitmap src = ((BitmapDrawable) d).getBitmap();
                if (src != null) {
                    return Bitmap.createScaledBitmap(src, size, size, true);
                }
            }
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            d.setBounds(0, 0, size, size);
            if (d instanceof AdaptiveIconDrawable) {
                // 自适应图标必须按系统蒙版（圆形/水滴/方圆）裁剪，否则栅格化出方角大图，
                // 与桌面真实图标观感不一致。getIconMask 返回按当前 bounds 缩放后的蒙版路径。
                c.clipPath(((AdaptiveIconDrawable) d).getIconMask());
            }
            d.draw(c);
            return bmp;
        } catch (Throwable t) {
            Log.w("DrawableUtils", "toBitmap failed: " + d, t);
            return null;
        }
    }
}

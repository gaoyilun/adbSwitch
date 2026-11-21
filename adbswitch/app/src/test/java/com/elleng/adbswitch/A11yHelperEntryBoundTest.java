package com.elleng.adbswitch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 护栏：无障碍「是否已绑定」的比对必须与组件名写法无关。
 *
 * 同一台设备上两个来源的写法并不固定：
 *  - 无障碍列表 key 存的是全限定 `pkg/pkg.Cls`；
 *  - {@code AccessibilityServiceInfo.getId()} 在 K90 实测返回缩写 `pkg/.Cls`。
 * 旧实现只补全了「待办条目是缩写」的方向，遇到「绑定结果是缩写」就永远判未绑定，
 * 导致「仍有 N 个需手动开启」永久残留（2026-11-21 K90 实测待办恒为 1）。
 */
public class A11yHelperEntryBoundTest {

    private static Set<String> set(String... items) {
        return new HashSet<>(Arrays.asList(items));
    }

    private static final String PKG = "com.x8bit.bitwarden";
    private static final String FULL = "com.x8bit.bitwarden.Accessibility.AccessibilityService";
    private static final String SHORT = ".Accessibility.AccessibilityService";

    @Test
    public void fullEntryMatchesShortBound() {
        // 本次修复的核心：列表存全限定、getId() 给缩写（K90 实测形态）
        assertTrue(A11yHelper.entryBound(PKG + "/" + FULL, set(PKG + "/" + SHORT)));
    }

    @Test
    public void shortEntryMatchesFullBound() {
        // 反向：待办条目是缩写、绑定结果是全限定（旧实现唯一覆盖的方向）
        assertTrue(A11yHelper.entryBound(PKG + "/" + SHORT, set(PKG + "/" + FULL)));
    }

    @Test
    public void sameFormOnBothSides() {
        assertTrue(A11yHelper.entryBound(PKG + "/" + FULL, set(PKG + "/" + FULL)));
        assertTrue(A11yHelper.entryBound(PKG + "/" + SHORT, set(PKG + "/" + SHORT)));
    }

    @Test
    public void matchesAmongMultipleBound() {
        assertTrue(A11yHelper.entryBound(PKG + "/" + FULL,
                set("li.songe.gkd/com.google.android.accessibility.selecttospeak.SelectToSpeakService",
                        PKG + "/" + SHORT)));
    }

    @Test
    public void differentClassInSamePackageDoesNotMatch() {
        assertFalse(A11yHelper.entryBound(PKG + "/" + FULL,
                set(PKG + "/.Other.SomeService")));
    }

    @Test
    public void differentPackageDoesNotMatch() {
        assertFalse(A11yHelper.entryBound(PKG + "/" + FULL,
                set("com.other.app/" + FULL)));
    }

    @Test
    public void malformedInputNeverThrowsOrMatches() {
        assertFalse(A11yHelper.entryBound("no-slash", set(PKG + "/" + SHORT)));
        assertFalse(A11yHelper.entryBound(null, set(PKG + "/" + SHORT)));
        assertFalse(A11yHelper.entryBound(PKG + "/" + FULL, set("no-slash")));
        assertFalse(A11yHelper.entryBound(PKG + "/" + FULL, new HashSet<>()));
    }

    @Test
    public void fullClassNameExpandsShortFormOnly() {
        assertEquals(FULL, A11yHelper.fullClassName(PKG, SHORT));
        assertEquals(FULL, A11yHelper.fullClassName(PKG, FULL));
        assertEquals("", A11yHelper.fullClassName(PKG, null));
    }
}

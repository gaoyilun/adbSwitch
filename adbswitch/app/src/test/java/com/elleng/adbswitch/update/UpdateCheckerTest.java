package com.elleng.adbswitch.update;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * §3.5 护栏：UpdateChecker.isNewer 的纯版本比较逻辑（只比 versionCode 单调整数）。
 */
public class UpdateCheckerTest {

    @Test
    public void newerWhenRemoteGreater() {
        assertTrue(UpdateChecker.isNewer(5, 4));
        assertTrue(UpdateChecker.isNewer(100, 1));
    }

    @Test
    public void notNewerWhenEqualOrLess() {
        assertFalse(UpdateChecker.isNewer(4, 5));
        assertFalse(UpdateChecker.isNewer(5, 5));
        assertFalse(UpdateChecker.isNewer(0, 0));
    }
}

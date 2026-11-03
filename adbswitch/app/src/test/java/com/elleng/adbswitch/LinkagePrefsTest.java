package com.elleng.adbswitch;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * §3.5 护栏：LinkagePrefs.isValidPackageName 的包名白名单（注入面）。
 * 这些字符串最终会拼进 root / shell 的 am force-stop 命令，必须挡住所有 shell 元字符。
 */
public class LinkagePrefsTest {

    @Test
    public void validPackageNames() {
        assertTrue(LinkagePrefs.isValidPackageName("com.example.app"));
        assertTrue(LinkagePrefs.isValidPackageName("com.example-app_v2"));
        assertTrue(LinkagePrefs.isValidPackageName("a"));
        assertTrue(LinkagePrefs.isValidPackageName("com.foo.bar"));
    }

    @Test
    public void rejectsShellMetachars() {
        // 注入面：以下任一字符都能以 root / uid 2000 权限执行任意命令
        assertFalse(LinkagePrefs.isValidPackageName("com.example;rm"));
        assertFalse(LinkagePrefs.isValidPackageName("com.example$(id)"));
        assertFalse(LinkagePrefs.isValidPackageName("com.example|cat"));
        assertFalse(LinkagePrefs.isValidPackageName("com.example&whoami"));
        assertFalse(LinkagePrefs.isValidPackageName("com.example`id`"));
    }

    @Test
    public void rejectsEmptyAndNull() {
        assertFalse(LinkagePrefs.isValidPackageName(""));
        assertFalse(LinkagePrefs.isValidPackageName(null));
    }
}

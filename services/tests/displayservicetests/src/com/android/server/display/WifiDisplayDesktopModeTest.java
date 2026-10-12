/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.display;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.Display;
import android.view.DisplayAdjustments;
import android.view.DisplayInfo;
import android.hardware.display.DisplayManager;
import android.os.Process;

import org.junit.Test;

/** Checks desktop eligibility without relying on a particular Miracast receiver. */
public class WifiDisplayDesktopModeTest {
    private static final int DEVICE_DESKTOP_FLAGS = DisplayDeviceInfo.FLAG_OWN_CONTENT_ONLY
            | DisplayDeviceInfo.FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS
            | DisplayDeviceInfo.FLAG_TRUSTED | DisplayDeviceInfo.FLAG_OWN_DISPLAY_GROUP;
    private static final int DISPLAY_DESKTOP_FLAGS = Display.FLAG_OWN_DISPLAY_GROUP
            | Display.FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS | Display.FLAG_TRUSTED;

    @Test
    public void publicTrustedWifiDesktop_isEligible() {
        assertTrue(device(Display.TYPE_WIFI, DEVICE_DESKTOP_FLAGS).isWifiDisplayDesktopMode());
        assertTrue(display(Display.TYPE_WIFI, DISPLAY_DESKTOP_FLAGS).isWifiDisplayDesktopMode());
    }

    @Test
    public void ordinaryMiracast_isNotDesktop() {
        assertFalse(device(Display.TYPE_WIFI, DisplayDeviceInfo.FLAG_PRESENTATION
                | DisplayDeviceInfo.FLAG_TRUSTED).isWifiDisplayDesktopMode());
        assertFalse(display(Display.TYPE_WIFI, Display.FLAG_PRESENTATION
                | Display.FLAG_TRUSTED).isWifiDisplayDesktopMode());
    }

    @Test
    public void incompleteDesktopFlags_areNotEligible() {
        for (int flag : new int[] {DisplayDeviceInfo.FLAG_OWN_CONTENT_ONLY,
                DisplayDeviceInfo.FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS,
                DisplayDeviceInfo.FLAG_TRUSTED, DisplayDeviceInfo.FLAG_OWN_DISPLAY_GROUP}) {
            assertFalse(device(Display.TYPE_WIFI, DEVICE_DESKTOP_FLAGS & ~flag)
                    .isWifiDisplayDesktopMode());
        }
        for (int flag : new int[] {Display.FLAG_OWN_DISPLAY_GROUP,
                Display.FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS, Display.FLAG_TRUSTED}) {
            assertFalse(display(Display.TYPE_WIFI, DISPLAY_DESKTOP_FLAGS & ~flag)
                    .isWifiDisplayDesktopMode());
        }
    }

    @Test
    public void privateWifiDisplay_isNotEligible() {
        assertFalse(device(Display.TYPE_WIFI, DEVICE_DESKTOP_FLAGS
                | DisplayDeviceInfo.FLAG_PRIVATE).isWifiDisplayDesktopMode());
        assertFalse(display(Display.TYPE_WIFI, DISPLAY_DESKTOP_FLAGS
                | Display.FLAG_PRIVATE).isWifiDisplayDesktopMode());
    }

    @Test
    public void otherDisplayTypes_areNotWifiDesktops() {
        for (int type : new int[] {Display.TYPE_INTERNAL, Display.TYPE_EXTERNAL,
                Display.TYPE_VIRTUAL, Display.TYPE_OVERLAY}) {
            assertFalse(device(type, DEVICE_DESKTOP_FLAGS).isWifiDisplayDesktopMode());
            assertFalse(display(type, DISPLAY_DESKTOP_FLAGS).isWifiDisplayDesktopMode());
        }
    }

    @Test
    public void admittedUsbDesktop_isIndependentButNotWifi() {
        final DisplayDeviceInfo device = device(Display.TYPE_VIRTUAL,
                DEVICE_DESKTOP_FLAGS | DisplayDeviceInfo.FLAG_USB_DESKTOP);
        final Display display = display(Display.TYPE_VIRTUAL,
                DISPLAY_DESKTOP_FLAGS | Display.FLAG_USB_DESKTOP);
        assertTrue(device.isIndependentDesktopMode());
        assertTrue(display.isIndependentDesktopMode());
        assertFalse(device.isWifiDisplayDesktopMode());
        assertFalse(display.isWifiDisplayDesktopMode());
    }

    @Test
    public void ordinaryOrUnlockedVirtualDisplay_isNotIndependentDesktop() {
        assertFalse(device(Display.TYPE_VIRTUAL, DEVICE_DESKTOP_FLAGS).isIndependentDesktopMode());
        assertFalse(display(Display.TYPE_VIRTUAL, DISPLAY_DESKTOP_FLAGS).isIndependentDesktopMode());
        assertFalse(device(Display.TYPE_VIRTUAL, DEVICE_DESKTOP_FLAGS
                | DisplayDeviceInfo.FLAG_USB_DESKTOP | DisplayDeviceInfo.FLAG_ALWAYS_UNLOCKED)
                .isIndependentDesktopMode());
        assertFalse(display(Display.TYPE_VIRTUAL, DISPLAY_DESKTOP_FLAGS
                | Display.FLAG_USB_DESKTOP | Display.FLAG_ALWAYS_UNLOCKED)
                .isIndependentDesktopMode());
        for (int flag : new int[] {Display.FLAG_OWN_DISPLAY_GROUP,
                Display.FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS, Display.FLAG_TRUSTED}) {
            assertFalse(display(Display.TYPE_VIRTUAL,
                    (DISPLAY_DESKTOP_FLAGS & ~flag) | Display.FLAG_USB_DESKTOP)
                    .isIndependentDesktopMode());
        }
        assertFalse(display(Display.TYPE_VIRTUAL, DISPLAY_DESKTOP_FLAGS
                | Display.FLAG_USB_DESKTOP | Display.FLAG_PRIVATE).isIndependentDesktopMode());
    }

    @Test
    public void usbAdmission_requiresOptInShellIdentityAndSafeFlags() {
        final int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP;
        assertTrue(VirtualDisplayAdapter.isUsbDesktop(true, Process.SHELL_UID,
                "com.android.shell", "usbcast-desktop", flags));
        assertFalse(VirtualDisplayAdapter.isUsbDesktop(false, Process.SHELL_UID,
                "com.android.shell", "usbcast-desktop", flags));
        assertFalse(VirtualDisplayAdapter.isUsbDesktop(true, 10000,
                "com.android.shell", "usbcast-desktop", flags));
        assertFalse(VirtualDisplayAdapter.isUsbDesktop(true, Process.SHELL_UID,
                "another.package", "usbcast-desktop", flags));
        assertFalse(VirtualDisplayAdapter.isUsbDesktop(true, Process.SHELL_UID,
                "com.android.shell", "scrcpy", flags));
        assertFalse(VirtualDisplayAdapter.isUsbDesktop(true, Process.SHELL_UID,
                "com.android.shell", "usbcast-desktop",
                flags | DisplayManager.VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED));
        assertFalse(VirtualDisplayAdapter.isUsbDesktop(true, Process.SHELL_UID,
                "com.android.shell", "usbcast-desktop",
                flags | DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR));
        for (int flag : new int[] {DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP}) {
            assertFalse(VirtualDisplayAdapter.isUsbDesktop(true, Process.SHELL_UID,
                    "com.android.shell", "usbcast-desktop", flags & ~flag));
        }
    }

    private static DisplayDeviceInfo device(int type, int flags) {
        final DisplayDeviceInfo info = new DisplayDeviceInfo();
        info.type = type;
        info.flags = flags;
        return info;
    }

    private static Display display(int type, int flags) {
        final DisplayInfo info = new DisplayInfo();
        info.type = type;
        info.flags = flags;
        return new Display(null, 1, info, new DisplayAdjustments());
    }
}

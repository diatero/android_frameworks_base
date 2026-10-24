/*
 * Copyright (C) 2026 diater
 * SPDX-License-Identifier: Apache-2.0
 */
package android.os;

import android.util.Log;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Process-local application environment supplied during bindApplication. No global property
 * writes, native property interception, filesystem hiding or attestation changes are performed.
 * The policy implementation lives outside the framework. Absent policy is a no-op.
 * @hide
 */
public final class AppEnvironmentOverrides {
    public static final String CORE_SETTINGS_KEY = "android.app.environmentOverrides";
    private static volatile Bundle sProperties;
    private static boolean sInstalled;

    private AppEnvironmentOverrides() {}

    /** String Build fields only; API level, ABI and security patch must remain accurate. */
    public static boolean isBuildFieldAllowed(String name) {
        switch (name) {
            case "ID": case "DISPLAY": case "PRODUCT": case "DEVICE": case "BOARD":
            case "MANUFACTURER": case "BRAND": case "MODEL": case "BOOTLOADER":
            case "HARDWARE": case "TYPE": case "TAGS": case "FINGERPRINT":
            case "HOST": case "USER":
                return true;
            default:
                return false;
        }
    }

    /** Deliberately excludes real boot/lock/verified-boot state and arbitrary properties. */
    public static boolean isPropertyAllowed(String name) {
        return name.equals("ro.debuggable") || name.equals("ro.secure")
                || name.equals("ro.adb.secure") || name.equals("ro.build.type")
                || name.equals("ro.build.tags") || name.equals("ro.build.fingerprint")
                || name.equals("ro.build.display.id") || name.equals("ro.build.id")
                || name.equals("ro.build.host") || name.equals("ro.build.user")
                || name.equals("ro.product.name") || name.equals("ro.product.device")
                || name.equals("ro.product.board") || name.equals("ro.product.manufacturer")
                || name.equals("ro.product.brand") || name.equals("ro.product.model")
                || name.equals("ro.bootloader") || name.equals("ro.hardware")
                || name.startsWith("ro.lineage.") || name.startsWith("ro.modversion");
    }

    /** Called once before application code is loaded. Invalid payloads leave stock behavior. */
    public static synchronized void install(Bundle profile) {
        if (profile == null || sInstalled || !Process.isApplicationUid(Process.myUid())) return;
        Bundle fields = profile.getBundle("build");
        Bundle properties = profile.getBundle("properties");
        if (fields == null || properties == null) return;
        // Prepare and validate everything before modifying any preloaded Build field.
        List<Field> targets = new ArrayList<>();
        List<Object> previous = new ArrayList<>();
        List<Object> replacements = new ArrayList<>();
        try {
            for (String name : fields.keySet()) {
                if (!isBuildFieldAllowed(name) || !(fields.get(name) instanceof String)) {
                    throw new IllegalArgumentException("unsupported Build field: " + name);
                }
                prepare(targets, previous, replacements, name, fields.getString(name));
            }
            for (String name : properties.keySet()) {
                if (!isPropertyAllowed(name) || !(properties.get(name) instanceof String)) {
                    throw new IllegalArgumentException("unsupported property: " + name);
                }
            }
            if (fields.containsKey("TYPE")) {
                String type = fields.getString("TYPE");
                prepare(targets, previous, replacements, "IS_ENG", "eng".equals(type));
                prepare(targets, previous, replacements, "IS_USERDEBUG", "userdebug".equals(type));
            }
            if (properties.containsKey("ro.debuggable")) {
                prepare(targets, previous, replacements, "IS_DEBUGGABLE",
                        "1".equals(properties.getString("ro.debuggable")));
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Log.e("AppEnvironment", "Cannot prepare process environment", e);
            return;
        }
        int applied = 0;
        try {
            for (; applied < targets.size(); applied++) {
                targets.get(applied).set(null, replacements.get(applied));
            }
            sProperties = new Bundle(properties);
            sInstalled = true;
        } catch (IllegalAccessException | RuntimeException | LinkageError e) {
            for (int i = applied - 1; i >= 0; i--) {
                try {
                    targets.get(i).set(null, previous.get(i));
                } catch (IllegalAccessException | RuntimeException rollback) {
                    Log.e("AppEnvironment", "Cannot restore " + targets.get(i).getName(), rollback);
                }
            }
            Log.e("AppEnvironment", "Cannot install process environment", e);
        }
    }

    private static void prepare(List<Field> targets, List<Object> previous,
            List<Object> replacements, String name, Object value)
            throws ReflectiveOperationException {
        Field field = Build.class.getDeclaredField(name);
        field.setAccessible(true);
        targets.add(field);
        previous.add(field.get(null));
        replacements.add(value);
    }

    /** Null means no override; empty string represents a hidden/empty property. No IPC or I/O. */
    static String property(String name) {
        Bundle properties = sProperties;
        if (properties == null) return null;
        if (properties.containsKey(name)) return properties.getString(name);
        if (name.startsWith("ro.lineage.") || name.equals("ro.modversion")) return "";
        return null;
    }

    static long parseLong(String value, long def) {
        if (value == null || value.isEmpty()) return def;
        int start = 0;
        while (start < value.length() && " \t\n\r\f\u000b".indexOf(value.charAt(start)) >= 0) start++;
        if (start == value.length()) return def;
        int radix = value.startsWith("0x", start) || value.startsWith("0X", start) ? 16 : 10;
        if (radix == 16) start += 2;
        int digits = start;
        if (radix == 10 && start < value.length()
                && (value.charAt(start) == '-' || value.charAt(start) == '+')) digits++;
        if (digits == value.length()) return def;
        for (int i = digits; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c >= '0' && c <= '9')
                    && !(radix == 16 && (c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F'))) {
                return def;
            }
        }
        try {
            return Long.parseLong(value.substring(start), radix);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static boolean parseBoolean(String value, boolean def) {
        switch (value) {
            case "1": case "y": case "yes": case "on": case "true": return true;
            case "0": case "n": case "no": case "off": case "false": return false;
            default: return def;
        }
    }
}

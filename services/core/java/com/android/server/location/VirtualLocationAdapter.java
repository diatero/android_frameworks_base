/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.location;

import android.annotation.Nullable;
import android.location.Location;
import android.location.util.identity.CallerIdentity;
import android.util.IndentingPrintWriter;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.FgThread;
import com.android.server.LocalServices;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Location service side of {@link VirtualLocationHooks}. It binds the implementation once
 * device-specific services are ready, keeps policy listeners that register earlier, and isolates
 * implementation failures: an unknown target falls back to stock behavior, while a failed fix
 * for a known target is not delivered (closed-for-targets).
 *
 * @hide
 */
public class VirtualLocationAdapter {
    private static final String TAG = "VirtualLocation";
    private static final int MAX_LOGGED_FAILURES = 3;

    private final CopyOnWriteArrayList<Runnable> mListeners = new CopyOnWriteArrayList<>();
    private final AtomicInteger mFailures = new AtomicInteger();
    @Nullable private volatile VirtualLocationHooks mHooks;

    /** Binds the implementation published to LocalServices, if there is one. */
    public void attach() {
        VirtualLocationHooks hooks;
        try {
            hooks = LocalServices.getService(VirtualLocationHooks.class);
        } catch (RuntimeException | LinkageError e) {
            // Never let a broken implementation stop the location service (rule H1).
            onFailure(e);
            return;
        }
        attach(hooks);
    }

    @VisibleForTesting
    public void attach(@Nullable VirtualLocationHooks hooks) {
        if (hooks == null) {
            Slog.i(TAG, "no virtual location implementation; stock location behavior");
            return;
        }
        try {
            hooks.setPolicyChangedCallback(this::notifyListeners);
        } catch (RuntimeException | LinkageError e) {
            onFailure(e);
            return;
        }
        mHooks = hooks;
        notifyListeners();
    }

    /** Returns the policy of this caller; null when none applies or the lookup fails. */
    @Nullable
    public VirtualLocationHooks.Policy getPolicy(CallerIdentity identity) {
        VirtualLocationHooks hooks = mHooks;
        if (hooks == null) {
            return null;
        }
        try {
            return hooks.getPolicy(identity);
        } catch (RuntimeException | LinkageError e) {
            onFailure(e);
            return null;
        }
    }

    /** Returns whether radio location information is hidden from this UID. */
    public boolean shouldHideEnvironment(int uid) {
        VirtualLocationHooks hooks = mHooks;
        if (hooks == null) {
            return false;
        }
        try {
            return hooks.shouldHideEnvironment(uid);
        } catch (RuntimeException | LinkageError e) {
            onFailure(e);
            return false;
        }
    }

    /** Creates a fix for a policy target; null means the fix must not be delivered. */
    @Nullable
    public Location createLocation(VirtualLocationHooks.Policy policy, String provider) {
        try {
            return policy.createLocation(provider);
        } catch (RuntimeException | LinkageError e) {
            onFailure(e);
            return null;
        }
    }

    /** Adds a listener posted to the foreground thread after policies change. */
    public void addListener(Runnable listener) {
        mListeners.add(listener);
    }

    /** Removes a listener added by {@link #addListener}. */
    public void removeListener(Runnable listener) {
        mListeners.remove(listener);
    }

    /** Dumps the binding state; policies are dumped by their implementation. */
    public void dump(IndentingPrintWriter pw) {
        pw.println("Virtual location: implementation " + (mHooks != null ? "bound" : "absent")
                + ", failures " + mFailures.get());
    }

    private void notifyListeners() {
        for (Runnable listener : mListeners) {
            FgThread.getHandler().post(listener);
        }
    }

    private void onFailure(Throwable e) {
        if (mFailures.incrementAndGet() <= MAX_LOGGED_FAILURES) {
            Slog.wtf(TAG, "virtual location implementation failed", e);
        }
    }
}

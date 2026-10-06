/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.location;

import android.annotation.Nullable;
import android.location.Location;
import android.location.util.identity.CallerIdentity;

/**
 * Contract for per-application virtual location. It is implemented outside the platform and
 * published to LocalServices; without an implementation location behaves as on stock Android.
 *
 * <p>Methods are called on location delivery paths, possibly while location service locks are
 * held: implementations read immutable snapshots and never block, do I/O, make binder calls or
 * take locks that may be held while calling into the location service.
 *
 * @hide
 */
public interface VirtualLocationHooks {

    /** An immutable policy. A different object is returned whenever a policy changes. */
    interface Policy {
        /** Creates a new synthetic fix; nothing is copied from a real fix. */
        Location createLocation(String provider);
    }

    /** Returns the policy of this caller, or null when its location is not replaced. */
    @Nullable
    Policy getPolicy(CallerIdentity identity);

    /** Returns whether Wi-Fi and cell information is hidden from this UID. */
    boolean shouldHideEnvironment(int uid);

    /**
     * Sets the callback to run after policies change. The implementation posts it to a handler
     * and never runs it inline.
     */
    void setPolicyChangedCallback(@Nullable Runnable callback);
}

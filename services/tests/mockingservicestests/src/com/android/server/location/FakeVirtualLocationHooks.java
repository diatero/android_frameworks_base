/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.location;

import android.annotation.Nullable;
import android.location.Location;
import android.location.util.identity.CallerIdentity;
import android.os.SystemClock;

import com.android.server.FgThread;

import java.util.HashMap;
import java.util.Map;

/** Fixed-coordinate {@link VirtualLocationHooks} for location service tests. */
public class FakeVirtualLocationHooks implements VirtualLocationHooks {

    private final class FixedPolicy implements Policy {
        final String mPackageName;
        final double mLatitude;
        final double mLongitude;
        final float mAccuracy;

        FixedPolicy(String packageName, double latitude, double longitude, float accuracy) {
            mPackageName = packageName;
            mLatitude = latitude;
            mLongitude = longitude;
            mAccuracy = accuracy;
        }

        @Override
        public Location createLocation(String provider) {
            if (mFailCreateLocation) {
                throw new IllegalStateException("simulated implementation failure");
            }
            Location location = new Location(provider);
            location.setLatitude(mLatitude);
            location.setLongitude(mLongitude);
            location.setAccuracy(mAccuracy);
            location.setTime(System.currentTimeMillis());
            location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            return location;
        }
    }

    private volatile Map<Integer, FixedPolicy> mPolicies = new HashMap<>();
    @Nullable private volatile Runnable mCallback;
    private volatile boolean mFailCreateLocation;

    /** Makes every policy fail to create a fix, as a broken implementation would. */
    public void setCreateLocationFailure(boolean fail) {
        mFailCreateLocation = fail;
    }

    /** Replaces the location of this application. */
    public void set(int uid, String packageName, double latitude, double longitude,
            float accuracy) {
        Map<Integer, FixedPolicy> next = new HashMap<>(mPolicies);
        next.put(uid, new FixedPolicy(packageName, latitude, longitude, accuracy));
        mPolicies = next;
        onChanged();
    }

    /** Stops replacing the location of this UID. */
    public void clear(int uid) {
        Map<Integer, FixedPolicy> next = new HashMap<>(mPolicies);
        next.remove(uid);
        mPolicies = next;
        onChanged();
    }

    @Nullable
    @Override
    public Policy getPolicy(CallerIdentity identity) {
        FixedPolicy policy = mPolicies.get(identity.getUid());
        return policy != null && policy.mPackageName.equals(identity.getPackageName())
                ? policy : null;
    }

    @Override
    public boolean shouldHideEnvironment(int uid) {
        return false;
    }

    @Override
    public void setPolicyChangedCallback(@Nullable Runnable callback) {
        mCallback = callback;
    }

    private void onChanged() {
        Runnable callback = mCallback;
        if (callback != null) {
            FgThread.getHandler().post(callback);
        }
    }
}

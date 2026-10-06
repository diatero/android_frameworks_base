/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.aod.widgets

import com.android.systemui.keyguard.shared.model.DozeStateModel

/** Never display provider content during pulses, authentication, proximity pause or user switch. */
object AodWidgetPolicy {
    fun canShowLockscreen(
        ownerUser: Boolean,
        switchingUser: Boolean,
        enabled: Boolean,
        keyguardDismissible: Boolean,
        showWhenLocked: Boolean,
        keyguardShowing: Boolean,
        occluded: Boolean,
        bouncerShowing: Boolean,
        awake: Boolean,
        dozing: Boolean,
    ): Boolean = ownerUser && !switchingUser && enabled &&
        (keyguardDismissible || showWhenLocked) && keyguardShowing && !occluded &&
        !bouncerShowing && awake && !dozing

    fun canShow(
        state: DozeStateModel,
        ownerUser: Boolean,
        switchingUser: Boolean,
        enabled: Boolean,
        keyguardDismissible: Boolean,
        showWhenLocked: Boolean,
        keyguardShowing: Boolean = true,
        occluded: Boolean = false,
        bouncerShowing: Boolean = false,
    ): Boolean =
        keyguardShowing && !occluded && !bouncerShowing && ownerUser && !switchingUser && enabled &&
            (keyguardDismissible || showWhenLocked) &&
            (state == DozeStateModel.DOZE_AOD || state == DozeStateModel.DOZE_AOD_DOCKED)
}

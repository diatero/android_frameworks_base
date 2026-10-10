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

    /**
     * Shared intensity for all AOD content: [ORIGINAL] when awake or pulsing, the saved
     * brightness on stable AOD, and [HIDDEN] while proximity pauses AOD.
     */
    fun contentBrightness(state: DozeStateModel, dozing: Boolean, brightness: Int): Int = when {
        !dozing -> ORIGINAL
        state == DozeStateModel.DOZE_AOD_PAUSING || state == DozeStateModel.DOZE_AOD_PAUSED -> HIDDEN
        state == DozeStateModel.DOZE_AOD || state == DozeStateModel.DOZE_AOD_DOCKED ||
            state == DozeStateModel.DOZE_AOD_MINMODE -> brightness.coerceIn(5, ORIGINAL)
        else -> ORIGINAL
    }

    const val ORIGINAL = 100
    const val HIDDEN = 0
}

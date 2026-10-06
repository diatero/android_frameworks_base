/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.aod.widgets

import androidx.test.filters.SmallTest
import android.testing.AndroidTestingRunner
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyguard.shared.model.DozeStateModel
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
class AodWidgetPolicyTest : SysuiTestCase() {
    @Test fun onlyStableAodStatesExposeProviderContent() {
        for (state in DozeStateModel.entries) {
            assertThat(AodWidgetPolicy.canShow(state, true, false, true, false, true))
                .isEqualTo(state == DozeStateModel.DOZE_AOD || state == DozeStateModel.DOZE_AOD_DOCKED)
        }
    }

    @Test fun secureLockRequiresExplicitConsent() {
        assertThat(AodWidgetPolicy.canShow(DozeStateModel.DOZE_AOD, true, false, true, false, false)).isFalse()
        assertThat(AodWidgetPolicy.canShow(DozeStateModel.DOZE_AOD, true, false, true, true, false)).isTrue()
    }

    @Test fun userSwitchAndDisabledSettingAlwaysHideContent() {
        assertThat(AodWidgetPolicy.canShow(DozeStateModel.DOZE_AOD, false, false, true, true, true)).isFalse()
        assertThat(AodWidgetPolicy.canShow(DozeStateModel.DOZE_AOD, true, true, true, true, true)).isFalse()
        assertThat(AodWidgetPolicy.canShow(DozeStateModel.DOZE_AOD, true, false, false, true, true)).isFalse()
    }

    @Test fun aodHidesBehindAppsAuthenticationAndDismissedKeyguard() {
        assertThat(AodWidgetPolicy.canShow(DozeStateModel.DOZE_AOD, true, false, true,
            true, true, keyguardShowing = false)).isFalse()
        assertThat(AodWidgetPolicy.canShow(DozeStateModel.DOZE_AOD, true, false, true,
            true, true, occluded = true)).isFalse()
        assertThat(AodWidgetPolicy.canShow(DozeStateModel.DOZE_AOD_DOCKED, true, false, true,
            true, true, bouncerShowing = true)).isFalse()
    }

    @Test fun lockscreenRequiresVisibleAwakeKeyguardAndConsent() {
        assertThat(AodWidgetPolicy.canShowLockscreen(true, false, true, false, true,
            true, false, false, true, false)).isTrue()
        assertThat(AodWidgetPolicy.canShowLockscreen(true, false, true, false, false,
            true, false, false, true, false)).isFalse()
        assertThat(AodWidgetPolicy.canShowLockscreen(true, false, true, true, false,
            true, false, false, true, false)).isTrue()
    }

    @Test fun lockscreenNeverExposesContentBehindAuthenticationOrApps() {
        // Independently exercise occlusion, bouncer, sleep, dozing and dismissed Keyguard.
        for (blocker in 0..4) {
            assertThat(AodWidgetPolicy.canShowLockscreen(true, false, true, true, true,
                blocker != 4, blocker == 0, blocker == 1, blocker != 2, blocker == 3)).isFalse()
        }
        assertThat(AodWidgetPolicy.canShowLockscreen(false, false, true, true, true,
            true, false, false, true, false)).isFalse()
        assertThat(AodWidgetPolicy.canShowLockscreen(true, true, true, true, true,
            true, false, false, true, false)).isFalse()
        assertThat(AodWidgetPolicy.canShowLockscreen(true, false, false, true, true,
            true, false, false, true, false)).isFalse()
    }
}

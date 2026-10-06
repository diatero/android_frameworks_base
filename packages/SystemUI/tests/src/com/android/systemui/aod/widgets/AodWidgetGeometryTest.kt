/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.aod.widgets

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
class AodWidgetGeometryTest : SysuiTestCase() {
    @Test fun oversizeAndCorruptedSettingsRemainInsidePortraitAndLandscape() {
        for ((w, h) in listOf(Pair(392f, 850f),
            Pair(850f, 392f), Pair(320f, 200f))) {
            for (x in listOf(-999, 0, 50, 100, 999)) {
                for (y in listOf(-999, 0, 50, 100, 999)) {
                    val rect = AodWidgetGeometry.resolve(w, h, Int.MAX_VALUE, Int.MAX_VALUE, x, y)
                    assertThat(rect.left).isAtLeast(0f)
                    assertThat(rect.top).isAtLeast(0f)
                    assertThat(rect.left + rect.width).isAtMost(w)
                    assertThat(rect.top + rect.height).isAtMost(h)
                }
            }
        }
    }

    @Test fun dragPositionsRoundTripAndNoTravelDoesNotDivideByZero() {
        for (position in listOf(0, 25, 50, 75, 100)) {
            val rect = AodWidgetGeometry.resolve(392f, 850f, 180, 120, position, position)
            assertThat(AodWidgetGeometry.positionPercent(rect.left, rect.minLeft, rect.travelX, 50))
                .isEqualTo(position)
            assertThat(AodWidgetGeometry.positionPercent(rect.top, rect.minTop, rect.travelY, 100))
                .isEqualTo(position)
        }
        assertThat(AodWidgetGeometry.positionPercent(0f, 0f, 0f, 50)).isEqualTo(50)
    }
}

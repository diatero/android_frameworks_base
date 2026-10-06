/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.aod.widgets;
import junit.framework.TestCase;
public class AodDisplayLayoutGeometryTest extends TestCase {
    public void testPortraitAllowsPlacementToScreenBottom() {
        var r = AodDisplayLayoutGeometry.resolve(400, 850, 100, 100, 100, 100);
        assertEquals(16f, r.left, .001f); assertEquals(32f, r.top, .001f);
        assertEquals(384f, r.left + r.width, .001f);
        assertEquals(850f, r.top + r.height, .001f);
    }
    public void testLandscapeUsesItsOwnShortScreenGeometry() {
        var r = AodDisplayLayoutGeometry.resolve(850, 400, 100, 100, 50, 50);
        assertEquals(834f, r.left + r.width, .001f);
        assertEquals(400f, r.top + r.height, .001f);
    }
    public void testBottomModuleCanOccupyFormerFingerprintArea() {
        var r = AodDisplayLayoutGeometry.resolve(400, 850, 50, 100, 50, 30);
        assertTrue(r.top < 660 && r.top + r.height > 660);
        assertEquals(850f, r.top + r.height, .001f);
    }
    public void testLegacyWidgetAlsoReachesPortraitAndLandscapeBottom() {
        for (var size : new float[][]{{400, 850}, {850, 400}, {320, 200}}) {
            var r = AodWidgetGeometry.resolve(size[0], size[1], 180, 120, 50, 100);
            assertEquals(size[1], r.top + r.height, .001f);
            assertTrue(r.top >= 0);
        }
    }
    public void testCorruptPercentagesRemainInViewport() {
        var r = AodDisplayLayoutGeometry.resolve(200, 150, -100, 999, 999, -100);
        assertTrue(r.left >= 0 && r.top >= 0);
        assertTrue(r.left + r.width <= 200 && r.top + r.height <= 150);
        assertTrue(r.width > 0 && r.height > 0);
    }
    public void testPositionHasNoDivisionByZeroAndClampsDrags() {
        assertEquals(42, AodDisplayLayoutGeometry.position(10, 0, 0, 42));
        assertEquals(0, AodDisplayLayoutGeometry.position(-100, 16, 100, 42));
        assertEquals(100, AodDisplayLayoutGeometry.position(1000, 16, 100, 42));
    }
    public void testBurnInMovementStaysInScreenWithoutReservingBottomSpace() {
        assertEquals(0f, AodDisplayLayoutGeometry.boundedOffset(650, 200, 850, 84), .001f);
        assertEquals(10f, AodDisplayLayoutGeometry.boundedOffset(640, 200, 850, 84), .001f);
        assertEquals(84f, AodDisplayLayoutGeometry.boundedOffset(300, 200, 850, 84), .001f);
        assertEquals(0f, AodDisplayLayoutGeometry.boundedOffset(0, 200, 850, -84), .001f);
        assertEquals(-650f, AodDisplayLayoutGeometry.boundedOffset(650, 200, 850, -999), .001f);
        assertEquals(0f, AodDisplayLayoutGeometry.boundedOffset(0, 850, 850, 84), .001f);
    }
    public void testDefaultModulesDoNotOverlapOnPortraitOrLandscape() {
        for (int profile = 0; profile < 4; profile++) {
            boolean landscape = profile % 2 != 0;
            var boxes = AodDisplayLayoutStore.defaults(profile).boxes;
            for (int i = 0; i < boxes.length; i++) for (int j = i + 1; j < boxes.length; j++) {
                var a = boxes[i]; var b = boxes[j];
                var ra = AodDisplayLayoutGeometry.resolve(landscape ? 850 : 400,
                        landscape ? 400 : 850, a.x, a.y, a.width, a.height);
                var rb = AodDisplayLayoutGeometry.resolve(landscape ? 850 : 400,
                        landscape ? 400 : 850, b.x, b.y, b.width, b.height);
                assertTrue("profile=" + profile + " modules=" + i + "," + j,
                        ra.left + ra.width <= rb.left || rb.left + rb.width <= ra.left
                        || ra.top + ra.height <= rb.top || rb.top + rb.height <= ra.top);
            }
        }
    }
}

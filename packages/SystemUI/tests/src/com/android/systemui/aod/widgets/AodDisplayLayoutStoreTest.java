/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.aod.widgets;
import junit.framework.TestCase;
public class AodDisplayLayoutStoreTest extends TestCase {
    public void testAllFourProfilesAreIndependentAndRoundTripAtomically() {
        var layouts = new AodDisplayLayoutStore.Layouts();
        layouts.profiles[1].enabled = true;
        layouts.profiles[1].boxes[0].x = 77;
        layouts.profiles[3].boxes[2].visible = false;
        var result = AodDisplayLayoutStore.decode(AodDisplayLayoutStore.encode(layouts));
        assertTrue(result.profiles[1].enabled); assertFalse(result.profiles[0].enabled);
        assertEquals(77, result.profiles[1].boxes[0].x);
        assertEquals(0, result.profiles[0].boxes[0].x);
        assertFalse(result.profiles[3].boxes[2].visible);
        assertTrue(result.profiles[2].boxes[2].visible);
    }
    public void testIncompleteCorruptOrFutureSchemaUsesNativeLayout() {
        for (String saved : new String[]{null, "{broken", "{\"version\":99}",
                "{\"version\":1,\"profiles\":[{\"enabled\":true,\"boxes\":[]}]}",
                "{\"version\":1,\"profiles\":[{\"enabled\":true,\"boxes\":[[],[],[],[],[]]}]}"}) {
            for (var profile : AodDisplayLayoutStore.decode(saved).profiles) assertFalse(profile.enabled);
        }
    }
    public void testCorruptGeometryCannotLeaveScreenOrAllocateNegativeBitmap() {
        var layouts = new AodDisplayLayoutStore.Layouts();
        var box = layouts.profiles[0].boxes[3]; box.x = -100; box.y = 300; box.width = 900; box.height = -1;
        var result = AodDisplayLayoutStore.decode(AodDisplayLayoutStore.encode(layouts)).profiles[0].boxes[3];
        assertEquals(0, result.x); assertEquals(100, result.y);
        assertEquals(100, result.width); assertEquals(5, result.height);
    }
    public void testScreenAndOrientationChooseDifferentProfiles() {
        assertEquals(0, AodDisplayLayoutStore.index(true, 1080, 2340));
        assertEquals(1, AodDisplayLayoutStore.index(true, 2340, 1080));
        assertEquals(2, AodDisplayLayoutStore.index(false, 1080, 2340));
        assertEquals(3, AodDisplayLayoutStore.index(false, 2340, 1080));
    }
    public void testWidgetGeometryIsKeyedByIdAndIndependentAcrossProfiles() {
        var layouts = new AodDisplayLayoutStore.Layouts();
        var first = AodDisplayLayoutStore.editWidgetBox(layouts.profiles[0], 12, 0);
        var second = AodDisplayLayoutStore.editWidgetBox(layouts.profiles[0], 14, 1);
        first.x = 11; second.x = 89; second.visible = false;
        var result = AodDisplayLayoutStore.decode(AodDisplayLayoutStore.encode(layouts));
        assertEquals(11, AodDisplayLayoutStore.widgetBox(result.profiles[0], 12, 1).x);
        assertEquals(89, AodDisplayLayoutStore.widgetBox(result.profiles[0], 14, 0).x);
        assertFalse(AodDisplayLayoutStore.widgetBox(result.profiles[0], 14, 0).visible);
        assertTrue(result.profiles[2].widgets.isEmpty());
    }
    public void testVersionOneCustomWidgetBoxRemainsFirstWidgetFallback() {
        var layouts = new AodDisplayLayoutStore.Layouts();
        layouts.profiles[0].enabled = true;
        layouts.profiles[0].boxes[3].x = 67;
        String legacy = AodDisplayLayoutStore.encode(layouts).replace("\"version\":2", "\"version\":1");
        var result = AodDisplayLayoutStore.decode(legacy);
        assertTrue(result.profiles[0].enabled);
        assertEquals(67, AodDisplayLayoutStore.widgetBox(result.profiles[0], 98, 0).x);
    }
    public void testCorruptIndividualWidgetGeometryIsBounded() {
        var layouts = new AodDisplayLayoutStore.Layouts();
        var b = AodDisplayLayoutStore.editWidgetBox(layouts.profiles[1], 14, 0);
        b.x = -99; b.y = 199; b.width = 900; b.height = -5;
        var result = AodDisplayLayoutStore.decode(AodDisplayLayoutStore.encode(layouts));
        b = result.profiles[1].widgets.get(14);
        assertEquals(0, b.x); assertEquals(100, b.y);
        assertEquals(100, b.width); assertEquals(5, b.height);
    }
}

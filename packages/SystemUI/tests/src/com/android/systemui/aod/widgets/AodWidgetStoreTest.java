/* Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.aod.widgets;

import java.util.List;
import junit.framework.TestCase;

public class AodWidgetStoreTest extends TestCase {
    public void testLegacySelectionMigratesOnlyWhenListIsAbsent() {
        assertEquals(List.of(98), AodWidgetStore.decodeIds(null, 98));
        assertEquals(List.of(), AodWidgetStore.decodeIds(null, -1));
        assertEquals(List.of(), AodWidgetStore.decodeIds("[]", 98));
        assertEquals(List.of(), AodWidgetStore.decodeIds("broken", 98));
    }
    public void testDuplicateNegativeAndInvalidIdsDoNotCreateDuplicateHosts() {
        assertEquals(List.of(12, 14, 15),
                AodWidgetStore.decodeIds("[12,12,-1,null,14,\"broken\",15]", -1));
    }
    public void testUntrustedListsCannotAllocateUnboundedRenderers() {
        assertEquals(AodWidgetStore.MAX_WIDGETS,
                AodWidgetStore.decodeIds("[1,2,3,4,5,6,7,8,9,10]", -1).size());
    }
}

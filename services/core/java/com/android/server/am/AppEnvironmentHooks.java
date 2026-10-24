/*
 * Copyright (C) 2026 diater
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.am;

import android.content.pm.ApplicationInfo;
import android.os.Bundle;

/** Optional app binding policy. Implementations must read an immutable snapshot: no IPC/I/O. */
public interface AppEnvironmentHooks {
    /** Returns a new, caller-owned Bundle, or null to preserve the original environment. */
    Bundle getOverrides(ApplicationInfo info);
}

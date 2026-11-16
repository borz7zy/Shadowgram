/*
 * SPDX-FileCopyrightText: borz7zy
 * SPDX-License-Identifier: MIT
 */

package org.lineageos.aperture.models

/**
 * Multi-frame RAW processing done in-app by the camera_pipeline native library.
 */
enum class BurstProcessing {
    /**
     * Regular single capture.
     */
    OFF,

    /**
     * Underexposed burst, aligned and merged with a Wiener filter, then tone mapped.
     */
    HDR_PLUS,

    /**
     * Burst merged with multi-frame super-resolution (kernel regression).
     */
    SUPER_RES,

    /**
     * Long exposures chosen from hand shake, for very low light.
     */
    NIGHT_SIGHT,
}

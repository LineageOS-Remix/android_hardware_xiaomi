/*
 * Copyright (C) 2023-24 Paranoid Android
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package co.aospa.dolby.xiaomi

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

private const val TAG = "DolbyTileService"

class DolbyTileService : TileService() {

    private val dolbyController by lazy { DolbyController.getInstance(applicationContext) }

    override fun onStartListening() {
        val isDsOn = dolbyController.dsOn
        qsTile.apply {
            state = if (isDsOn) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            subtitle = if (isDsOn) {
                dolbyController.getProfileName() ?: getString(R.string.dolby_unknown)
            } else {
                getString(R.string.dolby_off)
            }
            updateTile()
        }
        super.onStartListening()
    }

    override fun onClick() {
        val isDsOn = dolbyController.dsOn
        val newDsOn = !isDsOn
        dolbyController.setDsOnAndPersist(newDsOn) // toggle
        qsTile.apply {
            state = if (newDsOn) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            subtitle = if (newDsOn) {
                dolbyController.getProfileName() ?: getString(R.string.dolby_unknown)
            } else {
                getString(R.string.dolby_off)
            }
            updateTile()
        }
        super.onClick()
    }
}

package io.github.ninte86hl.hugminn

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Tile in Android's quick settings: opens the Control Center without pressing a button. */
class CcTile : TileService() {
    override fun onStartListening() {
        qsTile?.apply { state = if (HugminnService.instance != null) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE; updateTile() }
    }

    override fun onClick() = OpenFromOutside.open(this, Action.CONTROL_CENTER, closeShade = true)
}

package dev.nvgt.bridge

import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.edit

class BridgeTileService : TileService() {

	override fun onStartListening() {
		super.onStartListening()
		updateTileState()
	}

	override fun onClick() {
		val prefs = getSharedPreferences("nvgt_bridge_prefs", Context.MODE_PRIVATE)
		val currentState = prefs.getBoolean("master_switch", true)
		val newState = !currentState
		prefs.edit { putBoolean("master_switch", newState) }
		updateTileState()
	}

	private fun updateTileState() {
		val prefs = getSharedPreferences("nvgt_bridge_prefs", Context.MODE_PRIVATE)
		val isActive = prefs.getBoolean("master_switch", true)
		val tile = qsTile ?: return
		tile.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
		tile.label = getString(if (isActive) R.string.tile_on else R.string.tile_off)
		tile.contentDescription = getString(if (isActive) R.string.tile_description_on else R.string.tile_description_off)
		tile.updateTile()
	}
}
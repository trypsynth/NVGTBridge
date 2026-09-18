package dev.nvgt.bridge

import android.content.pm.PackageManager
import android.os.Bundle

object NvgtUtils {
	private const val NVGT_METADATA_KEY = "dev.nvgt.capability.DIRECT_TOUCH"

	fun hasNvgtSupport(pm: PackageManager, packageName: String): Boolean {
		try {
			val appInfo = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
			if (declaresDirectTouch(appInfo.metaData)) {
				return true
			}
			val component = pm.getLaunchIntentForPackage(packageName)?.component ?: return false
			val activityInfo = pm.getActivityInfo(component, PackageManager.GET_META_DATA)
			return declaresDirectTouch(activityInfo.metaData)
		} catch (_: Exception) {
			return false
		}
	}

	private fun declaresDirectTouch(metaData: Bundle?): Boolean {
		return metaData != null && metaData.getBoolean(NVGT_METADATA_KEY, false)
	}
}

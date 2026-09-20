package dev.nvgt.bridge

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

class SettingsActivity : ComponentActivity() {

	private val appsList = mutableStateListOf<AppInfo>()
	private var enabledApps by mutableStateOf(setOf<String>())
	private var hapticsEnabled by mutableStateOf(false)
	private var searchQuery by mutableStateOf("")
	private var serviceEnabled by mutableStateOf(false)

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val prefs = getSharedPreferences("nvgt_bridge_prefs", Context.MODE_PRIVATE)
		hapticsEnabled = prefs.getBoolean("haptics_enabled", false)
		loadEnabledApps()
		lifecycleScope.launch {
			loadInstalledApps()
		}
		setContent {
			MaterialTheme(
				colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
			) {
				SettingsScreen(
					serviceEnabled = serviceEnabled,
					onEnableService = { openAccessibilitySettings() },
					hapticsEnabled = hapticsEnabled,
					onHapticsChanged = { enabled ->
						hapticsEnabled = enabled
						prefs.edit { putBoolean("haptics_enabled", enabled) }
					},
					searchQuery = searchQuery,
					onSearchQueryChanged = { query -> searchQuery = query },
					appsList = appsList,
					onAppToggle = { app, isEnabled ->
						toggleApp(app, isEnabled)
					},
					onDirectTypingChanged = { app, isEnabled ->
						setDirectTyping(app, isEnabled)
					},
					onBackup = { uri -> performBackup(uri) },
					onRestore = { uri -> performRestore(uri) }
				)
			}
		}
	}

	override fun onResume() {
		super.onResume()
		serviceEnabled = isBridgeServiceEnabled()
	}

	private fun isBridgeServiceEnabled(): Boolean {
		val manager = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
		return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
			it.resolveInfo.serviceInfo.packageName == packageName
		}
	}

	private fun openAccessibilitySettings() {
		startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
	}

	private fun toggleApp(app: AppInfo, isEnabled: Boolean) {
		val newEnabledApps = enabledApps.toMutableSet()
		if (isEnabled) {
			newEnabledApps.add(app.packageName)
		} else {
			newEnabledApps.remove(app.packageName)
		}
		enabledApps = newEnabledApps
		saveEnabledApps()
		val index = appsList.indexOfFirst { it.packageName == app.packageName }
		if (index != -1) {
			appsList[index] = appsList[index].copy(isEnabled = isEnabled)
		}
	}

	private fun setDirectTyping(app: AppInfo, isEnabled: Boolean) {
		val prefs = getSharedPreferences("nvgt_bridge_prefs", Context.MODE_PRIVATE)
		prefs.edit { putBoolean("direct_typing_${app.packageName}", isEnabled) }
		val index = appsList.indexOfFirst { it.packageName == app.packageName }
		if (index != -1) {
			appsList[index] = appsList[index].copy(directTyping = isEnabled)
		}
	}

	private suspend fun loadInstalledApps() {
		withContext(Dispatchers.IO) {
			val pm = packageManager
			val selfPackage = packageName
			val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
			val prefs = getSharedPreferences("nvgt_bridge_prefs", Context.MODE_PRIVATE)
			val tempAppList = mutableListOf<AppInfo>()
			val newEnabledSet = enabledApps.toMutableSet()
			val seenNativeApps = prefs.getStringSet("seen_native_packages", emptySet())?.toMutableSet() ?: mutableSetOf()
			var seenChanged = false
			var enabledChanged = false
			for (packageInfo in packages) {
				if (packageInfo.packageName != selfPackage && pm.getLaunchIntentForPackage(packageInfo.packageName) != null) {
					val appName = packageInfo.loadLabel(pm).toString()
					val packageName = packageInfo.packageName
					var isEnabled = newEnabledSet.contains(packageName)
					if (!seenNativeApps.contains(packageName) && NvgtUtils.hasNvgtSupport(pm, packageName)) {
						seenNativeApps.add(packageName)
						seenChanged = true
						if (!isEnabled) {
							isEnabled = true
							newEnabledSet.add(packageName)
							enabledChanged = true
						}
					}
					val directTyping = prefs.getBoolean("direct_typing_$packageName", false)
					tempAppList.add(AppInfo(appName, packageName, isEnabled, directTyping))
				}
			}
			if (seenChanged) {
				prefs.edit { putStringSet("seen_native_packages", seenNativeApps) }
			}
			if (enabledChanged) {
				withContext(Dispatchers.Main) {
					enabledApps = newEnabledSet
					saveEnabledApps()
				}
			}
			withContext(Dispatchers.Main) {
				appsList.clear()
				appsList.addAll(tempAppList)
			}
		}
	}

	private fun saveEnabledApps() {
		val prefs = getSharedPreferences("nvgt_bridge_prefs", MODE_PRIVATE)
		prefs.edit { putStringSet("enabled_app_packages", enabledApps) }
	}

	private fun loadEnabledApps() {
		val prefs = getSharedPreferences("nvgt_bridge_prefs", MODE_PRIVATE)
		enabledApps = prefs.getStringSet("enabled_app_packages", emptySet())?.toSet() ?: emptySet()
	}

	private fun performBackup(uri: Uri) {
		lifecycleScope.launch(Dispatchers.IO) {
			try {
				val prefs = getSharedPreferences("nvgt_bridge_prefs", Context.MODE_PRIVATE)
				val root = JSONObject()
				val appsArray = JSONArray()
				enabledApps.forEach { appsArray.put(it) }
				root.put("enabled_apps", appsArray)
				val seenArray = JSONArray()
				prefs.getStringSet("seen_native_packages", emptySet())?.forEach { seenArray.put(it) }
				root.put("seen_native_apps", seenArray)
				root.put("haptics_enabled", prefs.getBoolean("haptics_enabled", false))
				val directTypingObj = JSONObject()
				prefs.all.keys.filter { it.startsWith("direct_typing_") }.forEach { key ->
					directTypingObj.put(key, prefs.getBoolean(key, false))
				}
				root.put("direct_typing", directTypingObj)
				contentResolver.openOutputStream(uri)?.use { outputStream ->
					outputStream.write(root.toString(4).toByteArray())
				}
				withContext(Dispatchers.Main) {
					Toast.makeText(this@SettingsActivity, R.string.backup_success, Toast.LENGTH_SHORT).show()
				}
			} catch (e: Exception) {
				withContext(Dispatchers.Main) {
					Toast.makeText(this@SettingsActivity, R.string.error_backup, Toast.LENGTH_SHORT).show()
				}
			}
		}
	}

	private fun performRestore(uri: Uri) {
		lifecycleScope.launch(Dispatchers.IO) {
			try {
				val stringBuilder = StringBuilder()
				contentResolver.openInputStream(uri)?.use { inputStream ->
					BufferedReader(InputStreamReader(inputStream)).use { reader ->
						var line: String? = reader.readLine()
						while (line != null) {
							stringBuilder.append(line)
							line = reader.readLine()
						}
					}
				}
				val root = JSONObject(stringBuilder.toString())
				val prefs = getSharedPreferences("nvgt_bridge_prefs", Context.MODE_PRIVATE)
				var restoredEnabled: Set<String>? = null
				var restoredHaptics: Boolean? = null
				prefs.edit {
					if (root.has("enabled_apps")) {
						val appsArray = root.getJSONArray("enabled_apps")
						val newEnabledApps = mutableSetOf<String>()
						for (i in 0 until appsArray.length()) {
							newEnabledApps.add(appsArray.getString(i))
						}
						putStringSet("enabled_app_packages", newEnabledApps)
						restoredEnabled = newEnabledApps
					}
					if (root.has("seen_native_apps")) {
						val seenArray = root.getJSONArray("seen_native_apps")
						val restoredSeen = mutableSetOf<String>()
						for (i in 0 until seenArray.length()) {
							restoredSeen.add(seenArray.getString(i))
						}
						putStringSet("seen_native_packages", restoredSeen)
					}
					if (root.has("haptics_enabled")) {
						val enabled = root.getBoolean("haptics_enabled")
						putBoolean("haptics_enabled", enabled)
						restoredHaptics = enabled
					}
					if (root.has("direct_typing")) {
						val directTypingObj = root.getJSONObject("direct_typing")
						val keys = directTypingObj.keys()
						while (keys.hasNext()) {
							val key = keys.next()
							putBoolean(key, directTypingObj.getBoolean(key))
						}
					}
				}
				withContext(Dispatchers.Main) {
					restoredEnabled?.let { enabledApps = it }
					restoredHaptics?.let { hapticsEnabled = it }
					loadInstalledApps()
					Toast.makeText(this@SettingsActivity, R.string.restore_success, Toast.LENGTH_SHORT).show()
				}
			} catch (e: Exception) {
				withContext(Dispatchers.Main) {
					Toast.makeText(this@SettingsActivity, R.string.error_restore, Toast.LENGTH_SHORT).show()
				}
			}
		}
	}
}


package dev.nvgt.bridge

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

sealed class AppListItem {
	data class Header(val title: String) : AppListItem()
	data class App(val appInfo: AppInfo) : AppListItem()
}

class SettingsActivity : ComponentActivity() {

	private val appsList = mutableStateListOf<AppInfo>()
	private var enabledApps by mutableStateOf(setOf<String>())
	private var hapticsEnabled by mutableStateOf(true)
	private var searchQuery by mutableStateOf("")

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val prefs = getSharedPreferences("nvgt_bridge_prefs", Context.MODE_PRIVATE)
		hapticsEnabled = prefs.getBoolean("haptics_enabled", true)
		loadEnabledApps()

		lifecycleScope.launch {
			loadInstalledApps()
		}

		setContent {
			MaterialTheme {
				SettingsScreen(
					hapticsEnabled = hapticsEnabled,
					onHapticsChanged = { enabled ->
						hapticsEnabled = enabled
						prefs.edit().putBoolean("haptics_enabled", enabled).apply()
					},
					searchQuery = searchQuery,
					onSearchQueryChanged = { query -> searchQuery = query },
					appsList = appsList,
					enabledApps = enabledApps,
					onAppToggle = { app, isEnabled ->
						toggleApp(app, isEnabled)
					},
					onAppConfigure = { app ->
						showAppConfigDialog(app)
					},
					onBackup = { uri -> performBackup(uri) },
					onRestore = { uri -> performRestore(uri) }
				)
			}
		}
	}

	private fun toggleApp(app: AppInfo, isEnabled: Boolean) {
		app.isEnabled = isEnabled
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
			appsList[index] = app.copy(isEnabled = isEnabled)
		}
	}

	private fun showAppConfigDialog(app: AppInfo) {
		val prefs = getSharedPreferences("nvgt_bridge_prefs", Context.MODE_PRIVATE)
		val keyDirectTyping = "direct_typing_${app.packageName}"
		var isDirectTyping = prefs.getBoolean(keyDirectTyping, false)

		val builder = android.app.AlertDialog.Builder(this)
		builder.setTitle("Configure settings for ${app.name}")
		
		val options = arrayOf("Direct Typing (Don't cut out keyboard)")
		val checkedItems = booleanArrayOf(isDirectTyping)

		builder.setMultiChoiceItems(options, checkedItems) { _, which, isChecked ->
			if (which == 0) {
				isDirectTyping = isChecked
			}
		}

		builder.setPositiveButton("Save") { _, _ ->
			prefs.edit().putBoolean(keyDirectTyping, isDirectTyping).apply()
			app.directTyping = isDirectTyping
			val index = appsList.indexOfFirst { it.packageName == app.packageName }
			if (index != -1) {
				appsList[index] = app.copy(directTyping = isDirectTyping)
			}
		}

		builder.setNegativeButton("Cancel", null)
		builder.show()
	}

	private suspend fun loadInstalledApps() {
		withContext(Dispatchers.IO) {
			val pm = packageManager
			val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
			val prefs = getSharedPreferences("nvgt_bridge_prefs", Context.MODE_PRIVATE)
			
			val tempAppList = mutableListOf<AppInfo>()
			val newEnabledSet = enabledApps.toMutableSet()
			var newNativeAppsFound = false
			
			for (packageInfo in packages) {
				if (pm.getLaunchIntentForPackage(packageInfo.packageName) != null) {
					val appName = packageInfo.loadLabel(pm).toString()
					val appIcon = packageInfo.loadIcon(pm)
					val packageName = packageInfo.packageName
					
					var isEnabled = newEnabledSet.contains(packageName)
					
					if (!isEnabled) {
						if (NvgtUtils.hasNvgtSupport(pm, packageName)) {
							isEnabled = true
							newEnabledSet.add(packageName)
							newNativeAppsFound = true
						}
					}
					
					val directTyping = prefs.getBoolean("direct_typing_$packageName", false)

					tempAppList.add(AppInfo(appName, packageName, appIcon, isEnabled, directTyping))
				}
			}
			
			if (newNativeAppsFound) {
				withContext(Dispatchers.Main) {
					enabledApps = newEnabledSet
					saveEnabledApps()
				}
			}

			tempAppList.sortBy { it.name }
			
			withContext(Dispatchers.Main) {
				appsList.clear()
				appsList.addAll(tempAppList)
			}
		}
	}

	private fun saveEnabledApps() {
		val prefs = getSharedPreferences("nvgt_bridge_prefs", MODE_PRIVATE)
		prefs.edit().putStringSet("enabled_app_packages", enabledApps).apply()
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
				
				root.put("haptics_enabled", prefs.getBoolean("haptics_enabled", true))

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
				val editor = prefs.edit()

				if (root.has("enabled_apps")) {
					val appsArray = root.getJSONArray("enabled_apps")
					val newEnabledApps = mutableSetOf<String>()
					for (i in 0 until appsArray.length()) {
						newEnabledApps.add(appsArray.getString(i))
					}
					editor.putStringSet("enabled_app_packages", newEnabledApps)
					withContext(Dispatchers.Main) {
						enabledApps = newEnabledApps
					}
				}

				if (root.has("haptics_enabled")) {
					val enabled = root.getBoolean("haptics_enabled")
					editor.putBoolean("haptics_enabled", enabled)
					withContext(Dispatchers.Main) {
						hapticsEnabled = enabled
					}
				}

				if (root.has("direct_typing")) {
					val directTypingObj = root.getJSONObject("direct_typing")
					val keys = directTypingObj.keys()
					while (keys.hasNext()) {
						val key = keys.next()
						editor.putBoolean(key, directTypingObj.getBoolean(key))
					}
				}

				editor.apply()

				withContext(Dispatchers.Main) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
	hapticsEnabled: Boolean,
	onHapticsChanged: (Boolean) -> Unit,
	searchQuery: String,
	onSearchQueryChanged: (String) -> Unit,
	appsList: List<AppInfo>,
	enabledApps: Set<String>,
	onAppToggle: (AppInfo, Boolean) -> Unit,
	onAppConfigure: (AppInfo) -> Unit,
	onBackup: (Uri) -> Unit,
	onRestore: (Uri) -> Unit
) {
	val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
		if (result.resultCode == Activity.RESULT_OK) {
			result.data?.data?.let { uri -> onBackup(uri) }
		}
	}

	val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
		if (result.resultCode == Activity.RESULT_OK) {
			result.data?.data?.let { uri -> onRestore(uri) }
		}
	}

	var showMenu by remember { mutableStateOf(false) }

	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text("Direct Touch Apps", color = Color.White) },
				colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary),
				actions = {
					IconButton(onClick = { showMenu = !showMenu }) {
						Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = Color.White)
					}
					DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
						DropdownMenuItem(
							text = { Text("Backup Settings") },
							onClick = {
								showMenu = false
								val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
									addCategory(Intent.CATEGORY_OPENABLE)
									type = "application/json"
									putExtra(Intent.EXTRA_TITLE, "nvgt_bridge_backup.json")
								}
								backupLauncher.launch(intent)
							}
						)
						DropdownMenuItem(
							text = { Text("Restore Settings") },
							onClick = {
								showMenu = false
								val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
									addCategory(Intent.CATEGORY_OPENABLE)
									type = "application/json"
								}
								restoreLauncher.launch(intent)
							}
						)
					}
				}
			)
		}
	) { padding ->
		Column(modifier = Modifier.padding(padding)) {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.clickable { onHapticsChanged(!hapticsEnabled) }
					.padding(16.dp)
					.clearAndSetSemantics {
						contentDescription = "Enable Haptic Feedback, ${if (hapticsEnabled) "on" else "off"}"
						role = Role.Switch
					},
				verticalAlignment = Alignment.CenterVertically
			) {
				Text("Enable Haptic Feedback", modifier = Modifier.weight(1f), fontSize = 18.sp)
				Switch(checked = hapticsEnabled, onCheckedChange = null)
			}

			TextField(
				value = searchQuery,
				onValueChange = onSearchQueryChanged,
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 16.dp),
				placeholder = { Text("Search Apps") },
				leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
				singleLine = true
			)

			val filteredApps = if (searchQuery.isEmpty()) {
				appsList
			} else {
				appsList.filter { it.name.contains(searchQuery, ignoreCase = true) }
			}

			val enabled = filteredApps.filter { it.isEnabled }.sortedBy { it.name }
			val disabled = filteredApps.filter { !it.isEnabled }.sortedBy { it.name }

			val items = mutableListOf<AppListItem>()
			if (enabled.isNotEmpty()) {
				items.add(AppListItem.Header("Direct Touch Enabled Apps"))
				items.addAll(enabled.map { AppListItem.App(it) })
			}
			if (disabled.isNotEmpty()) {
				items.add(AppListItem.Header("All Apps"))
				items.addAll(disabled.map { AppListItem.App(it) })
			}

			val lazyListState = rememberLazyListState()

			LazyColumn(
				state = lazyListState,
				modifier = Modifier
					.fillMaxSize()
					.semantics {
						verticalScrollAxisRange = ScrollAxisRange(
							value = { lazyListState.firstVisibleItemIndex.toFloat() },
							maxValue = { (items.size - 1).coerceAtLeast(0).toFloat() }
						)
						collectionInfo = CollectionInfo(rowCount = items.size, columnCount = 1)
					}
			) {
				items(items) { item ->
					when (item) {
						is AppListItem.Header -> HeaderRow(item.title)
						is AppListItem.App -> AppRow(
							appInfo = item.appInfo,
							onToggle = { isEnabled -> onAppToggle(item.appInfo, isEnabled) },
							onConfigure = { onAppConfigure(item.appInfo) }
						)
					}
				}
			}
		}
	}
}

@Composable
fun HeaderRow(title: String) {
	Text(
		text = title,
		modifier = Modifier
			.fillMaxWidth()
			.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp)
			.semantics { heading() },
		fontWeight = FontWeight.Bold,
		color = MaterialTheme.colorScheme.primary,
		fontSize = 14.sp
	)
}

@Composable
fun AppRow(
	appInfo: AppInfo,
	onToggle: (Boolean) -> Unit,
	onConfigure: () -> Unit
) {
	val painter = rememberDrawablePainter(appInfo.icon)

	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable { onToggle(!appInfo.isEnabled) }
			.padding(16.dp)
			.clearAndSetSemantics {
				val stateText = if (appInfo.isEnabled) "on" else "off"
				contentDescription = "${appInfo.name}, $stateText"
				role = Role.Switch
				customActions = listOf(
					CustomAccessibilityAction("Configure settings for ${appInfo.name}") {
						onConfigure()
						true
					}
				)
			},
		verticalAlignment = Alignment.CenterVertically
	) {
		Image(
			painter = painter,
			contentDescription = null,
			modifier = Modifier.size(48.dp)
		)
		Spacer(modifier = Modifier.width(16.dp))
		Text(
			text = appInfo.name,
			modifier = Modifier.weight(1f),
			fontSize = 16.sp
		)
		Switch(
			checked = appInfo.isEnabled,
			onCheckedChange = null
		)
	}
}

@Composable
fun rememberDrawablePainter(drawable: Drawable): Painter {
	return remember(drawable) {
		BitmapPainter(drawable.toBitmap().asImageBitmap())
	}
}

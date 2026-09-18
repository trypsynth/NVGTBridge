package dev.nvgt.bridge

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
						prefs.edit().putBoolean("haptics_enabled", enabled).apply()
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
		prefs.edit().putBoolean("direct_typing_${app.packageName}", isEnabled).apply()
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
				prefs.edit().putStringSet("seen_native_packages", seenNativeApps).apply()
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
				if (root.has("seen_native_apps")) {
					val seenArray = root.getJSONArray("seen_native_apps")
					val restoredSeen = mutableSetOf<String>()
					for (i in 0 until seenArray.length()) {
						restoredSeen.add(seenArray.getString(i))
					}
					editor.putStringSet("seen_native_packages", restoredSeen)
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
	serviceEnabled: Boolean,
	onEnableService: () -> Unit,
	hapticsEnabled: Boolean,
	onHapticsChanged: (Boolean) -> Unit,
	searchQuery: String,
	onSearchQueryChanged: (String) -> Unit,
	appsList: List<AppInfo>,
	onAppToggle: (AppInfo, Boolean) -> Unit,
	onDirectTypingChanged: (AppInfo, Boolean) -> Unit,
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
	var configPackage by rememberSaveable { mutableStateOf<String?>(null) }
	val backupFileName = stringResource(R.string.backup_file_name)
	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text(stringResource(R.string.app_name)) },
				colors = TopAppBarDefaults.topAppBarColors(
					containerColor = MaterialTheme.colorScheme.primary,
					titleContentColor = MaterialTheme.colorScheme.onPrimary,
					actionIconContentColor = MaterialTheme.colorScheme.onPrimary
				),
				actions = {
					IconButton(onClick = { showMenu = !showMenu }) {
						Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_more_options))
					}
					DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
						DropdownMenuItem(
							text = { Text(stringResource(R.string.action_backup)) },
							onClick = {
								showMenu = false
								val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
									addCategory(Intent.CATEGORY_OPENABLE)
									type = "application/json"
									putExtra(Intent.EXTRA_TITLE, backupFileName)
								}
								backupLauncher.launch(intent)
							}
						)
						DropdownMenuItem(
							text = { Text(stringResource(R.string.action_restore)) },
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
			if (!serviceEnabled) {
				ServiceDisabledBanner(onClick = onEnableService)
			}
			val hapticsLabel = stringResource(R.string.label_haptics)
			val hapticsState = stringResource(if (hapticsEnabled) R.string.switch_on else R.string.switch_off)
			val hapticsDescription = stringResource(R.string.a11y_switch, hapticsLabel, hapticsState)
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.clickable { onHapticsChanged(!hapticsEnabled) }
					.padding(16.dp)
					.clearAndSetSemantics {
						contentDescription = hapticsDescription
						role = Role.Switch
					},
				verticalAlignment = Alignment.CenterVertically
			) {
				Text(hapticsLabel, modifier = Modifier.weight(1f), fontSize = 18.sp)
				Switch(checked = hapticsEnabled, onCheckedChange = null)
			}
			TextField(
				value = searchQuery,
				onValueChange = onSearchQueryChanged,
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 16.dp),
				placeholder = { Text(stringResource(R.string.label_search)) },
				leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
				singleLine = true
			)
			val listItems = buildAppListItems(
				apps = appsList,
				query = searchQuery,
				enabledHeader = stringResource(R.string.header_enabled_apps),
				allHeader = stringResource(R.string.header_all_apps)
			)
			val lazyListState = rememberLazyListState()
			LazyColumn(
				state = lazyListState,
				modifier = Modifier
					.fillMaxSize()
					.semantics {
						verticalScrollAxisRange = ScrollAxisRange(
							value = { lazyListState.firstVisibleItemIndex.toFloat() },
							maxValue = { (listItems.size - 1).coerceAtLeast(0).toFloat() }
						)
						collectionInfo = CollectionInfo(rowCount = listItems.size, columnCount = 1)
					}
			) {
				items(
					items = listItems,
					key = { item ->
						when (item) {
							is AppListItem.Header -> "header:${item.title}"
							is AppListItem.App -> "app:${item.appInfo.packageName}"
						}
					}
				) { item ->
					when (item) {
						is AppListItem.Header -> HeaderRow(item.title)
						is AppListItem.App -> AppRow(
							appInfo = item.appInfo,
							onToggle = { isEnabled -> onAppToggle(item.appInfo, isEnabled) },
							onConfigure = { configPackage = item.appInfo.packageName }
						)
					}
				}
			}
		}
	}
	val configTarget = configPackage?.let { pkg -> appsList.firstOrNull { it.packageName == pkg } }
	if (configTarget != null) {
		AppConfigDialog(
			appInfo = configTarget,
			onDismiss = { configPackage = null },
			onSave = { directTyping ->
				onDirectTypingChanged(configTarget, directTyping)
				configPackage = null
			}
		)
	}
}

@Composable
fun AppConfigDialog(
	appInfo: AppInfo,
	onDismiss: () -> Unit,
	onSave: (Boolean) -> Unit
) {
	var directTyping by rememberSaveable(appInfo.packageName) { mutableStateOf(appInfo.directTyping) }
	val label = stringResource(R.string.label_direct_typing)
	val state = stringResource(if (directTyping) R.string.switch_on else R.string.switch_off)
	val description = stringResource(R.string.a11y_switch, label, state)
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.config_title, appInfo.name)) },
		text = {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.clickable { directTyping = !directTyping }
					.clearAndSetSemantics {
						contentDescription = description
						role = Role.Switch
					},
				verticalAlignment = Alignment.CenterVertically
			) {
				Text(label, modifier = Modifier.weight(1f))
				Switch(checked = directTyping, onCheckedChange = null)
			}
		},
		confirmButton = {
			TextButton(onClick = { onSave(directTyping) }) {
				Text(stringResource(R.string.action_save))
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss) {
				Text(stringResource(R.string.action_cancel))
			}
		}
	)
}

@Composable
fun ServiceDisabledBanner(onClick: () -> Unit) {
	val message = stringResource(R.string.service_disabled)
	Text(
		text = message,
		color = MaterialTheme.colorScheme.onErrorContainer,
		fontSize = 16.sp,
		modifier = Modifier
			.fillMaxWidth()
			.background(MaterialTheme.colorScheme.errorContainer)
			.clickable(onClick = onClick)
			.padding(16.dp)
			.clearAndSetSemantics {
				contentDescription = message
				role = Role.Button
			}
	)
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
	val icon = rememberAppIcon(appInfo.packageName)
	val state = stringResource(if (appInfo.isEnabled) R.string.switch_on else R.string.switch_off)
	val description = stringResource(R.string.a11y_switch, appInfo.name, state)
	val configureLabel = stringResource(R.string.config_title, appInfo.name)
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable { onToggle(!appInfo.isEnabled) }
			.padding(16.dp)
			.clearAndSetSemantics {
				contentDescription = description
				role = Role.Switch
				customActions = listOf(
					CustomAccessibilityAction(configureLabel) {
						onConfigure()
						true
					}
				)
			},
		verticalAlignment = Alignment.CenterVertically
	) {
		if (icon != null) {
			Image(
				bitmap = icon,
				contentDescription = null,
				modifier = Modifier.size(48.dp)
			)
		} else {
			Spacer(modifier = Modifier.size(48.dp))
		}
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
fun rememberAppIcon(packageName: String): ImageBitmap? {
	val context = LocalContext.current
	var icon by remember(packageName) { mutableStateOf<ImageBitmap?>(null) }
	LaunchedEffect(packageName) {
		icon = withContext(Dispatchers.IO) {
			runCatching {
				context.packageManager.getApplicationIcon(packageName).toBitmap().asImageBitmap()
			}.getOrNull()
		}
	}
	return icon
}

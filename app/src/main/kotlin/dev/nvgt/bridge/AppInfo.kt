package dev.nvgt.bridge

data class AppInfo(
	val name: String,
	val packageName: String,
	val isEnabled: Boolean,
	val directTyping: Boolean
)

sealed class AppListItem {
	data class Header(val title: String) : AppListItem()
	data class App(val appInfo: AppInfo) : AppListItem()
}

fun buildAppListItems(
	apps: List<AppInfo>,
	query: String,
	enabledHeader: String,
	allHeader: String
): List<AppListItem> {
	val matches = if (query.isEmpty()) apps else apps.filter { it.name.contains(query, ignoreCase = true) }
	val enabled = matches.filter { it.isEnabled }.sortedBy { it.name }
	val disabled = matches.filter { !it.isEnabled }.sortedBy { it.name }
	val items = mutableListOf<AppListItem>()
	if (enabled.isNotEmpty()) {
		items.add(AppListItem.Header(enabledHeader))
		enabled.forEach { items.add(AppListItem.App(it)) }
	}
	if (disabled.isNotEmpty()) {
		items.add(AppListItem.Header(allHeader))
		disabled.forEach { items.add(AppListItem.App(it)) }
	}
	return items
}

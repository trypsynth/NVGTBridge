package dev.nvgt.bridge

import java.text.Collator

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

private val nameOrder: Comparator<AppInfo> = run {
	val collator = Collator.getInstance()
	collator.strength = Collator.SECONDARY
	Comparator { a, b -> collator.compare(a.name, b.name) }
}

fun buildAppListItems(
	apps: List<AppInfo>,
	query: String,
	enabledHeader: String,
	allHeader: String
): List<AppListItem> {
	val matches = if (query.isEmpty()) apps else apps.filter { it.name.contains(query, ignoreCase = true) }
	val enabled = matches.filter { it.isEnabled }.sortedWith(nameOrder)
	val disabled = matches.filter { !it.isEnabled }.sortedWith(nameOrder)
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

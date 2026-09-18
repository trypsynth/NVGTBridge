package dev.nvgt.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppListTest {

	private fun app(name: String, enabled: Boolean = false) = AppInfo(
		name = name,
		packageName = "pkg.${name.lowercase()}",
		isEnabled = enabled,
		directTyping = false
	)

	private fun titles(items: List<AppListItem>) = items.map { item ->
		when (item) {
			is AppListItem.Header -> item.title
			is AppListItem.App -> item.appInfo.name
		}
	}

	@Test
	fun `no apps produces no headers`() {
		assertTrue(buildAppListItems(emptyList(), "", "Enabled", "All").isEmpty())
	}

	@Test
	fun `enabled apps come first under their own header`() {
		val items = buildAppListItems(
			listOf(app("Quiz"), app("Runner", enabled = true)),
			"",
			"Enabled",
			"All"
		)
		assertEquals(listOf("Enabled", "Runner", "All", "Quiz"), titles(items))
	}

	@Test
	fun `the enabled header is omitted when nothing is enabled`() {
		val items = buildAppListItems(listOf(app("Quiz")), "", "Enabled", "All")
		assertEquals(listOf("All", "Quiz"), titles(items))
	}

	@Test
	fun `the all apps header is omitted when everything is enabled`() {
		val items = buildAppListItems(listOf(app("Quiz", enabled = true)), "", "Enabled", "All")
		assertEquals(listOf("Enabled", "Quiz"), titles(items))
	}

	@Test
	fun `apps are sorted by name ignoring case`() {
		val items = buildAppListItems(
			listOf(app("Zebra"), app("apple"), app("Mango")),
			"",
			"Enabled",
			"All"
		)
		assertEquals(listOf("All", "apple", "Mango", "Zebra"), titles(items))
	}

	@Test
	fun `lowercase names are not pushed past uppercase ones`() {
		val items = buildAppListItems(
			listOf(app("YouTube Music"), app("appstats"), app("eero"), app("Gmail"), app("echobe")),
			"",
			"Enabled",
			"All"
		)
		assertEquals(listOf("All", "appstats", "echobe", "eero", "Gmail", "YouTube Music"), titles(items))
	}

	@Test
	fun `case insensitive ordering applies inside the enabled section too`() {
		val items = buildAppListItems(
			listOf(app("Zebra", enabled = true), app("apple", enabled = true)),
			"",
			"Enabled",
			"All"
		)
		assertEquals(listOf("Enabled", "apple", "Zebra"), titles(items))
	}

	@Test
	fun `search ignores case`() {
		val items = buildAppListItems(listOf(app("Constant Motion"), app("Quiz")), "MOTION", "Enabled", "All")
		assertEquals(listOf("All", "Constant Motion"), titles(items))
	}

	@Test
	fun `a search that matches nothing produces no headers`() {
		val items = buildAppListItems(listOf(app("Quiz")), "nothing matches this", "Enabled", "All")
		assertTrue(items.isEmpty())
	}

	@Test
	fun `search spans both sections`() {
		val items = buildAppListItems(
			listOf(app("Audio Quiz", enabled = true), app("Audio Runner"), app("Chess")),
			"audio",
			"Enabled",
			"All"
		)
		assertEquals(listOf("Enabled", "Audio Quiz", "All", "Audio Runner"), titles(items))
	}
}

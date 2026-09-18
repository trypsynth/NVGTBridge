package dev.nvgt.bridge

import android.graphics.Rect
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PassthroughRegionTest {

	private val display = Rect(0, 0, 1080, 2400)

	@Test
	fun coversTheWholeDisplayWhenNothingIsExcluded() {
		assertEquals(display, passthroughRegion(display, emptyList()).bounds)
	}

	@Test
	fun cutsOutAnExcludedWindow() {
		val keyboard = Rect(0, 1600, 1080, 2400)
		val region = passthroughRegion(display, listOf(keyboard))
		assertFalse(region.contains(540, 2000))
		assertTrue(region.contains(540, 800))
	}

	@Test
	fun isEmptyWhenAnExcludedWindowCoversTheDisplay() {
		assertTrue(passthroughRegion(display, listOf(Rect(display))).isEmpty)
	}

	@Test
	fun keepsTheKeyboardWhenDirectTypingIsOn() {
		assertFalse(shouldExcludeWindow(AccessibilityWindowInfo.TYPE_INPUT_METHOD, directTyping = true))
	}

	@Test
	fun cutsOutTheKeyboardWhenDirectTypingIsOff() {
		assertTrue(shouldExcludeWindow(AccessibilityWindowInfo.TYPE_INPUT_METHOD, directTyping = false))
	}

	@Test
	fun alwaysCutsOutSystemAndOverlayWindows() {
		assertTrue(shouldExcludeWindow(AccessibilityWindowInfo.TYPE_SYSTEM, directTyping = true))
		assertTrue(shouldExcludeWindow(AccessibilityWindowInfo.TYPE_SYSTEM, directTyping = false))
		assertTrue(shouldExcludeWindow(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY, directTyping = true))
		assertTrue(shouldExcludeWindow(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY, directTyping = false))
	}

	@Test
	fun neverCutsOutApplicationWindows() {
		assertFalse(shouldExcludeWindow(AccessibilityWindowInfo.TYPE_APPLICATION, directTyping = true))
		assertFalse(shouldExcludeWindow(AccessibilityWindowInfo.TYPE_APPLICATION, directTyping = false))
	}
}

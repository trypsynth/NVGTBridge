package dev.nvgt.bridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NvgtUtilsTest {

	private val context = InstrumentationRegistry.getInstrumentation().targetContext

	@Test
	fun reportsNoSupportForAnAppWithoutTheMetaData() {
		assertFalse(NvgtUtils.hasNvgtSupport(context.packageManager, context.packageName))
	}

	@Test
	fun reportsNoSupportForAPackageThatIsNotInstalled() {
		assertFalse(NvgtUtils.hasNvgtSupport(context.packageManager, "dev.nvgt.bridge.absent"))
	}
}

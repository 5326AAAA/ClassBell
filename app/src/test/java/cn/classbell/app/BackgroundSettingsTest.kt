package cn.classbell.app

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = BellApp::class, qualifiers = "zh-rCN")
@LooperMode(LooperMode.Mode.PAUSED)
class BackgroundSettingsTest {
    private var controller: ActivityController<MainActivity>? = null
    private val app get() = RuntimeEnvironment.getApplication() as BellApp

    @Before fun cleanState() {
        app.prefs.edit().clear().commit()
        ShadowBuild.setManufacturer("Xiaomi")
        ShadowBuild.setBrand("Redmi")
    }

    @After fun cleanup() { controller?.pause()?.stop()?.destroy() }

    private fun main(): MainActivity = Robolectric.buildActivity(MainActivity::class.java).setup()
        .also { controller = it }.get()

    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()

    private fun button(activity: MainActivity, text: String): Button = views(activity.window.decorView)
        .filterIsInstance<Button>().single { it.text.toString() == text }

    private fun labels(root: View) = views(root).filterIsInstance<TextView>().joinToString("\n") { it.text }

    private fun choosePhone(activity: MainActivity, family: PhoneFamily?) {
        val spinner = views(activity.window.decorView).filterIsInstance<Spinner>()
            .single { it.contentDescription == "选择手机类型" }
        spinner.setSelection(BackgroundSettings.choices.indexOf(family))
        val root = activity.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1080, 2400)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun honorCanSwitchAllPhoneGuidesAndReturnToAutomaticWithoutStoppingMonitoring() {
        ShadowBuild.setManufacturer("HONOR")
        ShadowBuild.setBrand("HONOR")
        val activity = main()
        app.prefs.edit().putBoolean("monitorEnabled", true).putString("alert", "已有提醒").commit()
        for (family in listOf(PhoneFamily.OPPO_ONEPLUS, PhoneFamily.XIAOMI, PhoneFamily.OTHER, PhoneFamily.HONOR, null)) {
            choosePhone(activity, family)
            assertEquals(family, BackgroundSettings.selection(app.prefs))
            assertTrue(labels(activity.window.decorView).contains(BackgroundSettings.title(family ?: PhoneFamily.HONOR)))
            assertEquals(if (family == PhoneFamily.XIAOMI) View.VISIBLE else View.GONE,
                button(activity, "小米锁屏显示和后台弹出设置").visibility)
            button(activity, "设置自启动和后台运行").performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(labels(dialog.window!!.decorView).contains(BackgroundSettings.title(family ?: PhoneFamily.HONOR)))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val request = shadowOf(activity).nextStartedActivity
            assertEquals(if (family == PhoneFamily.XIAOMI) "miui.intent.action.OP_AUTO_START"
                else Settings.ACTION_APPLICATION_DETAILS_SETTINGS, request.action)
        }
        assertTrue(app.prefs.getBoolean("monitorEnabled", false))
        assertEquals("已有提醒", app.prefs.getString("alert", null))
        assertNull(shadowOf(activity).nextStartedService)
        app.prefs.edit().putBoolean("monitorEnabled", false).apply()
    }

    @Test fun manualChoiceSurvivesActivityRecreationAndInvalidChoiceFallsBackToDevice() {
        val activity = main()
        choosePhone(activity, PhoneFamily.OPPO_ONEPLUS)
        controller!!.recreate()
        val reopened = controller!!.get()
        assertEquals(PhoneFamily.OPPO_ONEPLUS, BackgroundSettings.effectiveFamily(app.prefs))
        assertTrue(labels(reopened.window.decorView).contains("OPPO / 一加后台设置"))
        app.prefs.edit().putString("backgroundPhoneType", "removed-option").commit()
        controller!!.recreate()
        assertEquals(PhoneFamily.XIAOMI, BackgroundSettings.effectiveFamily(app.prefs))
        assertTrue(labels(controller!!.get().window.decorView).contains("小米 / Redmi 后台设置"))
    }

    @Test fun topLeftHelpMenuOpensBothReadableDialogsAndUsesChosenPhoneGuide() {
        val activity = main()
        choosePhone(activity, PhoneFamily.OPPO_ONEPLUS)
        button(activity, "☰ 使用帮助").performClick()
        var sheet=ShadowAlertDialog.getLatestAlertDialog()
        fun entry(root:View,title:String):View? {
            if(root.contentDescription==title)return root
            if(root is ViewGroup)for(i in 0 until root.childCount)entry(root.getChildAt(i),title)?.let{return it}
            return null
        }
        entry(sheet.window!!.decorView,"使用指南")!!.performClick()
        var dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(labels(dialog.window!!.decorView).contains("当前指引：OPPO / 一加"))
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(labels(dialog.window!!.decorView).contains("OPPO / 一加后台设置"))
        dialog.dismiss()
        button(activity, "☰ 使用帮助").performClick()
        sheet=ShadowAlertDialog.getLatestAlertDialog()
        entry(sheet.window!!.decorView,"常见问题处理方法")!!.performClick()
        dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(labels(dialog.window!!.decorView).contains("息屏后不响"))
        assertTrue(labels(dialog.window!!.decorView).contains("手机类型显示不对"))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertNull(shadowOf(activity).nextStartedService)
    }

    @Test fun recognizesXiaomiRedmiAndPocoWithoutMisclassifyingOtherPhones() {
        assertEquals(PhoneFamily.XIAOMI, BackgroundSettings.family("Xiaomi", "Redmi"))
        assertEquals(PhoneFamily.XIAOMI, BackgroundSettings.family("unknown", "POCO"))
        assertEquals(PhoneFamily.XIAOMI, BackgroundSettings.family(" REDMI ", "unknown"))
        assertEquals(PhoneFamily.HONOR, BackgroundSettings.family("HONOR", "HONOR"))
        assertEquals(PhoneFamily.OTHER, BackgroundSettings.family("Google", "google"))
        assertEquals(PhoneFamily.OTHER, BackgroundSettings.family("", ""))
    }

    @Test fun recognizesOppoAndOnePlusFromEitherDeviceField() {
        listOf("OPPO" to "oppo", "OnePlus" to "OnePlus", "OPPO" to "OnePlus",
            "unknown" to " ONEPLUS ", "one plus" to "unknown", "unknown" to "一加").forEach { (maker, brand) ->
            assertEquals(PhoneFamily.OPPO_ONEPLUS, BackgroundSettings.family(maker, brand))
        }
        assertEquals(PhoneFamily.OTHER, BackgroundSettings.family("realme", "realme"))
    }

    private fun verifyOppoGuide(manufacturer: String, brand: String) {
        ShadowBuild.setManufacturer(manufacturer)
        ShadowBuild.setBrand(brand)
        val activity = main()
        val labels = views(activity.window.decorView).filterIsInstance<TextView>().joinToString("\n") { it.text }
        assertTrue(labels.contains("OPPO / 一加后台设置"))
        assertTrue(labels.contains("请到系统设置手动核对"))
        assertNull(shadowOf(activity).nextStartedActivity)
        button(activity, "设置自启动和后台运行").performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val message = views(dialog.window!!.decorView).filterIsInstance<TextView>().joinToString("\n") { it.text }
        listOf("学习通", "自启动", "后台活动", "锁屏通知", "OxygenOS", "打开设置不代表已授权").forEach {
            assertTrue("Missing guidance: $it", message.contains(it))
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val request = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, request.action)
        assertEquals("package:${app.packageName}", request.data.toString())
    }

    @Test fun oppoShowsItsGuideAndOpensThisAppsSettings() = verifyOppoGuide("OPPO", "OPPO")

    @Test fun onePlusShowsItsGuideAndOpensThisAppsSettings() = verifyOppoGuide("OnePlus", "OnePlus")

    @Test fun onePlusOffersVendorGuideEvenWhenStandardBatteryExemptionIsGranted() {
        ShadowBuild.setManufacturer("OnePlus")
        ShadowBuild.setBrand("OnePlus")
        shadowOf(app.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(app.packageName, true)
        val activity = main()
        val offer = MainActivity::class.java.getDeclaredMethod("offerBackgroundSetup").apply { isAccessible = true }
        offer.invoke(activity)
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.isShowing)
        val message = views(dialog.window!!.decorView).filterIsInstance<TextView>().joinToString("\n") { it.text }
        assertTrue(message.contains("OPPO 和一加"))
        dialog.dismiss()
        offer.invoke(activity)
        assertFalse("Offer should only appear once", dialog.isShowing)
    }

    @Test fun unavailableStandardAppDetailsFallsBackWithoutRetryingSameEntry() {
        val attempts = mutableListOf<Intent>()
        assertTrue(BackgroundSettings.openWithFallback(app.packageName, BackgroundSettings.appDetailsIntent(app.packageName)) {
            attempts.add(it)
            if (attempts.size == 1) throw ActivityNotFoundException()
        })
        assertEquals(2, attempts.size)
        assertEquals(Settings.ACTION_SETTINGS, attempts.last().action)
    }

    @Test fun openingMainDoesNotAutomaticallyLaunchPhoneSettings() {
        val activity = main()
        assertNull(shadowOf(activity).nextStartedActivity)
        assertNotNull(button(activity, "小米锁屏显示和后台弹出设置"))
    }

    @Test fun xiaomiAutostartButtonWaitsForUserAndThenOpensDocumentedSystemEntry() {
        val activity = main()
        button(activity, "设置自启动和后台运行").performClick()
        assertNull(shadowOf(activity).nextStartedActivity)
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val request = shadowOf(activity).nextStartedActivity
        assertEquals("miui.intent.action.OP_AUTO_START", request.action)
        assertTrue(request.hasCategory(Intent.CATEGORY_DEFAULT))
    }

    @Test fun xiaomiOtherPermissionsOpensForThisAppOnly() {
        val activity = main()
        button(activity, "小米锁屏显示和后台弹出设置").performClick()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val request = shadowOf(activity).nextStartedActivity
        assertEquals("miui.intent.action.APP_PERM_EDITOR", request.action)
        assertEquals(app.packageName, request.getStringExtra("extra_pkgname"))
    }

    @Test fun honorKeepsItsGuideAndStandardAppSettings() {
        ShadowBuild.setManufacturer("HONOR")
        ShadowBuild.setBrand("HONOR")
        val activity = main()
        assertEquals(View.GONE, button(activity, "小米锁屏显示和后台弹出设置").visibility)
        button(activity, "设置自启动和后台运行").performClick()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val request = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, request.action)
        assertEquals("package:${app.packageName}", request.data.toString())
    }

    @Test fun missingVendorEntryFallsBackToTheCorrectAppDetails() {
        val attempts = mutableListOf<Intent>()
        val result = BackgroundSettings.openWithFallback(app.packageName, BackgroundSettings.xiaomiAutostartIntent()) {
            attempts.add(it)
            if (attempts.size == 1) throw ActivityNotFoundException()
        }
        assertTrue(result)
        assertEquals(2, attempts.size)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, attempts.last().action)
        assertEquals("package:${app.packageName}", attempts.last().data.toString())
    }

    @Test fun deniedVendorAndDetailsEntriesFallBackToSystemSettings() {
        val attempts = mutableListOf<Intent>()
        val result = BackgroundSettings.openWithFallback(app.packageName, BackgroundSettings.xiaomiPermissionsIntent(app.packageName)) {
            attempts.add(it)
            if (attempts.size < 3) throw SecurityException("System entry is not exported")
        }
        assertTrue(result)
        assertEquals(3, attempts.size)
        assertEquals(Settings.ACTION_SETTINGS, attempts.last().action)
    }

    @Test fun allUnavailableEntriesReturnFailureForManualGuidance() {
        var attempts = 0
        assertFalse(BackgroundSettings.openWithFallback(app.packageName, BackgroundSettings.xiaomiAutostartIntent()) {
            attempts++
            throw ActivityNotFoundException()
        })
        assertEquals(3, attempts)
    }
}

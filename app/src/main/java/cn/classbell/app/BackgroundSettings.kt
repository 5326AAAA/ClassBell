package cn.classbell.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.util.Locale

enum class PhoneFamily { XIAOMI, HONOR, OPPO_ONEPLUS, OTHER }

object BackgroundSettings {
    private const val PHONE_TYPE = "backgroundPhoneType"
    val choices: List<PhoneFamily?> = listOf(null, PhoneFamily.HONOR, PhoneFamily.XIAOMI, PhoneFamily.OPPO_ONEPLUS, PhoneFamily.OTHER)

    fun selection(prefs: SharedPreferences): PhoneFamily? =
        PhoneFamily.entries.firstOrNull { it.name == prefs.getString(PHONE_TYPE, null) }

    fun select(prefs: SharedPreferences, family: PhoneFamily?) {
        prefs.edit().putString(PHONE_TYPE, family?.name).apply()
    }

    fun effectiveFamily(prefs: SharedPreferences): PhoneFamily = selection(prefs) ?: family()

    fun name(family: PhoneFamily) = when (family) {
        PhoneFamily.HONOR -> "荣耀"
        PhoneFamily.XIAOMI -> "小米 / Redmi / POCO"
        PhoneFamily.OPPO_ONEPLUS -> "OPPO / 一加"
        PhoneFamily.OTHER -> "其他安卓"
    }

    fun choiceLabels(): List<String> = choices.map { it?.let(::name) ?: "自动识别（${name(family())}）" }

    fun family(manufacturer: String = Build.MANUFACTURER, brand: String = Build.BRAND): PhoneFamily {
        val names = listOf(manufacturer, brand).map { it.trim().lowercase(Locale.ROOT) }
        return when {
            names.any { it in setOf("xiaomi", "redmi", "poco") } -> PhoneFamily.XIAOMI
            "honor" in names -> PhoneFamily.HONOR
            names.any { it in setOf("oppo", "oneplus", "one plus", "一加") } -> PhoneFamily.OPPO_ONEPLUS
            else -> PhoneFamily.OTHER
        }
    }

    fun title(family: PhoneFamily) = when (family) {
        PhoneFamily.XIAOMI -> "小米 / Redmi 后台设置"
        PhoneFamily.HONOR -> "荣耀后台设置"
        PhoneFamily.OPPO_ONEPLUS -> "OPPO / 一加后台设置"
        PhoneFamily.OTHER -> "后台与锁屏设置"
    }

    fun summary(family: PhoneFamily) = when (family) {
        PhoneFamily.XIAOMI -> "适用于小米、Redmi、POCO 的 HyperOS / MIUI。请检查自启动、省电策略“无限制”、后台任务加锁，以及锁屏显示 / 后台弹出界面。学习通也要允许后台运行和通知，才能提供原始签到通知。"
        PhoneFamily.HONOR -> "请在应用启动管理中允许自启动、关联启动和后台活动，并在最近任务中加锁；学习通也要允许后台运行和通知。"
        PhoneFamily.OPPO_ONEPLUS -> "适用于 OPPO 和一加的 ColorOS / OxygenOS。请检查自启动、允许后台活动 / 后台耗电、最近任务加锁，以及锁屏和横幅通知；学习通也要允许后台运行和通知。菜单以手机实际显示为准。"
        PhoneFamily.OTHER -> "请在系统应用设置中检查后台运行、电池和通知权限；学习通也要允许后台运行和通知。菜单名称以手机实际显示为准。"
    }

    fun guide(family: PhoneFamily) = when (family) {
        PhoneFamily.XIAOMI -> "请分别检查“签到震动提醒”和“学习通”：\n\n" +
            "1. 系统设置 → 应用设置 / 应用 → 授权管理 / 权限 → 自启动 / 后台自启动，允许这两个 App。\n\n" +
            "2. 各自应用详情 → 省电策略 / 电池，选择“无限制”。需要后台保护时，在最近任务中长按卡片点锁；部分机型在手机管家 → 设置 → 优化加速 → 锁定任务中设置。\n\n" +
            "3. 允许通知、悬浮通知和锁屏通知。本 App 的“其他权限”中如有“锁屏显示”“后台弹出界面”，请手动允许。学习通内部的消息提醒也需开启。\n\n" +
            "4. 返回本 App，检查电池优化例外、闹钟和全屏提醒，再做“10 秒后锁屏闹铃测试”。\n\n" +
            "菜单因 HyperOS / MIUI 版本而异。这里只打开设置，授权由你确认；自启动等厂商权限需手动核对。"
        PhoneFamily.HONOR -> "在系统设置 → 应用 → 应用启动管理，找到“签到震动提醒”，关闭自动管理，允许自启动、关联启动和后台活动；再在最近任务中给本 App 加锁，并允许电池优化例外。\n\n学习通也需要允许后台运行和通知。完成后返回，使用 10 秒锁屏闹铃测试检查提醒。"
        PhoneFamily.OPPO_ONEPLUS -> "请分别检查“签到震动提醒”和“学习通”：\n\n" +
            "1. 系统设置 → 应用 → 自启动 / 自启动管理，允许这两个 App 自启动；如有“关联启动”，也请允许。找不到自启动菜单时，继续检查下一步的后台设置。\n\n" +
            "2. 应用 → 应用管理 → 对应 App → 耗电管理 / 电池使用情况，选择“允许后台活动”“允许完全后台行为”或“无限制”等允许后台运行的选项。部分版本入口在设置 → 电池 → 应用耗电管理；旧版 OxygenOS 可在电池优化中选择“不优化”。\n\n" +
            "3. 打开最近任务，在 App 卡片菜单中选择“锁定”；部分版本可下拉卡片加锁。\n\n" +
            "4. 在两个 App 的通知管理中允许通知、锁屏通知和横幅通知。学习通内部的消息提醒也需开启。本 App 再检查“签到紧急提醒”分类、闹钟和全屏提醒权限，并调整闹钟音量。\n\n" +
            "5. 返回本 App，先做“10 秒后锁屏闹铃测试”和本地模拟，再观察真实学习通签到通知及长时间锁屏提醒。\n\n" +
            "下面打开本 App 的系统应用详情，请按上述步骤手动设置；学习通需另行长按图标打开应用信息。菜单因 ColorOS / OxygenOS 版本而异，可在系统设置搜索“自启动”“耗电管理”“电池优化”。打开设置不代表已授权。"
        PhoneFamily.OTHER -> "在本 App 的系统应用设置中允许通知、后台运行，并按需设置电池优化例外；检查锁屏和悬浮通知。学习通也需要允许后台运行和通知。\n\n完成后返回，使用 10 秒锁屏闹铃测试检查提醒。菜单以手机实际显示为准。"
    }

    // These public MIUI actions are documented by Xiaomi. HyperOS builds may omit them.
    fun xiaomiAutostartIntent() = Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT)

    fun xiaomiPermissionsIntent(packageName: String) = Intent("miui.intent.action.APP_PERM_EDITOR")
        .addCategory(Intent.CATEGORY_DEFAULT).putExtra("extra_pkgname", packageName)

    fun appDetailsIntent(packageName: String) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))

    fun openWithFallback(packageName: String, primary: Intent, launch: (Intent) -> Unit): Boolean {
        val candidates = listOf(primary, appDetailsIntent(packageName), Intent(Settings.ACTION_SETTINGS))
            .distinctBy { it.toUri(0) }
        for (intent in candidates) {
            try {
                launch(intent)
                return true
            } catch (diagnosticError: ActivityNotFoundException) { DiagnosticLog.error("BackgroundSettings", diagnosticError);
                // Try the standard app settings when a vendor entry is absent.
            } catch (diagnosticError: SecurityException) { DiagnosticLog.error("BackgroundSettings", diagnosticError);
                // Some system builds expose an entry but do not allow third-party launches.
            }
        }
        return false
    }
}

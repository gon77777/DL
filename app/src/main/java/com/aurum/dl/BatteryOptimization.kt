package com.aurum.dl

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * 電池の最適化 (Doze) の対象外にするための補助。
 * 対象のままだと、画面が消えてしばらく経つと通信が止められ、バックグラウンドのダウンロードが途中で止まる。
 */
object BatteryOptimization {

    fun isIgnoring(context: Context): Boolean {
        val pm = context.applicationContext.getSystemService(PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** 「このアプリを制限しない」を許可する確認画面。開けない端末では設定一覧を開く。 */
    @SuppressLint("BatteryLife")
    fun requestIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:" + context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun settingsIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

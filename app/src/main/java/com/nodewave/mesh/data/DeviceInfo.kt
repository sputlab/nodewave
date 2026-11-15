// SPDX-License-Identifier: AGPL-3.0-or-later
package com.nodewave.mesh.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.view.Display
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import com.nodewave.mesh.protocol.BluetoothFeatures
import com.nodewave.mesh.protocol.DeviceField
import com.nodewave.mesh.protocol.DeviceReport
import com.nodewave.mesh.protocol.DeviceReport.Companion.yesNo
import com.nodewave.mesh.transport.MeshLog
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/**
 * Reads the hardware and system facts of a [DeviceReport]: only capabilities, versions and on/off states, each into
 * its [DeviceField]. Nothing here reads an identifier (no serial, IMEI, Android ID, MAC), and nothing needs a
 * permission the app does not already hold: a query Android refuses becomes "unknown".
 */
object DeviceInfo {

    /** [app] carries the NodeWave lines, which come from the engine rather than from the phone. */
    fun report(context: Context, app: Map<DeviceField, String?>): DeviceReport {
        val v = HashMap<DeviceField, String?>(app)
        fun put(f: DeviceField, read: () -> String?) {
            v[f] = runCatching(read).onFailure { MeshLog.d("Device report: ${f.name} not readable (${it.javaClass.simpleName})") }.getOrNull()
        }
        val pm = context.packageManager

        put(DeviceField.APP_VERSION) { pm.getPackageInfo(context.packageName, 0).versionName }
        put(DeviceField.APP_BUILD) { PackageInfoCompat.getLongVersionCode(pm.getPackageInfo(context.packageName, 0)).toString() }
        put(DeviceField.INSTALLED_BY) { installer(context) }

        put(DeviceField.MANUFACTURER) { Build.MANUFACTURER }
        put(DeviceField.BRAND) { Build.BRAND }
        put(DeviceField.MODEL) { Build.MODEL }
        put(DeviceField.CODENAME) { Build.DEVICE }
        put(DeviceField.CHIPSET) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Build.SOC_MODEL != Build.UNKNOWN) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}"
            else Build.HARDWARE
        }
        put(DeviceField.CPU_ABIS) { Build.SUPPORTED_ABIS.joinToString(", ") }
        put(DeviceField.CPU_CORES) { Runtime.getRuntime().availableProcessors().toString() }
        put(DeviceField.RAM) {
            val info = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
            String.format(Locale.US, "%.1f GB", info.totalMem / 1_073_741_824.0)
        }
        put(DeviceField.SCREEN) { screen(context) }

        put(DeviceField.ANDROID_VERSION) { Build.VERSION.RELEASE }
        put(DeviceField.API_LEVEL) { Build.VERSION.SDK_INT.toString() }
        put(DeviceField.SECURITY_PATCH) { Build.VERSION.SECURITY_PATCH }
        put(DeviceField.BUILD_TYPE) { Build.TYPE }

        bluetooth(context, v)

        put(DeviceField.WIFI_DIRECT) { yesNo(pm.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) }
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        put(DeviceField.WIFI_5GHZ) { yesNo(wifi.is5GHzBandSupported) }
        put(DeviceField.WIFI_STANDARDS) { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) wifiStandards(wifi) else null }
        put(DeviceField.GPS) { yesNo(pm.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)) }
        put(DeviceField.USB_HOST) { yesNo(pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) }
        put(DeviceField.CAMERA) { yesNo(pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) }
        put(DeviceField.MICROPHONE) { yesNo(pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) }

        fun granted(vararg names: String) = names.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        fun perm(ok: Boolean) = if (ok) "granted" else "not granted"
        put(DeviceField.PERM_BLUETOOTH) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                perm(granted(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT))
            else "not needed (Android 10-11 uses location)"
        }
        put(DeviceField.PERM_LOCATION) { perm(granted(Manifest.permission.ACCESS_FINE_LOCATION)) }
        put(DeviceField.PERM_NEARBY_WIFI) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perm(granted(Manifest.permission.NEARBY_WIFI_DEVICES)) else "not needed (before Android 13)"
        }
        put(DeviceField.PERM_NOTIFICATIONS) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perm(granted(Manifest.permission.POST_NOTIFICATIONS)) else "not needed (before Android 13)"
        }
        put(DeviceField.PERM_MICROPHONE) { perm(granted(Manifest.permission.RECORD_AUDIO)) }
        put(DeviceField.PERM_CAMERA) { perm(granted(Manifest.permission.CAMERA)) }
        put(DeviceField.LOCATION_SERVICES) { if (context.getSystemService(LocationManager::class.java).isLocationEnabled) "on" else "off" }
        put(DeviceField.BATTERY_OPTIMISATION) {
            if (context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)) "not optimised (exempt)" else "optimised"
        }
        put(DeviceField.BACKGROUND_RESTRICTED) { yesNo(context.getSystemService(ActivityManager::class.java).isBackgroundRestricted) }

        MeshLog.d("Device report built: ${v.count { it.value != null }}/${DeviceField.entries.size} fields known")
        return DeviceReport(v, LocalDate.now(ZoneOffset.UTC).toString())
    }

    /** The feature queries need no permission, but some vendors guard them anyway: such a query reads "unknown". */
    @SuppressLint("MissingPermission")
    private fun bluetooth(context: Context, v: MutableMap<DeviceField, String?>) {
        val le = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        v[DeviceField.BT_LE] = yesNo(le)
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) { v[DeviceField.BT_ENABLED] = "none"; v[DeviceField.BT_VERSION] = "no Bluetooth"; return }
        fun <T> ask(read: () -> T): T? = runCatching(read).getOrNull()
        v[DeviceField.BT_ENABLED] = ask { if (adapter.isEnabled) "on" else "off" }
        val twoM = ask { adapter.isLe2MPhySupported }
        val coded = ask { adapter.isLeCodedPhySupported }
        val ext = ask { adapter.isLeExtendedAdvertisingSupported }
        val audio = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ask { adapter.isLeAudioSupported }?.let {
            when (it) { BluetoothStatusCodes.FEATURE_SUPPORTED -> true; BluetoothStatusCodes.FEATURE_NOT_SUPPORTED -> false; else -> null }
        } else null
        v[DeviceField.BT_2M_PHY] = yesNo(twoM)
        v[DeviceField.BT_CODED_PHY] = yesNo(coded)
        v[DeviceField.BT_EXT_ADV] = yesNo(ext)
        v[DeviceField.BT_PERIODIC_ADV] = yesNo(ask { adapter.isLePeriodicAdvertisingSupported })
        v[DeviceField.BT_MULTI_ADV] = yesNo(ask { adapter.isMultipleAdvertisementSupported })
        v[DeviceField.BT_MAX_ADV_DATA] = ask { adapter.leMaximumAdvertisingDataLength }?.let { "$it bytes" }
        v[DeviceField.BT_OFFLOAD_FILTER] = yesNo(ask { adapter.isOffloadedFilteringSupported })
        v[DeviceField.BT_OFFLOAD_BATCH] = yesNo(ask { adapter.isOffloadedScanBatchingSupported })
        v[DeviceField.BT_LE_AUDIO] = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) yesNo(audio) else "not reported (before Android 13)"
        v[DeviceField.BT_VERSION] = DeviceReport.bluetoothVersion(BluetoothFeatures(twoM, coded, ext, audio))
    }

    /** Resolution of the panel itself, not of the app's window, with its density and refresh rate. */
    private fun screen(context: Context): String {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val mode = display.mode
        val dpi = context.resources.displayMetrics.densityDpi
        return String.format(Locale.US, "%d × %d px, %d dpi, %.0f Hz", mode.physicalWidth, mode.physicalHeight, dpi, mode.refreshRate)
    }

    @SuppressLint("NewApi")
    private fun wifiStandards(wifi: WifiManager): String {
        val known = listOf(
            ScanResult.WIFI_STANDARD_11N to "4 (n)", ScanResult.WIFI_STANDARD_11AC to "5 (ac)", ScanResult.WIFI_STANDARD_11AX to "6 (ax)"
        ) + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf(ScanResult.WIFI_STANDARD_11BE to "7 (be)") else emptyList()
        return known.filter { wifi.isWifiStandardSupported(it.first) }.joinToString(", ") { "Wi-Fi ${it.second}" }.ifEmpty { "none reported" }
    }

    /** The app that installed NodeWave (F-Droid, a browser, adb): tells a store build from a sideloaded one. */
    @Suppress("DEPRECATION")
    private fun installer(context: Context): String {
        val pm = context.packageManager
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) pm.getInstallSourceInfo(context.packageName).installingPackageName
        else pm.getInstallerPackageName(context.packageName)
        return when (name) {
            null -> "not recorded (adb or sideload)"
            "org.fdroid.fdroid", "org.fdroid.basic" -> "F-Droid ($name)"
            "com.android.shell" -> "adb ($name)"
            else -> name
        }
    }
}

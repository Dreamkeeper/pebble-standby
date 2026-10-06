package org.cryomonitor.companion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.cryomonitor.companion.ui.AdvancedScreen
import org.cryomonitor.companion.ui.Detector
import org.cryomonitor.companion.ui.DetectorScreen
import org.cryomonitor.companion.ui.WhenItAsksScreen
import org.cryomonitor.companion.ui.CmTheme
import org.cryomonitor.companion.ui.ContactsScreen
import org.cryomonitor.companion.ui.EnrollScreen
import org.cryomonitor.companion.ui.HomeScreen
import org.cryomonitor.companion.ui.PebbleScreen
import org.cryomonitor.companion.ui.PermissionsScreen
import org.cryomonitor.companion.ui.ServerScreen
import org.cryomonitor.companion.ui.SettingsScreen

/**
 * The one host Activity (design D2): Compose + NavHost. Screens not yet
 * converted (Contacts, Enrol, Diagnostics, Logs) are reached through their
 * Activities until their routes exist.
 */
class MainActivity : ComponentActivity() {

    private lateinit var settings: SettingsStore
    private lateinit var watchConfig: WatchConfig

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        settings = SettingsStore(this)
        watchConfig = WatchConfig(this)
        CmLog.init(this)
        requestPermissionsThenStartService()
        setContent { CmTheme { Nav(intent.getStringExtra(EXTRA_ROUTE) ?: ROUTE_HOME) } }
    }

    @Composable
    private fun Nav(start: String) {
        val nav = rememberNavController()
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "?"
        NavHost(nav, startDestination = start) {
            composable(ROUTE_HOME) {
                HomeScreen(
                    settings = settings,
                    onAction = { a ->
                        when (a) {
                            CoverageAction.CANCEL_ALARM -> startActivity(
                                Intent(this@MainActivity, AlarmActivity::class.java))
                            CoverageAction.SET_UP -> nav.navigate(ROUTE_SETTINGS)
                            CoverageAction.ADD_CONTACT -> nav.navigate(ROUTE_CONTACTS)
                            CoverageAction.OPEN_BLUETOOTH -> runCatching {
                                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                            }
                            CoverageAction.OPEN_SERVER -> nav.navigate(ROUTE_SERVER)
                            CoverageAction.OPEN_PERMISSIONS -> nav.navigate(ROUTE_PERMISSIONS)
                            CoverageAction.OPEN_WATCH -> nav.navigate(ROUTE_PEBBLE)
                            CoverageAction.RE_WEAR -> nav.navigate(ROUTE_PEBBLE)
                            CoverageAction.RESUME -> resumeOnWatch()
                        }
                    },
                    onOpenSettings = { nav.navigate(ROUTE_SETTINGS) },
                    onOpenContacts = { nav.navigate(ROUTE_CONTACTS) },
                    onOpenServer = { nav.navigate(ROUTE_SERVER) },
                    onOpenWatch = { nav.navigate(ROUTE_PEBBLE) },
                    onFireDrill = { fireDrill() },
                )
            }
            composable(ROUTE_SETTINGS) {
                SettingsScreen(
                    settings = settings,
                    cfg = watchConfig,
                    version = version,
                    onBack = { nav.popBackStack() },
                    onDetector = { d -> nav.navigate("$ROUTE_DETECTOR/${d.name}") },
                    onWhenItAsks = { nav.navigate(ROUTE_WHEN) },
                    onContacts = { nav.navigate(ROUTE_CONTACTS) },
                    onAdvanced = { nav.navigate(ROUTE_ADVANCED) },
                    onServer = { nav.navigate(ROUTE_SERVER) },
                    onPermissions = { nav.navigate(ROUTE_PERMISSIONS) },
                    onPebble = { nav.navigate(ROUTE_PEBBLE) },
                    onDiagnostics = {
                        startActivity(Intent(this@MainActivity, DebugActivity::class.java))
                    },
                    onSetupAgain = { nav.navigate(ROUTE_PERMISSIONS) }, // onboarding lands here (task 7)
                )
            }
            composable(ROUTE_SERVER) {
                ServerScreen(settings, onBack = { nav.popBackStack() },
                             onEnrol = { nav.navigate(ROUTE_ENROL) })
            }
            composable(ROUTE_ENROL) { EnrollScreen(settings, onBack = { nav.popBackStack() }) }
            composable(ROUTE_CONTACTS) { ContactsScreen(settings, onBack = { nav.popBackStack() }) }
            composable(ROUTE_PEBBLE) { PebbleScreen(settings, onBack = { nav.popBackStack() }) }
            composable(ROUTE_WHEN) { WhenItAsksScreen(watchConfig, onBack = { nav.popBackStack() }) }
            composable("$ROUTE_DETECTOR/{name}") { entry ->
                val d = entry.arguments?.getString("name")?.let { n ->
                    Detector.entries.firstOrNull { it.name == n } } ?: Detector.PULSE
                DetectorScreen(d, watchConfig, onBack = { nav.popBackStack() })
            }
            composable(ROUTE_PERMISSIONS) { PermissionsScreen(onBack = { nav.popBackStack() }) }
            composable(ROUTE_ADVANCED) { AdvancedScreen(settings, onBack = { nav.popBackStack() }) }
        }
    }

    private fun resumeOnWatch() {
        startService(Intent(this, MonitorService::class.java)
            .setAction(MonitorService.ACTION_USER_CANCEL).putExtra("cause", "resumed_on_phone"))
    }

    private fun fireDrill() {
        startService(Intent(this, MonitorService::class.java)
            .setAction(MonitorService.ACTION_TEST_ALARM))
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { startMonitorService() }

    private fun requestPermissionsThenStartService() {
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) wanted += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        wanted += Manifest.permission.ACCESS_FINE_LOCATION
        val missing = wanted.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startMonitorService()
        else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startMonitorService() {
        try {
            startForegroundService(Intent(this, MonitorService::class.java))
        } catch (e: Exception) {
            CmLog.e("MainActivity", "failed to start MonitorService", e)
        }
    }

    companion object {
        const val EXTRA_ROUTE = "route"
        const val ROUTE_HOME = "home"
        const val ROUTE_SETTINGS = "settings"
        const val ROUTE_SERVER = "settings/server"
        const val ROUTE_PEBBLE = "settings/pebble"
        const val ROUTE_PERMISSIONS = "settings/permissions"
        const val ROUTE_ADVANCED = "settings/advanced"
        const val ROUTE_ENROL = "settings/server/enrol"
        const val ROUTE_CONTACTS = "contacts"
        const val ROUTE_WHEN = "settings/when"
        const val ROUTE_DETECTOR = "settings/detector"
    }
}

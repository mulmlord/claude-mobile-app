package dev.mulm.claudemobile

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** The newest app build the laptop has (from Gradle's output metadata). */
data class AppBuild(val versionCode: Long, val versionName: String, val size: Long, val sha256: String) {
    companion object {
        fun from(o: JSONObject?): AppBuild? = o?.let {
            AppBuild(it.optLong("versionCode"), it.optString("versionName"), it.optLong("size"), it.optString("sha256"))
        }
    }
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data class Downloading(val progress: Float) : UpdateState
    data object Installing : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Self-update over the bridge: download the APK the laptop built, check its sha256,
 * then hand it to PackageInstaller. Android shows its own confirmation; the app is
 * replaced and BootReceiver (MY_PACKAGE_REPLACED) brings the connection back.
 */
object Updater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    fun installedCode(ctx: Context): Long {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }

    fun installedName(ctx: Context): String = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    private fun file(ctx: Context) = File(ctx.cacheDir, "update.apk")

    /** Leftover download from an update that already went through. */
    fun cleanup(ctx: Context) {
        file(ctx).delete()
    }

    fun start(ctx: Context, bridge: BridgeClient, build: AppBuild) {
        scope.launch { update(ctx.applicationContext, bridge, build) }
    }

    private suspend fun update(ctx: Context, bridge: BridgeClient, build: AppBuild) {
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Installing) return
        val dest = file(ctx)
        try {
            _state.value = UpdateState.Downloading(0f)
            bridge.downloadApk(dest) { p -> _state.value = UpdateState.Downloading(p) }
            if (build.sha256.isNotBlank() && sha256(dest) != build.sha256) {
                throw IOException("The download was damaged, try again")
            }
            _state.value = UpdateState.Installing
            install(ctx, dest)
        } catch (e: Exception) {
            dest.delete()
            _state.value = UpdateState.Failed(e.message ?: "Update failed")
        }
    }

    private suspend fun sha256(f: File): String = withContext(Dispatchers.IO) {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun install(ctx: Context, apk: File) = withContext(Dispatchers.IO) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        params.setSize(apk.length())
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("app.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallReceiver::class.java), flags)
            session.commit(pending.intentSender)
        }
    }

    internal fun finished(message: String?) {
        _state.value = if (message == null) UpdateState.Idle else UpdateState.Failed(message)
    }
}

/** PackageInstaller's answers: show its confirmation screen, or report how it ended. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                else Updater.finished("The installer didn't open")
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.finished(null)
            PackageInstaller.STATUS_FAILURE_ABORTED -> Updater.finished("Update cancelled")
            else -> Updater.finished(
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.let { "Install failed: $it" }
                    ?: "Install failed ($status)"
            )
        }
    }
}

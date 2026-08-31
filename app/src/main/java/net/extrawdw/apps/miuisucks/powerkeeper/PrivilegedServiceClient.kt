package net.extrawdw.apps.miuisucks.powerkeeper

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

object PrivilegedServiceClient {
    private val operationMutex = Mutex()
    private var service: IPrivilegedService? = null
    private var connectionWaiter: CompletableDeferred<IPrivilegedService>? = null

    private val serviceArgs: Shizuku.UserServiceArgs
        get() = Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, PowerKeeperUserService::class.java.name),
        )
            .daemon(true)
            .processNameSuffix("guard")
            .debuggable(BuildConfig.DEBUG)
            .version(USER_SERVICE_VERSION)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            AppLog.i("ShizukuClient", "connected component=${name.flattenToShortString()} alive=${binder.isBinderAlive}")
            val connectedService = IPrivilegedService.Stub.asInterface(binder)
            service = connectedService
            connectionWaiter?.complete(connectedService)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            AppLog.w("ShizukuClient", "disconnected component=${name.flattenToShortString()}")
            service = null
            connectionWaiter?.completeExceptionally(IllegalStateException("Shizuku user service disconnected"))
        }
    }

    suspend fun enforce(
        aurogonPackages: Collection<String>,
        policies: Collection<AppPolicy>,
        targetUserIds: List<Int>,
        milletPollingIntervalMillis: Long,
        trigger: String,
        onProgress: (suspend (completedApps: Int, totalApps: Int) -> Unit)? = null,
    ): String = coroutineScope {
        val progressUpdates = onProgress?.let { Channel<Pair<Int, Int>>(Channel.UNLIMITED) }
        val progressCollector = progressUpdates?.let { updates ->
            launch {
                for ((completed, total) in updates) onProgress?.invoke(completed, total)
            }
        }
        val progressCallback = progressUpdates?.let { updates ->
            object : IEnforcementProgressCallback.Stub() {
                override fun onProgress(completedApps: Int, totalApps: Int) {
                    updates.trySend(completedApps to totalApps)
                }
            }
        }
        try {
            withService("enforce", trigger) { connectedService ->
                connectedService.configureFcmPolling(milletPollingIntervalMillis, trigger)
                // Full passes never apply per-app AppOps or battery state for a disabled app.
                // Targeted disable cleanup uses applyAppPolicy() and remains intentionally exempt.
                val orderedPolicies = policies
                    .filter(AppPolicy::appEnabled)
                    .sortedBy(AppPolicy::packageName)
                connectedService.enforceBatched(
                    aurogonPackages.distinct().sorted().toTypedArray(),
                    orderedPolicies.map(AppPolicy::packageName).toTypedArray(),
                    orderedPolicies.map(AppPolicy::autostartManaged).toBooleanArray(),
                    orderedPolicies.map(AppPolicy::autostartEnabled).toBooleanArray(),
                    orderedPolicies.map(AppPolicy::dozeManaged).toBooleanArray(),
                    orderedPolicies.map { it.dozePolicy.code }.toIntArray(),
                    targetUserIds.toIntArray(),
                    trigger,
                    progressCallback,
                )
            }
        } finally {
            progressUpdates?.close()
            progressCollector?.join()
        }
    }

    suspend fun startFcmProtection(
        aurogonPackages: Collection<String>,
        milletPollingIntervalMillis: Long,
        trigger: String,
    ): String =
        withService("startFcmProtection", trigger) { connectedService ->
            connectedService.configureFcmPolling(milletPollingIntervalMillis, trigger)
            connectedService.startFcmProtection(
                aurogonPackages.distinct().sorted().toTypedArray(),
                trigger,
            )
        }

    suspend fun unstop(
        packageNames: Collection<String>,
        targetUserIds: List<Int>,
        trigger: String,
    ): String =
        withService("unstop", trigger) { connectedService ->
            connectedService.unstop(
                packageNames.distinct().sorted().toTypedArray(),
                targetUserIds.distinct().sorted().toIntArray(),
                trigger,
            )
        }

    suspend fun reconcileAurogon(
        aurogonPackages: Collection<String>,
        milletPollingIntervalMillis: Long,
        trigger: String,
    ): String =
        withService("reconcileAurogon", trigger) { connectedService ->
            connectedService.configureFcmPolling(milletPollingIntervalMillis, trigger)
            connectedService.reconcileAurogon(
                aurogonPackages.distinct().sorted().toTypedArray(),
                trigger,
            )
        }

    suspend fun configureFcmPolling(
        intervalMillis: Long,
        trigger: String,
    ): String =
        withService("configureFcmPolling", trigger) { connectedService ->
            connectedService.configureFcmPolling(intervalMillis, trigger)
        }

    suspend fun applyAppPolicy(
        policy: AppPolicy,
        targetUserIds: List<Int>,
        trigger: String,
    ): String =
        withService("applyAppPolicy", trigger) { connectedService ->
            connectedService.applyAppPolicy(
                policy.packageName,
                policy.autostartManaged,
                policy.autostartEnabled,
                policy.dozeManaged,
                policy.dozePolicy.code,
                targetUserIds.toIntArray(),
                trigger,
            )
        }

    suspend fun getMilletNoRestrictValue(trigger: String): String =
        withService("getMilletNoRestrictValue", trigger) { connectedService ->
            connectedService.getMilletNoRestrictValue(trigger)
        }

    suspend fun listAndroidUsers(trigger: String): String =
        withService("listAndroidUsers", trigger) { connectedService ->
            connectedService.listAndroidUsers(trigger)
        }

    suspend fun flushServiceLogs(trigger: String) {
        withService("flushServiceLogs", trigger) { Unit }
    }

    private suspend fun <T> withService(
        operationName: String,
        trigger: String,
        operation: suspend (IPrivilegedService) -> T,
    ): T = operationMutex.withLock {
        AppLog.i("ShizukuClient", "operation=$operationName trigger=$trigger connecting")
        val connectedService = connect()
        try {
            withContext(Dispatchers.IO) {
                attachServiceLog(connectedService, operationName)
                operation(connectedService)
            }.also {
                AppLog.i("ShizukuClient", "operation=$operationName trigger=$trigger completed")
            }
        } catch (throwable: Throwable) {
            AppLog.e("ShizukuClient", "operation=$operationName trigger=$trigger failed", throwable)
            throw throwable
        } finally {
            disconnect()
        }
    }

    private fun attachServiceLog(connectedService: IPrivilegedService, operationName: String) {
        val path = AppLog.shizukuLogPath()
        if (path == null) {
            AppLog.w("ShizukuClient", "external service log path unavailable operation=$operationName")
            return
        }
        runCatching { connectedService.attachLogPath(path) }
            .onSuccess {
                AppLog.i("ShizukuClient", "service log attached operation=$operationName file=${path.substringAfterLast('/')}")
            }
            .onFailure { error ->
                // Diagnostics must never prevent the requested privileged operation.
                AppLog.w("ShizukuClient", "service log attach failed operation=$operationName: ${error.message}")
            }
    }

    private suspend fun connect(): IPrivilegedService {
        service?.let { existing ->
            if (existing.asBinder().pingBinder()) {
                AppLog.i("ShizukuClient", "reusing connected UserService")
                return existing
            }
        }

        val waiter = CompletableDeferred<IPrivilegedService>()
        connectionWaiter = waiter
        withContext(Dispatchers.Main.immediate) {
            AppLog.i("ShizukuClient", "bindUserService requested")
            Shizuku.bindUserService(serviceArgs, connection)
        }
        return try {
            withTimeout(CONNECTION_TIMEOUT_MILLIS) { waiter.await() }
        } catch (throwable: Throwable) {
            AppLog.e("ShizukuClient", "connection failed", throwable)
            disconnect()
            throw throwable
        } finally {
            connectionWaiter = null
        }
    }

    private suspend fun disconnect() {
        service = null
        runCatching {
            withContext(Dispatchers.Main.immediate) {
                Shizuku.unbindUserService(serviceArgs, connection, false)
            }
        }.onSuccess {
            AppLog.i("ShizukuClient", "client unbound; daemon retained")
        }.onFailure { error ->
            AppLog.w("ShizukuClient", "unbind failed: ${error.message}")
        }
    }

    private const val CONNECTION_TIMEOUT_MILLIS = 15_000L
    // Increment whenever the UserService implementation or AIDL changes so Shizuku replaces the
    // daemon process instead of retaining code loaded from an older APK.
    private const val USER_SERVICE_VERSION = 15
}

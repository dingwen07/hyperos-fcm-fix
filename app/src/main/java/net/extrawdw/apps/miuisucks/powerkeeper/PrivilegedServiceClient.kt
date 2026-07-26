package net.extrawdw.apps.miuisucks.powerkeeper

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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
            val connectedService = IPrivilegedService.Stub.asInterface(binder)
            service = connectedService
            connectionWaiter?.complete(connectedService)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            connectionWaiter?.completeExceptionally(IllegalStateException("Shizuku user service disconnected"))
        }
    }

    suspend fun enforce(policy: WechatPolicy, targetUserIds: List<Int>): String = withService { connectedService ->
        connectedService.enforce(policy.code, targetUserIds.toIntArray())
    }

    suspend fun startFcmProtection(): String = withService { connectedService ->
        connectedService.startFcmProtection()
    }

    suspend fun getMilletNoRestrictValue(): String = withService { connectedService ->
        connectedService.getMilletNoRestrictValue()
    }

    suspend fun listAndroidUsers(): String = withService { connectedService ->
        connectedService.listAndroidUsers()
    }

    private suspend fun withService(operation: (IPrivilegedService) -> String): String = operationMutex.withLock {
        val connectedService = connect()
        try {
            withContext(Dispatchers.IO) { operation(connectedService) }
        } finally {
            disconnect()
        }
    }

    private suspend fun connect(): IPrivilegedService {
        service?.let { existing ->
            if (existing.asBinder().pingBinder()) return existing
        }

        val waiter = CompletableDeferred<IPrivilegedService>()
        connectionWaiter = waiter
        withContext(Dispatchers.Main.immediate) {
            Shizuku.bindUserService(serviceArgs, connection)
        }
        return try {
            withTimeout(CONNECTION_TIMEOUT_MILLIS) { waiter.await() }
        } catch (throwable: Throwable) {
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
        }
    }

    private const val CONNECTION_TIMEOUT_MILLIS = 15_000L
    // Increment whenever the UserService AIDL surface changes so Shizuku replaces stale processes.
    private const val USER_SERVICE_VERSION = 2
}

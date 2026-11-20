package id.larus.mqttgroup

import android.content.Context
import android.util.Log
import id.larus.mqttgroup.call.AndroidCallNotifier
import id.larus.mqttgroup.call.AndroidRinger
import id.larus.mqttgroup.call.CallEngine
import id.larus.mqttgroup.crypto.KeyVault
import id.larus.mqttgroup.data.backup.BackupRepository
import id.larus.mqttgroup.data.blob.BlobStore
import id.larus.mqttgroup.data.db.AppDatabase
import id.larus.mqttgroup.data.repo.BrokerRepository
import id.larus.mqttgroup.data.repo.GroupRepository
import id.larus.mqttgroup.data.repo.IdentityRepository
import id.larus.mqttgroup.data.repo.OutboxRepository
import id.larus.mqttgroup.map.MapPreferences
import id.larus.mqttgroup.mqtt.ConnectionPool
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * The app's singletons, built on first use rather than at startup: opening the database means
 * touching the Keystore and loading SQLCipher's native library, which is real work to do on the
 * main thread of a cold start that may only be showing a welcome screen.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val vault: KeyVault by lazy { KeyVault(appContext) }

    private val databaseLazy = lazy { AppDatabase.open(appContext, vault) }
    val database: AppDatabase by databaseLazy

    val blobs: BlobStore by lazy { BlobStore(blobDirectory(appContext), vault.blobKey()) }

    val identity: IdentityRepository by lazy { IdentityRepository(database, blobs) }

    val brokers: BrokerRepository by lazy { BrokerRepository(database) }

    val outbox: OutboxRepository by lazy { OutboxRepository(database) }

    val backups: BackupRepository by lazy { BackupRepository(database, vault) }

    val groups: GroupRepository by lazy { GroupRepository(database, vault, blobs, outbox) }

    /** Which map layer the person picked. Cheap enough to build eagerly-on-first-use like the
     * rest, and touched by both the Settings screen and every map. */
    val mapPreferences: MapPreferences by lazy { MapPreferences(appContext) }

    /**
     * One connection per broker, for the whole process. The service drives them; screens only
     * read the aggregate state and enqueue through the outbox, so nothing breaks when the
     * service is restarted.
     */
    val connections: ConnectionPool by lazy { ConnectionPool() }

    /** How a call is shown and heard. Held here rather than inside [calls] so that the
     * settings screen can ask [AndroidCallNotifier.canUseFullScreen] without building an
     * engine. */
    val callNotifier: AndroidCallNotifier by lazy { AndroidCallNotifier(appContext) }

    /**
     * The one call this phone can be on.
     *
     * Process-scoped, not screen-scoped: a call outlives the activity that started it, survives
     * the chat screen being closed, and has to be reachable from a notification action while no
     * UI exists at all. Its scope is the application's, for the same reason.
     */
    val calls: CallEngine by lazy {
        CallEngine(
            appContext = appContext,
            identity = identity,
            groups = groups,
            connections = connections,
            // The handler is the point, not decoration. This scope is the application's, so
            // anything that escapes a launch here reaches the default uncaught handler and
            // takes the process down — which is exactly what a CallStyle notification the
            // system refused to post did on the first real dial. A call that cannot ring is a
            // bad call; a call that kills the app is a crash report.
            scope = CoroutineScope(
                SupervisorJob() + Dispatchers.Default +
                    CoroutineExceptionHandler { _, t -> Log.e("CallEngine", "call failed", t) },
            ),
            ringer = AndroidRinger(appContext),
            notifier = callNotifier,
        )
    }

    /** Which group's chat is on-screen right now, so an incoming message for it can skip the
     * notification — the person is already looking at it. Set by the UI, read by [GroupSync]. */
    val activeGroupId = MutableStateFlow<String?>(null)

    /**
     * Whether a screen of this app is actually in front of the user, set by MainActivity's
     * onStart/onStop and read by [id.larus.mqttgroup.mqtt.MqttService].
     *
     * It exists so that "keep trying" and "stop trying" can be two different answers to the same
     * broken connection. In the background, the automatic reconnect gives up after
     * MqttConnection.MAX_RECONNECT_ATTEMPTS, because a broker that is gone would otherwise mean a
     * radio wake every minute forever. In the foreground someone is *looking at* the word
     * "Offline" and waiting for it to change, so giving up there just moves the retry onto them
     * and their thumb.
     */
    val appVisible = MutableStateFlow(false)

    /**
     * "Delete my data", and it means it. The order matters: close and delete the database and
     * the blobs first, then destroy the keys. Even if the file deletions leave recoverable
     * fragments on flash — and on a phone they usually do — those fragments are ciphertext
     * whose key no longer exists anywhere.
     *
     * The process is killed afterwards by the caller, because the lazies above still hold a
     * handle to a database that is now gone.
     */
    fun wipeEverything() {
        if (databaseLazy.isInitialized()) database.close()
        AppDatabase.deleteFiles(appContext)
        blobDirectory(appContext).deleteRecursively()
        vault.destroy()
    }

    private fun blobDirectory(context: Context) = File(context.filesDir, "blobs")
}

package id.larus.mqttgroup.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import id.larus.mqttgroup.crypto.KeyVault
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File

@Database(
    entities = [
        IdentityEntity::class,
        BrokerEntity::class,
        GroupEntity::class,
        GroupKeyEntity::class,
        MemberEntity::class,
        MessageEntity::class,
        MessageReceiptEntity::class,
        AttachmentEntity::class,
        LocationEntity::class,
        OutboxEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun identity(): IdentityDao
    abstract fun brokers(): BrokerDao
    abstract fun groups(): GroupDao
    abstract fun members(): MemberDao
    abstract fun messages(): MessageDao
    abstract fun receipts(): MessageReceiptDao
    abstract fun attachments(): AttachmentDao
    abstract fun locations(): LocationDao
    abstract fun outbox(): OutboxDao

    companion object {
        const val FILE_NAME = "family-group.db"

        /**
         * Opens the database with the whole file encrypted by SQLCipher. Room is otherwise
         * unaware — the DAOs above are plain Room — because SQLCipher plugs in at the
         * support-SQLite layer.
         */
        fun open(context: Context, vault: KeyVault): AppDatabase {
            System.loadLibrary("sqlcipher")
            val passphrase = vault.databaseKey()
            return Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, FILE_NAME)
                // SupportOpenHelperFactory zeroes the passphrase array it is handed, so this
                // copy exists to keep the vault's cached key intact.
                .openHelperFactory(SupportOpenHelperFactory(passphrase.copyOf()))
                // Pre-release schema churn (read receipts in phase 7, the outbox's expiresAt
                // in phase 8) isn't worth hand-written
                // migrations for yet — every group and message is re-derivable by rejoining, and
                // nobody but this app's own developer has a database old enough to hit this.
                .fallbackToDestructiveMigration()
                .build()
        }

        /** Removes the database and its journal files. Paired with [KeyVault.destroy]. */
        fun deleteFiles(context: Context) {
            val base = context.getDatabasePath(FILE_NAME)
            listOf(base, File("${base.path}-wal"), File("${base.path}-shm"), File("${base.path}-journal"))
                .forEach { it.delete() }
        }
    }
}

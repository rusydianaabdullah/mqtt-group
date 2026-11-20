package id.larus.mqttgroup.data.repo

import id.larus.mqttgroup.data.db.AppDatabase
import id.larus.mqttgroup.data.db.BrokerEntity
import id.larus.mqttgroup.mqtt.MqttConnection
import id.larus.mqttgroup.mqtt.Topics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class BrokerRepository(private val db: AppDatabase) {

    fun observeAll(): Flow<List<BrokerEntity>> = db.brokers().observeAll()

    /** Phase 2 uses a single broker; phase 7 lets a group pick one. */
    fun observeActive(): Flow<BrokerEntity?> = db.brokers().observeAll().map { it.firstOrNull() }

    suspend fun get(id: String): BrokerEntity? = db.brokers().get(id)

    suspend fun save(broker: BrokerEntity) = db.brokers().put(broker)

    suspend fun delete(id: String) = db.brokers().delete(id)

    /**
     * Connects with the given settings and disconnects again, so the user finds out about a
     * wrong password or an unreachable host here rather than by wondering why messages never
     * arrive. Returns the failure text, or null for success.
     */
    suspend fun testConnection(broker: BrokerEntity, clientId: String): String? {
        val connection = MqttConnection()
        return try {
            connection.connect(broker, clientId)
            if (connection.isConnected) null else "Could not connect. Check the host, port and credentials."
        } catch (e: Exception) {
            e.message ?: "Could not connect."
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /**
         * A public broker so a first run works before anyone stands up their own. Port 8886 is
         * Mosquitto's TLS listener with a certificate from a public CA, so it validates against
         * the system trust store with nothing to install.
         */
        fun suggestion(): BrokerEntity = BrokerEntity(
            id = UUID.randomUUID().toString(),
            label = "Public test broker",
            host = "test.mosquitto.org",
            port = 8886,
            tls = true,
            allowInsecureTls = false,
            username = null,
            password = null,
            topicPrefix = Topics.DEFAULT_PREFIX,
            createdAt = System.currentTimeMillis(),
        )
    }
}

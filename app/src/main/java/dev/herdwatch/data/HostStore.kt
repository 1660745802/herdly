package dev.herdwatch.data

import android.content.Context
import android.content.SharedPreferences
import dev.herdwatch.R
import dev.herdwatch.i18n.Str
import dev.herdwatch.model.Agent
import dev.herdwatch.model.HostConfig
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The credential store cannot read or write the saved hosts (the Android Keystore entry is gone or
 * unreadable). Distinct from an ordinary failed commit: retrying cannot fix it, and the stored
 * payload must never be replaced by one encrypted with a different key.
 */
class HostStoreUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Encrypted, synchronous preferences store.  `commit()` is intentional here: a host
 * fingerprint or credential change must not be reported as saved before it is durable.
 */
class HostStore internal constructor(
    private val prefs: SharedPreferences,
    private val encrypt: (String) -> String,
    private val decrypt: (String) -> String,
) {
    constructor(context: Context) : this(
        context.getSharedPreferences("herdwatch", Context.MODE_PRIVATE),
        SecretBox::encrypt,
        SecretBox::decrypt,
    )
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(HostConfig.serializer())
    private val agentSerializer = ListSerializer(Agent.serializer())

    fun load(): List<HostConfig> {
        val primary = prefs.getString(KEY_HOSTS, null)
        val backup = prefs.getString(KEY_BACKUP, null)
        primary?.let { packed -> decode(packed)?.let { return it } }
        backup?.let { packed -> decode(packed)?.let { return it } }
        // A payload exists but neither copy decodes: the Keystore key is gone or unreadable. Do not
        // report "no hosts" — that looks like a fresh install, and the next save would overwrite
        // credentials that are still sitting there and may be recoverable.
        if (primary != null || backup != null) {
            throw HostStoreUnavailableException(Str.get(R.string.store_unavailable))
        }
        return emptyList()
    }

    private fun decode(packed: String): List<HostConfig>? = runCatching {
        json.decodeFromString(serializer, decrypt(packed))
    }.getOrNull()

    fun loadAgents(hostId: String): List<Agent> {
        val packed = prefs.getString(agentsKey(hostId), null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(agentSerializer, decrypt(packed))
        }.getOrDefault(emptyList())
    }

    fun saveAgents(hostId: String, agents: List<Agent>) {
        val plain = json.encodeToString(agentSerializer, agents)
        check(prefs.edit().putString(agentsKey(hostId), encrypt(plain)).commit()) {
            Str.get(R.string.store_cache_save_failed)
        }
    }

    /** Remove a cache durably; used when a host is deleted. */
    fun deleteAgents(hostId: String) {
        check(prefs.edit().remove(agentsKey(hostId)).commit()) { Str.get(R.string.store_cache_delete_failed) }
    }

    /**
     * Save hosts and, optionally, delete caches in the same preferences transaction.
     * Both recovery copies contain the current trust/credential state. Rolling back to
     * an older generation could resurrect deleted credentials or silently remove a pin.
     */
    fun save(hosts: List<HostConfig>, removedHostIds: Set<String> = emptySet()) {
        val primary = prefs.getString(KEY_HOSTS, null)
        val backup = prefs.getString(KEY_BACKUP, null)
        // Refuse before touching the payload: an unreadable generation means we cannot tell which
        // credentials a new ciphertext would overwrite (encrypting with a regenerated key succeeds).
        if ((primary != null || backup != null) &&
            primary?.let(::decode) == null && backup?.let(::decode) == null
        ) {
            throw HostStoreUnavailableException(Str.get(R.string.store_unavailable))
        }
        val plain = json.encodeToString(serializer, hosts)
        val ciphertext = try {
            encrypt(plain)
        } catch (t: Exception) {
            throw HostStoreUnavailableException(Str.get(R.string.store_unavailable), t)
        }
        val edit = prefs.edit().putString(KEY_HOSTS, ciphertext).putString(KEY_BACKUP, ciphertext)
        removedHostIds.forEach { edit.remove(agentsKey(it)) }
        check(edit.commit()) { Str.get(R.string.store_config_save_failed) }
    }

    private fun agentsKey(hostId: String) = "agents.v1.$hostId"

    companion object {
        private const val KEY_HOSTS = "hosts.v1"
        private const val KEY_BACKUP = "hosts.v1.bak"
    }
}

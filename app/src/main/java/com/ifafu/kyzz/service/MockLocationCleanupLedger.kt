package com.ifafu.kyzz.service

import android.content.Context
import android.content.SharedPreferences
import android.location.LocationManager

/** Durable ownership record. Provider registration is forbidden until this record is committed. */
internal interface MockLocationCleanupLedger {
    fun pendingProviders(): Set<String>
    fun markPending(providers: Set<String>): Boolean
    fun markPending(provider: String): Boolean = markPending(setOf(provider))
    fun markRemoved(provider: String): Boolean
}

internal class PreferencesMockLocationCleanupLedger(private val preferences: SharedPreferences) : MockLocationCleanupLedger {
    constructor(context: Context) : this(context.getSharedPreferences("ifafu_user", Context.MODE_PRIVATE))

    override fun pendingProviders(): Set<String> = synchronized(gate) { readPending() }

    override fun markPending(providers: Set<String>): Boolean = synchronized(gate) {
        require(providers.all { it in OWNED_PROVIDERS }) { "Unsupported mock provider" }
        val pending = readPending() + providers
        // commit, rather than apply: process death immediately after add must leave an ownership record.
        val committed = write(pending)
        if (!committed) write(pending) // Preserve the conservative memory image on failed commits.
        committed
    }

    override fun markRemoved(provider: String): Boolean = synchronized(gate) {
        val previous = readPending()
        val remaining = previous - provider
        if (write(remaining)) true else {
            // SharedPreferences changes its memory image even when the disk commit fails. Restore
            // the conservative record so a new Service in this process also retries the cleanup.
            write(previous)
            false
        }
    }

    private fun readPending(): Set<String> {
        val all = preferences.all
        val stored = all[KEY_PENDING]
        val pending = when (stored) {
            null -> emptySet()
            is Set<*> -> if (stored.any { it !is String || it !in OWNED_PROVIDERS }) OWNED_PROVIDERS
                else stored.filterIsInstance<String>().toSet()
            // A damaged existing ownership record must not silently turn into "already cleaned".
            else -> OWNED_PROVIDERS
        }
        return if (all[KEY_SCHEMA] != SCHEMA && all[KEY_LEGACY_RUNNING] == true) pending + OWNED_PROVIDERS else pending
    }

    private fun write(pending: Set<String>): Boolean = try {
        preferences.edit().putStringSet(KEY_PENDING, pending.toSet()).putInt(KEY_SCHEMA, SCHEMA)
            // Migrate the old running flag atomically; it cannot erase the new pending record.
            .putBoolean(KEY_LEGACY_RUNNING, false).commit()
    } catch (_: Exception) {
        false
    }

    companion object {
        internal const val KEY_PENDING = "mock_location_cleanup_providers"
        internal const val KEY_SCHEMA = "mock_location_cleanup_schema"
        internal const val KEY_LEGACY_RUNNING = "mock_location_running"
        private const val SCHEMA = 1
        private val gate = Any()
        private val OWNED_PROVIDERS = setOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    }
}

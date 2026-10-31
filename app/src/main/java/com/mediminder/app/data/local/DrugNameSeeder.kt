package com.mediminder.app.data.local

import android.content.Context
import com.mediminder.app.di.IoDispatcher
import com.mediminder.app.domain.model.DrugNameType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copies the packaged drug-name dictionary asset into Room the first time it
 * is needed. Idempotent and safe to call from any thread; subsequent calls
 * short-circuit on an in-process flag.
 *
 * The asset is JSON Lines, one entry per line:
 * `{"n":"Metformin","t":"GENERIC","g":null,"s":["500 mg","850 mg"]}`
 *
 * Rebuilt from the openFDA NDC Directory (public domain) — see
 * `tools/rebuild_drug_dictionary.py`.
 */
@Singleton
class DrugNameSeeder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: DrugNameDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    @Volatile
    private var seeded = false

    suspend fun ensureSeeded() {
        if (seeded) return
        withContext(ioDispatcher) {
            try {
                if (dao.count() == 0) {
                    val entries = readAsset()
                    if (entries.isNotEmpty()) {
                        dao.insertAll(entries)
                    }
                }
                seeded = true
            } catch (e: Exception) {
                // A missing or broken dictionary must never break scanning;
                // extraction simply works without name corrections.
                seeded = true
            }
        }
    }

    private fun readAsset(): List<DrugNameEntity> {
        val json = Json { ignoreUnknownKeys = true }
        return context.assets.open(ASSET_NAME).bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                val record = runCatching { json.decodeFromString<DrugNameAssetRecord>(line) }.getOrNull()
                record?.toEntity()
            }.toList()
        }
    }

    @Serializable
    private data class DrugNameAssetRecord(
        val n: String,
        val t: String,
        val g: String? = null,
        val s: List<String> = emptyList(),
    )

    private fun DrugNameAssetRecord.toEntity(): DrugNameEntity? {
        val type = when (t.trim().uppercase()) {
            "GENERIC" -> DrugNameType.GENERIC
            "BRAND" -> DrugNameType.BRAND
            else -> null
        } ?: return null
        val name = n.trim()
        if (name.isEmpty()) return null
        return DrugNameEntity(
            name = name,
            type = type,
            genericName = g?.trim()?.takeIf { it.isNotEmpty() },
            commonStrengths = s.takeIf { it.isNotEmpty() }?.joinToString(", "),
        )
    }

    private companion object {
        const val ASSET_NAME = "drug_names.jsonl"
    }
}

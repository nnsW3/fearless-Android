package jp.co.soramitsu.runtime

import android.content.Context
import jp.co.soramitsu.common.data.storage.Preferences
import jp.co.soramitsu.common.utils.readAssetFile
import jp.co.soramitsu.core_db.dao.RuntimeDao
import jp.co.soramitsu.core_db.model.RuntimeCacheEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val RUNTIME_CACHE_ENTRIES = listOf(
    predefinedEntry("kusama", 2030),
    predefinedEntry("westend", 9000),
    predefinedEntry("polkadot", 30),
    predefinedEntry("rococo", 230)
)

private const val PREDEFINED_METADATA_MASK = "metadata/%s"
private const val PREDEFINED_TYPES_MASK = "types/%s.json"

private fun predefinedEntry(networkName: String, runtimeVersion: Int) = RuntimeCacheEntry(
    networkName = networkName,
    latestKnownVersion = runtimeVersion,
    latestAppliedVersion = runtimeVersion,
    typesVersion = runtimeVersion
)

private const val PREPOPULATED_FLAG = "PREPOPULATED_FLAG"
private const val HOTFIX_23_09_21 = "HOTFIX_23_09_21"
private const val HOTFIX_28_10_21 = "HOTFIX_28_10_21"

class RuntimePrepopulator(
    private val context: Context,
    private val runtimeDao: RuntimeDao,
    private val preferences: Preferences,
    private val runtimeCache: RuntimeCache
) {

    suspend fun maybePrepopulateCache(): Unit = withContext(Dispatchers.IO) {
        if (preferences.contains(PREPOPULATED_FLAG)
            and preferences.contains(HOTFIX_23_09_21)
            and preferences.contains(HOTFIX_28_10_21)) {
            return@withContext
        }

        forcePrepopulateCache()

        preferences.putBoolean(PREPOPULATED_FLAG, true)
        preferences.putBoolean(HOTFIX_23_09_21, true)
        preferences.putBoolean(HOTFIX_28_10_21, true)
    }

    suspend fun forcePrepopulateCache() {
        saveTypes("default")

        RUNTIME_CACHE_ENTRIES.forEach {
            val networkType = it.networkName

            saveMetadata(networkType)

            saveTypes(networkType)

            runtimeDao.insertRuntimeCacheEntry(it)
        }
    }

    private suspend fun saveMetadata(networkName: String) {
        val predefinedMetadataFileName = PREDEFINED_METADATA_MASK.format(networkName)
        val predefinedMetadata = context.readAssetFile(predefinedMetadataFileName)
        runtimeCache.saveRuntimeMetadata(networkName, predefinedMetadata)
    }

    private suspend fun saveTypes(networkName: String) {
        val predefinedTypesFileName = PREDEFINED_TYPES_MASK.format(networkName)
        val predefinedTypes = context.readAssetFile(predefinedTypesFileName)
        runtimeCache.saveTypeDefinitions(networkName, predefinedTypes)
    }
}

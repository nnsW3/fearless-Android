package jp.co.soramitsu.runtime.storage.source

import jp.co.soramitsu.common.data.network.rpc.BulkRetriever
import jp.co.soramitsu.common.data.network.rpc.queryKey
import jp.co.soramitsu.common.data.network.rpc.retrieveAllValues
import jp.co.soramitsu.common.data.network.runtime.binding.BlockHash
import jp.co.soramitsu.core.runtime.models.requests.GetChildStateRequest
import jp.co.soramitsu.runtime.multiNetwork.ChainRegistry
import jp.co.soramitsu.runtime.network.subscriptionFlowCatching
import jp.co.soramitsu.shared_utils.wsrpc.executeAsync
import jp.co.soramitsu.shared_utils.wsrpc.request.runtime.storage.SubscribeStorageRequest
import jp.co.soramitsu.shared_utils.wsrpc.request.runtime.storage.storageChange
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RemoteStorageSource(
    chainRegistry: ChainRegistry,
    private val bulkRetriever: BulkRetriever
) : BaseStorageSource(chainRegistry) {

    override suspend fun query(key: String, chainId: String, at: BlockHash?): String? {
        return kotlin.runCatching { bulkRetriever.queryKey(getSocketService(chainId), key, at) }
            .getOrNull()
    }

    override suspend fun queryKeys(
        keys: List<String>,
        chainId: String,
        at: BlockHash?
    ): Map<String, String?> {
        return kotlin.runCatching { bulkRetriever.queryKeys(getSocketService(chainId), keys, at) }
            .getOrDefault(
                emptyMap()
            )
    }

    override suspend fun observe(key: String, chainId: String): Flow<String?> {
        return getSocketService(chainId).subscriptionFlowCatching(SubscribeStorageRequest(key))
            .map { result ->
                result.getOrNull()?.storageChange()?.getSingleChange()
            }
    }

    override suspend fun queryByPrefix(prefix: String, chainId: String): Map<String, String?> {
        return kotlin.runCatching {
            bulkRetriever.retrieveAllValues(
                getSocketService(chainId),
                prefix
            )
        }.getOrDefault(
            emptyMap()
        )
    }

    override suspend fun queryChildState(
        storageKey: String,
        childKey: String,
        chainId: String
    ): String? {
        val response =
            kotlin.runCatching { getSocketService(chainId).executeAsync(GetChildStateRequest(storageKey, childKey)) }.getOrNull()

        return response?.result as? String?
    }

    private suspend fun getSocketService(chainId: String) =
        chainRegistry.awaitConnection(chainId).socketService
}

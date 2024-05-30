package jp.co.soramitsu.runtime.multiNetwork.runtime

import jp.co.soramitsu.core.runtime.ChainConnection
import jp.co.soramitsu.coredb.dao.ChainDao
import jp.co.soramitsu.runtime.multiNetwork.chain.model.Chain
import kotlinx.coroutines.cancel
import java.util.concurrent.ConcurrentHashMap
import jp.co.soramitsu.common.domain.NetworkStateService

class RuntimeSubscriptionPool(
    private val chainDao: ChainDao,
    private val runtimeSyncService: RuntimeSyncService,
    private val networkStateService: NetworkStateService,
) {

    private val pool = ConcurrentHashMap<String, RuntimeVersionSubscription>()

    fun getRuntimeSubscription(chainId: String) = pool.getValue(chainId)

    fun setupRuntimeSubscription(chain: Chain, connection: ChainConnection): RuntimeVersionSubscription {
        return pool.getOrPut(chain.id) {
            RuntimeVersionSubscription(chain.id, connection, chainDao, runtimeSyncService, networkStateService)
        }
    }

    fun removeSubscription(chainId: String) {
        pool.remove(chainId)?.apply { cancel() }
    }
}

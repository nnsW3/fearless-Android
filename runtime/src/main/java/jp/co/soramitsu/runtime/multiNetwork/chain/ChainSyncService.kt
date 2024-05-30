package jp.co.soramitsu.runtime.multiNetwork.chain

import jp.co.soramitsu.coredb.dao.AssetDao
import jp.co.soramitsu.coredb.dao.ChainDao
import jp.co.soramitsu.coredb.dao.MetaAccountDao
import jp.co.soramitsu.coredb.model.AssetLocal
import jp.co.soramitsu.coredb.model.chain.JoinedChainInfo
import jp.co.soramitsu.runtime.multiNetwork.chain.model.Chain
import jp.co.soramitsu.runtime.multiNetwork.chain.remote.ChainFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ChainSyncService(
    private val dao: ChainDao,
    private val chainFetcher: ChainFetcher,
    private val metaAccountDao: MetaAccountDao,
    private val assetsDao: AssetDao
) {

    suspend fun syncUp() = withContext(Dispatchers.Default) {
        runCatching {
            val localChainsJoinedInfo = dao.getJoinChainInfo()

            val remoteChains = chainFetcher.getChains()
                .filter {
                    !it.disabled && (it.assets?.isNotEmpty() == true)
                }
                .map {
                    it.toChain()
                }

            val localChains = localChainsJoinedInfo.map(::mapChainLocalToChain)

            val remoteMapping = remoteChains.associateBy(Chain::id)
            val localMapping = localChains.associateBy(Chain::id)

            val newOrUpdated = remoteChains.mapNotNull { remoteChain ->
                val localVersion = localMapping[remoteChain.id]

                when {
                    localVersion == null -> remoteChain // new
                    localVersion != remoteChain -> remoteChain // updated
                    else -> null // same
                }
            }.map(::mapChainToChainLocal)

            val removed = localChainsJoinedInfo.filter { it.chain.id !in remoteMapping }
                .map(JoinedChainInfo::chain)
            dao.update(removed, newOrUpdated)
            val metaAccounts = metaAccountDao.getMetaAccounts()

            if (metaAccounts.isEmpty()) return@runCatching Unit
            val newAssets =
                newOrUpdated.filter { it.chain.id !in localMapping.keys }.map { it.assets }
                    .flatten()

            val newLocalAssets = metaAccounts.map { metaAccount ->
                newAssets.mapNotNull {
                    val chain = remoteMapping[it.chainId]
                    val accountId = if (chain?.isEthereumBased == true) {
                        metaAccount.ethereumAddress
                    } else {
                        metaAccount.substrateAccountId
                    } ?: return@mapNotNull null
                    AssetLocal(
                        accountId = accountId,
                        id = it.id,
                        chainId = it.chainId,
                        metaId = metaAccount.id,
                        tokenPriceId = it.priceId,
                        enabled = false
                    )
                }
            }.flatten()
            runCatching { assetsDao.insertAssets(newLocalAssets) }.onFailure {
                it.printStackTrace()
            }
        }
    }
}

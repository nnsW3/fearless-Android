package jp.co.soramitsu.runtime.multiNetwork.connection

import javax.inject.Provider
import jp.co.soramitsu.fearless_utils.wsrpc.SocketService
import jp.co.soramitsu.runtime.multiNetwork.chain.model.Chain
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

class ConnectionPool(
    private val socketServiceProvider: Provider<SocketService>,
    private val externalRequirementFlow: MutableStateFlow<ChainConnection.ExternalRequirement>
) {

    private val pool = ConcurrentHashMap<String, ChainConnection>()

    fun getConnection(chainId: String): ChainConnection = pool.getValue(chainId)

    fun setupConnection(chain: Chain, onSelectedNodeChange: (chainId: String, newNodeUrl: String) -> Unit): ChainConnection {
        val connection = pool.getOrPut(chain.id) {
            ChainConnection(
                socketService = socketServiceProvider.get(),
                initialNodes = chain.nodes,
                externalRequirementFlow = externalRequirementFlow,
                onSelectedNodeChange = { onSelectedNodeChange(chain.id, it) }
            )
        }

        connection.considerUpdateNodes(chain.nodes)

        return connection
    }

    fun removeConnection(chainId: String) {
        pool.remove(chainId)?.apply { finish() }
    }
}
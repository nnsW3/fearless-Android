package jp.co.soramitsu.feature_account_impl.presentation.node.list.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import jp.co.soramitsu.common.resources.ResourceManager
import jp.co.soramitsu.feature_account_api.domain.interfaces.NodesSettingsScenario
import jp.co.soramitsu.feature_account_impl.domain.NodesSettingsScenarioImpl
import jp.co.soramitsu.feature_account_impl.presentation.node.mixin.api.NodeListingMixin
import jp.co.soramitsu.feature_account_impl.presentation.node.mixin.impl.NodeListingProvider
import jp.co.soramitsu.runtime.multiNetwork.ChainRegistry

@InstallIn(SingletonComponent::class)
@Module
class NodesModule {

    @Provides
    fun provideNodeListingMixin(
        nodesSettingsScenario: NodesSettingsScenario,
        resourceManager: ResourceManager
    ): NodeListingMixin = NodeListingProvider(nodesSettingsScenario, resourceManager)

    @Provides
    fun provideNodesSettingsScenario(chainRegistry: ChainRegistry): NodesSettingsScenario {
        return NodesSettingsScenarioImpl(chainRegistry)
    }
}

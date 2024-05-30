package jp.co.soramitsu.staking.impl.scenarios

import java.math.BigInteger
import jp.co.soramitsu.account.api.domain.interfaces.AccountRepository
import jp.co.soramitsu.account.api.domain.model.accountId
import jp.co.soramitsu.common.list.GroupedList
import jp.co.soramitsu.common.list.emptyGroupedList
import jp.co.soramitsu.common.utils.orZero
import jp.co.soramitsu.core.utils.utilityAsset
import jp.co.soramitsu.runtime.ext.accountFromMapKey
import jp.co.soramitsu.runtime.ext.accountIdOf
import jp.co.soramitsu.runtime.multiNetwork.chain.model.Chain
import jp.co.soramitsu.runtime.multiNetwork.chain.model.ChainId
import jp.co.soramitsu.shared_utils.extensions.toHexString
import jp.co.soramitsu.shared_utils.runtime.AccountId
import jp.co.soramitsu.shared_utils.ss58.SS58Encoder
import jp.co.soramitsu.shared_utils.ss58.SS58Encoder.toAccountId
import jp.co.soramitsu.staking.api.domain.api.IdentityRepository
import jp.co.soramitsu.staking.api.domain.model.Identity
import jp.co.soramitsu.staking.api.domain.model.NominatedValidator
import jp.co.soramitsu.staking.api.domain.model.NominationPoolState
import jp.co.soramitsu.staking.api.domain.model.Nominations
import jp.co.soramitsu.staking.api.domain.model.OwnPool
import jp.co.soramitsu.staking.api.domain.model.PoolInfo
import jp.co.soramitsu.staking.api.domain.model.PoolUnbonding
import jp.co.soramitsu.staking.api.domain.model.StakingState
import jp.co.soramitsu.staking.impl.data.model.BondedPool
import jp.co.soramitsu.staking.impl.data.model.PoolMember
import jp.co.soramitsu.staking.impl.data.model.PoolRewards
import jp.co.soramitsu.staking.impl.data.repository.StakingPoolApi
import jp.co.soramitsu.staking.impl.data.repository.StakingPoolDataSource
import jp.co.soramitsu.staking.impl.domain.StakingInteractor
import jp.co.soramitsu.staking.impl.domain.getSelectedChain
import jp.co.soramitsu.staking.impl.domain.validators.current.CurrentValidatorsInteractor
import jp.co.soramitsu.staking.impl.presentation.common.EditPoolFlowState
import jp.co.soramitsu.staking.impl.scenarios.relaychain.StakingRelayChainScenarioRepository
import jp.co.soramitsu.wallet.impl.domain.interfaces.WalletConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flattenMerge
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class StakingPoolInteractor(
    private val api: StakingPoolApi,
    private val dataSource: StakingPoolDataSource,
    private val stakingInteractor: StakingInteractor,
    private val relayChainRepository: StakingRelayChainScenarioRepository,
    private val accountRepository: AccountRepository,
    private val identitiesRepositoryImpl: IdentityRepository,
    private val walletConstants: WalletConstants,
    private val currentValidatorsInteractor: CurrentValidatorsInteractor
) {

    @OptIn(ExperimentalCoroutinesApi::class)
    fun stakingStateFlow(): Flow<StakingState> {
        val currentChainFlow = stakingInteractor.selectedChainFlow().filter { it.supportStakingPool }
        val selectedAccountFlow = accountRepository.selectedMetaAccountFlow()

        return combine(currentChainFlow, selectedAccountFlow) { chain, metaAccount ->
            val accountId = metaAccount.accountId(chain) ?: error("cannot find accountId")
            stakingPoolStateFlow(chain, accountId)
        }.flattenMerge()
    }

    private fun stakingPoolStateFlow(chain: Chain, accountId: AccountId): Flow<StakingState> {
        return observeCurrentPool(chain, accountId).map {
            when (it) {
                null -> StakingState.Pool.None(chain, accountId)
                else -> StakingState.Pool.Member(chain, accountId, it)
            }
        }.runCatching { this }.getOrDefault(emptyFlow())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeCurrentPool(
        chain: Chain,
        accountId: AccountId
    ): Flow<OwnPool?> {
        return dataSource.observePoolMembers(chain.id, accountId).distinctUntilChanged().flatMapLatest { poolMember ->
            poolMember ?: return@flatMapLatest flowOf(null)
            observePoolInfo(chain, poolMember.poolId).map { poolInfo ->
                val pendingRewards = dataSource.getPendingRewards(chain.id, accountId).getOrNull().orZero()
                val currentEra = relayChainRepository.getCurrentEraIndex(chain.id)
                val unbondingEras = poolMember.unbondingEras.map { PoolUnbonding(it.era, it.amount) }
                val redeemable = unbondingEras.filter { it.era <= currentEra }.sumOf { it.amount }
                val unbonding = unbondingEras.filter { it.era > currentEra }.sumOf { it.amount }
                poolInfo.toOwnPool(poolMember, redeemable, unbonding, pendingRewards)
            }
        }
    }

    private suspend fun observePoolInfo(chain: Chain, poolId: BigInteger): Flow<PoolInfo> {
        val poolStashAccount = generatePoolStashAccount(chain, poolId)
        return combine(
            dataSource.observePool(chain.id, poolId),
            relayChainRepository.observeRemoteAccountNominations(chain.id, poolStashAccount)
        ) { bondedPool, nominations ->
            bondedPool ?: return@combine null

            val name = dataSource.getPoolMetadata(chain.id, poolId) ?: "Pool #$poolId"

            val hasValidators = nominations?.targets?.isNotEmpty() == true
            val bondedPoolState = NominationPoolState.from(bondedPool.state.name)
            val state = when {
                bondedPoolState == NominationPoolState.Open &&
                    hasValidators.not() -> NominationPoolState.HasNoValidators
                else -> NominationPoolState.from(bondedPool.state.name)
            }
            PoolInfo(
                poolId,
                name,
                bondedPool.points,
                state,
                bondedPool.memberCounter,
                bondedPool.depositor,
                bondedPool.root,
                bondedPool.nominator,
                bondedPool.bouncer
            )
        }.filterNotNull()
    }

    @Deprecated("Manual calculating is deprecated", replaceWith = ReplaceWith("dataSource.getPendingRewards"))
    private suspend fun calculatePendingRewards(chain: Chain, poolMember: PoolMember, bondedPool: BondedPool, rewardPool: PoolRewards?): BigInteger {
        rewardPool ?: return BigInteger.ZERO
        val rewardsAccountId = generatePoolRewardAccount(chain, poolMember.poolId)
        val existentialDeposit = chain.utilityAsset?.let { walletConstants.existentialDeposit(it) }.orZero()
        val rewardsAccountBalance = stakingInteractor.getAccountBalance(chain.id, rewardsAccountId).data.free.subtract(existentialDeposit)
        val payoutSinceLastRecord = rewardsAccountBalance.add(rewardPool.totalRewardsClaimed).subtract(rewardPool.lastRecordedTotalPayouts)
        val rewardCounterBase = BigInteger.valueOf(10).pow(18)
        val currentRewardCounter = payoutSinceLastRecord.multiply(rewardCounterBase).divide(bondedPool.points).add(rewardPool.lastRecordedRewardCounter)
        return currentRewardCounter.subtract(rewardPool.lastRecordedRewardCounter).multiply(poolMember.points).divide(rewardCounterBase)
    }

    private suspend fun generatePoolRewardAccount(chain: Chain, poolId: BigInteger): ByteArray {
        return generatePoolAccountId(1, chain, poolId)
    }

    private suspend fun generatePoolStashAccount(chain: Chain, poolId: BigInteger): ByteArray {
        return generatePoolAccountId(0, chain, poolId)
    }

    private suspend fun generatePoolAccountId(index: Int, chain: Chain, poolId: BigInteger): ByteArray {
        val palletId = relayChainRepository.getNominationPoolPalletId(chain.id)
        val modPrefix = "modl".toByteArray()
        val indexBytes = index.toByte()
        val poolIdBytes = poolId.toByteArray().reversedArray() // reversed for little-endian order
        val empty = ByteArray(32)
        val source = modPrefix + palletId + indexBytes + poolIdBytes + empty
        val encoded = SS58Encoder.encode(source.take(32).toByteArray(), chain.addressPrefix.toShort())
        return encoded.toAccountId()
    }

    private fun PoolInfo.toOwnPool(poolMember: PoolMember, redeemable: BigInteger, unbonding: BigInteger, pendingRewards: BigInteger): OwnPool {
        return OwnPool(
            poolId = poolId,
            name = name,
            myStakeInPlanks = poolMember.points,
            totalStakedInPlanks = stakedInPlanks,
            lastRecordedRewardCounter = poolMember.lastRecordedRewardCounter,
            state = state,
            redeemable = redeemable,
            unbonding = unbonding,
            pendingRewards = pendingRewards,
            members = members,
            depositor = depositor,
            root = root,
            nominator = nominator,
            stateToggler = stateToggler
        )
    }

    suspend fun getMinToJoinPool(chainId: ChainId): BigInteger {
        return dataSource.minJoinBond(chainId)
    }

    suspend fun getMinToCreate(chainId: ChainId): BigInteger {
        return dataSource.minCreateBond(chainId)
    }

    suspend fun getExistingPools(chainId: ChainId): BigInteger {
        return dataSource.existingPools(chainId)
    }

    suspend fun getPossiblePools(chainId: ChainId): BigInteger {
        return dataSource.maxPools(chainId) ?: BigInteger.ZERO
    }

    suspend fun getPoolsCount(chainId: ChainId): BigInteger {
        return dataSource.getPoolsCount(chainId)
    }

    suspend fun getMaxMembersInPool(chainId: ChainId): BigInteger {
        return dataSource.maxMembersInPool(chainId) ?: BigInteger.ZERO
    }

    suspend fun getMaxPoolsMembers(chainId: ChainId): BigInteger {
        return dataSource.maxPoolMembers(chainId) ?: BigInteger.ZERO
    }

    suspend fun getAllPools(chain: Chain): List<PoolInfo> {
        val poolsMetadata = dataSource.poolsMetadata(chain.id)
        val pools = dataSource.bondedPools(chain.id)
        return pools.mapNotNull { (id, pool) ->
            pool ?: return@mapNotNull null

            val poolStashAccount = generatePoolStashAccount(chain, id)
            relayChainRepository.observeRemoteAccountNominations(chain.id, poolStashAccount)
            val name = poolsMetadata[id] ?: "Pool #$id"
            val state = NominationPoolState.from(pool.state.name)
            PoolInfo(
                id,
                name,
                pool.points,
                state,
                pool.memberCounter,
                pool.depositor,
                pool.root,
                pool.nominator,
                pool.bouncer
            )
        }
    }

    suspend fun getIdentities(accountIds: List<AccountId>): Map<String, Identity?> {
        if (accountIds.isEmpty()) return emptyMap()
        val chain = stakingInteractor.getSelectedChain()
        return identitiesRepositoryImpl.getIdentitiesFromIds(chain, accountIds.map { it.toHexString(false) })
    }

    suspend fun getAccountName(address: String): String? {
        return withContext(Dispatchers.Default) {
            val chain = stakingInteractor.getSelectedChain()
            val accountId = chain.accountIdOf(address)
            val metaAccount = accountRepository.findMetaAccount(accountId)
            if (metaAccount != null) {
                metaAccount.name
            } else {
                val identities = getIdentities(listOf(accountId))
                val map = identities.mapNotNull {
                    chain.accountFromMapKey(it.key) to it.value?.display
                }.toMap()
                map[address]
            }
        }
    }

    suspend fun getValidatorsIds(chain: Chain, poolId: BigInteger): List<AccountId> {
        return getNominations(chain, poolId)?.targets ?: emptyList()
    }

    suspend fun nominatedValidatorsFlow(
        chain: Chain,
        poolId: BigInteger
    ): Flow<GroupedList<NominatedValidator.Status.Group, NominatedValidator>> {
        val poolStashId = generatePoolStashAccount(chain, poolId)
        val nominations = getNominations(chain, poolId) ?: return flowOf(emptyGroupedList())
        return currentValidatorsInteractor.nominatedValidatorsFlow(chain, poolStashId, nominations)
    }

    private suspend fun getNominations(chain: Chain, poolId: BigInteger): Nominations? {
        val poolStashAccount = generatePoolStashAccount(chain, poolId)
        return relayChainRepository.getRemoteAccountNominations(chain.id, poolStashAccount)
    }

    suspend fun getLastPoolId(chainId: ChainId) = dataSource.lastPoolId(chainId)

    suspend fun estimateJoinFee(amount: BigInteger, poolId: BigInteger = BigInteger.ZERO) = api.estimateJoinFee(amount, poolId)

    suspend fun joinPool(address: String, amount: BigInteger, poolId: BigInteger) = api.joinPool(address, amount, poolId)

    suspend fun estimateBondMoreFee(amount: BigInteger) = api.estimateBondExtraFee(amount)

    suspend fun bondMore(address: String, amount: BigInteger) = api.bondExtra(address, amount)

    suspend fun estimateUnstakeFee(address: String, amount: BigInteger) = api.estimateUnbondFee(address, amount)

    suspend fun unstake(address: String, amount: BigInteger) = api.unbond(address, amount)

    suspend fun estimateRedeemFee(address: String) = api.estimateWithdrawUnbondedFee(address)

    suspend fun redeem(address: String) = api.withdrawUnbonded(address)

    suspend fun estimateClaimFee() = api.estimateClaimPayoutFee()

    suspend fun claim(address: String) = api.claimPayout(address)

    suspend fun estimateCreateFee(
        poolId: BigInteger,
        name: String,
        amountInPlanks: BigInteger,
        rootAddress: String,
        nominatorAddress: String,
        stateToggler: String
    ) = api.estimateCreatePoolFee(name, poolId, amountInPlanks, rootAddress, nominatorAddress, stateToggler)

    suspend fun createPool(
        poolId: BigInteger,
        name: String,
        amountInPlanks: BigInteger,
        rootAddress: String,
        nominatorAddress: String,
        stateToggler: String
    ) = api.createPool(name, poolId, amountInPlanks, rootAddress, nominatorAddress, stateToggler)

    suspend fun estimateNominateFee(
        poolId: BigInteger,
        vararg validators: AccountId
    ) = api.estimateNominatePoolFee(poolId, *validators)

    suspend fun nominate(
        poolId: BigInteger,
        accountAddress: String,
        vararg validators: AccountId
    ) = api.nominatePool(poolId, accountAddress, *validators)

    suspend fun estimateEditFee(state: EditPoolFlowState) = api.estimateEditPool(state)

    suspend fun edit(state: EditPoolFlowState, address: String) = api.editPool(state, address)
}

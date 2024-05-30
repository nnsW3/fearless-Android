package jp.co.soramitsu.staking.impl.presentation.confirm.pool.create

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigInteger
import javax.inject.Inject
import jp.co.soramitsu.common.compose.component.TitleValueViewState
import jp.co.soramitsu.common.resources.ResourceManager
import jp.co.soramitsu.common.utils.flowOf
import jp.co.soramitsu.common.utils.inBackground
import jp.co.soramitsu.feature_staking_impl.R
import jp.co.soramitsu.staking.impl.presentation.StakingConfirmViewModel
import jp.co.soramitsu.staking.impl.presentation.StakingRouter
import jp.co.soramitsu.staking.impl.presentation.common.SelectValidatorFlowState
import jp.co.soramitsu.staking.impl.presentation.common.StakingPoolSharedStateProvider
import jp.co.soramitsu.staking.impl.scenarios.StakingPoolInteractor
import jp.co.soramitsu.wallet.api.domain.ExistentialDepositUseCase
import jp.co.soramitsu.wallet.impl.domain.interfaces.WalletInteractor
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class ConfirmCreatePoolViewModel @Inject constructor(
    walletInteractor: WalletInteractor,
    existentialDepositUseCase: ExistentialDepositUseCase,
    poolSharedStateProvider: StakingPoolSharedStateProvider,
    private val stakingPoolInteractor: StakingPoolInteractor,
    resourceManager: ResourceManager,
    private val router: StakingRouter,
    private val poolInteractor: StakingPoolInteractor
) : StakingConfirmViewModel(
    walletInteractor = walletInteractor,
    existentialDepositUseCase = existentialDepositUseCase,
    chain = poolSharedStateProvider.requireMainState.requireChain,
    router = router,
    address = poolSharedStateProvider.requireMainState.requireAddress,
    resourceManager = resourceManager,
    asset = poolSharedStateProvider.requireMainState.requireAsset,
    amountInPlanks = poolSharedStateProvider.requireCreateState.requireAmountInPlanks,
    customIcon = R.drawable.ic_vector,
    feeEstimator = { stakingPoolInteractor.estimateCreatePoolFee(poolSharedStateProvider) },
    executeOperation = { address, _ -> stakingPoolInteractor.createPool(poolSharedStateProvider, address) },
    onOperationSuccess = {
        poolSharedStateProvider.selectValidatorsState.set(
            SelectValidatorFlowState(
                poolName = poolSharedStateProvider.requireCreateState.requirePoolName,
                poolId = poolSharedStateProvider.requireCreateState.requirePoolId.toBigInteger()
            )
        )
        router.returnToMain()
        router.openStartSelectValidators()
    },
    accountNameProvider = { stakingPoolInteractor.getAccountName(it) },
    titleRes = R.string.pool_staking_create_confirm_title,
    customSuccessMessage = resourceManager.getString(R.string.pool_create_success_message)
) {
    private val addressDisplayFlow = flowOf {
        poolInteractor.getAccountName(address) ?: address
    }.inBackground()

    private val nominatorDisplayFlow = flowOf {
        val nominatorAddress = poolSharedStateProvider.requireCreateState.requireNominatorAddress
        poolInteractor.getAccountName(nominatorAddress) ?: nominatorAddress
    }.inBackground()

    private val stateTogglerDisplayFlow = flowOf {
        val stateTogglerAddress = poolSharedStateProvider.requireCreateState.requireStateTogglerAddress
        poolInteractor.getAccountName(stateTogglerAddress) ?: stateTogglerAddress
    }.inBackground()

    override val tableItemsFlow: StateFlow<List<TitleValueViewState>> = combine(
        addressDisplayFlow,
        nominatorDisplayFlow,
        stateTogglerDisplayFlow,
        feeViewStateFlow
    ) { addressDisplay, nominatorDisplay, stateTogglerDisplay, feeViewState ->
        val createState = poolSharedStateProvider.requireCreateState

        val poolId = TitleValueViewState(resourceManager.getString(R.string.pool_staking_pool_id), createState.requirePoolId.toString())
        val addressState = defaultAddressViewState.copy(value = addressDisplay)
        val depositor = TitleValueViewState(resourceManager.getString(R.string.pool_staking_depositor), addressDisplay)
        val root = TitleValueViewState(resourceManager.getString(R.string.pool_staking_root), addressDisplay)
        val nominator = TitleValueViewState(resourceManager.getString(R.string.pool_staking_nominator), nominatorDisplay)
        val stateToggler = TitleValueViewState(resourceManager.getString(R.string.pool_staking_bouncer), stateTogglerDisplay)
        listOf(addressState, amountViewState, poolId, depositor, root, nominator, stateToggler, feeViewState)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun onBackClick() {
        router.back()
    }
}

private suspend fun StakingPoolInteractor.estimateCreatePoolFee(poolSharedStateProvider: StakingPoolSharedStateProvider): BigInteger {
    val createFlowState = poolSharedStateProvider.requireCreateState
    val address = poolSharedStateProvider.requireMainState.requireAddress

    return estimateCreateFee(
        createFlowState.requirePoolId.toBigInteger(),
        createFlowState.requirePoolName,
        createFlowState.requireAmountInPlanks,
        address,
        createFlowState.requireNominatorAddress,
        createFlowState.requireStateTogglerAddress
    )
}

private suspend fun StakingPoolInteractor.createPool(poolSharedStateProvider: StakingPoolSharedStateProvider, address: String): Result<String> {
    val createFlowState = poolSharedStateProvider.requireCreateState

    return createPool(
        createFlowState.requirePoolId.toBigInteger(),
        createFlowState.requirePoolName,
        createFlowState.requireAmountInPlanks,
        address,
        createFlowState.requireNominatorAddress,
        createFlowState.requireStateTogglerAddress
    )
}

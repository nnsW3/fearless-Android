package jp.co.soramitsu.feature_wallet_impl.presentation.send.amount

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.liveData
import androidx.lifecycle.viewModelScope
import java.math.BigDecimal
import java.math.BigInteger
import jp.co.soramitsu.common.address.AddressIconGenerator
import jp.co.soramitsu.common.address.AddressModel
import jp.co.soramitsu.common.address.createAddressModel
import jp.co.soramitsu.common.base.BaseViewModel
import jp.co.soramitsu.common.data.network.BlockExplorerUrlBuilder
import jp.co.soramitsu.common.utils.Event
import jp.co.soramitsu.common.utils.combine
import jp.co.soramitsu.common.utils.format
import jp.co.soramitsu.common.utils.formatAsCurrency
import jp.co.soramitsu.common.utils.map
import jp.co.soramitsu.common.utils.mediateWith
import jp.co.soramitsu.common.utils.requireValue
import jp.co.soramitsu.common.view.ButtonState
import jp.co.soramitsu.feature_account_api.presentation.actions.ExternalAccountActions
import jp.co.soramitsu.feature_wallet_api.domain.interfaces.WalletConstants
import jp.co.soramitsu.feature_wallet_api.domain.interfaces.WalletInteractor
import jp.co.soramitsu.feature_wallet_api.domain.model.Asset
import jp.co.soramitsu.feature_wallet_api.domain.model.Fee
import jp.co.soramitsu.feature_wallet_api.domain.model.Transfer
import jp.co.soramitsu.feature_wallet_api.domain.model.TransferValidityLevel.Error
import jp.co.soramitsu.feature_wallet_api.domain.model.TransferValidityLevel.Ok
import jp.co.soramitsu.feature_wallet_api.domain.model.TransferValidityLevel.Warning
import jp.co.soramitsu.feature_wallet_api.domain.model.TransferValidityStatus
import jp.co.soramitsu.feature_wallet_api.domain.model.amountFromPlanks
import jp.co.soramitsu.feature_wallet_api.presentation.formatters.formatTokenAmount
import jp.co.soramitsu.feature_wallet_api.presentation.mixin.TransferValidityChecks
import jp.co.soramitsu.feature_wallet_impl.R
import jp.co.soramitsu.feature_wallet_impl.data.mappers.mapAssetToAssetModel
import jp.co.soramitsu.feature_wallet_impl.presentation.AssetPayload
import jp.co.soramitsu.feature_wallet_impl.presentation.WalletRouter
import jp.co.soramitsu.feature_wallet_impl.presentation.send.BalanceDetailsBottomSheet
import jp.co.soramitsu.feature_wallet_impl.presentation.send.TransferDraft
import jp.co.soramitsu.feature_wallet_impl.presentation.send.phishing.warning.api.PhishingWarningMixin
import jp.co.soramitsu.feature_wallet_impl.presentation.send.phishing.warning.api.PhishingWarningPresentation
import jp.co.soramitsu.feature_wallet_impl.presentation.send.phishing.warning.api.proceedOrShowPhishingWarning
import jp.co.soramitsu.runtime.multiNetwork.ChainRegistry
import jp.co.soramitsu.runtime.multiNetwork.chain.model.Chain
import jp.co.soramitsu.runtime.multiNetwork.chain.model.getSupportedExplorers
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.launch

private const val AVATAR_SIZE_DP = 24

private const val RETRY_TIMES = 3L

enum class RetryReason(val reasonRes: Int) {
    CHECK_ENOUGH_FUNDS(R.string.choose_amount_error_balance),
    LOAD_FEE(R.string.choose_amount_error_fee)
}

class ChooseAmountViewModel(
    private val interactor: WalletInteractor,
    private val router: WalletRouter,
    private val addressIconGenerator: AddressIconGenerator,
    private val externalAccountActions: ExternalAccountActions.Presentation,
    private val transferValidityChecks: TransferValidityChecks.Presentation,
    private val walletConstants: WalletConstants,
    private val recipientAddress: String,
    private val assetPayload: AssetPayload,
    private val phishingAddress: PhishingWarningMixin,
    private val chainRegistry: ChainRegistry
) : BaseViewModel(),
    ExternalAccountActions by externalAccountActions,
    TransferValidityChecks by transferValidityChecks,
    PhishingWarningMixin by phishingAddress,
    PhishingWarningPresentation {

    val recipientModelLiveData = liveData {
        emit(generateAddressModel(recipientAddress))
    }

    private val amountDecimalFlow = MutableStateFlow(BigDecimal.ZERO)

    private val amountEvents = MutableStateFlow("0")
    val amountRawLiveData = amountEvents.asLiveData()

    private val _feeLoadingLiveData = MutableLiveData<Boolean>(true)
    val feeLoadingLiveData = _feeLoadingLiveData

    private val assetLiveData = liveData {
        val asset = interactor.getCurrentAsset(assetPayload.chainId, assetPayload.chainAssetId)

        emit(asset)
        updateExistentialDeposit(asset.token.configuration)
    }

    private val tipLiveData = liveData { walletConstants.tip(assetPayload.chainId)?.let { emit(it) } }
    private val tipAmountLiveData = mediateWith(tipLiveData, assetLiveData) { (tip: BigInteger?, asset: Asset?) ->
        tip?.let {
            asset?.token?.amountFromPlanks(it)
        }
    }

    val tipAmountTextLiveData = mediateWith(tipAmountLiveData, assetLiveData) { (tip: BigDecimal?, asset: Asset?) ->
        asset?.token?.configuration?.symbol?.let {
            tip?.formatTokenAmount(it)
        }
    }
    val tipFiatAmountLiveData = mediateWith(tipAmountLiveData, assetLiveData) { (tip: BigDecimal?, asset: Asset?) ->
        tip?.let {
            asset?.token?.fiatAmount(it)?.formatAsCurrency(asset.token.fiatSymbol)
        }
    }

    val feeLiveData = MutableLiveData<Fee?>()
    val feeFiatLiveData = combine(assetLiveData, feeLiveData) { (asset: Asset, fee: Fee?) ->
        fee?.feeAmount?.let {
            asset.token.fiatAmount(it)?.formatAsCurrency(asset.token.fiatSymbol)
        }
    }

    private val _feeErrorLiveData = MutableLiveData<Event<RetryReason>>()
    val feeErrorLiveData = _feeErrorLiveData

    private val checkingEnoughFundsLiveData = MutableLiveData(false)

    private val _showBalanceDetailsEvent = MutableLiveData<Event<BalanceDetailsBottomSheet.Payload>>()
    val showBalanceDetailsEvent: LiveData<Event<BalanceDetailsBottomSheet.Payload>> = _showBalanceDetailsEvent

    val assetModelLiveData = assetLiveData.map { mapAssetToAssetModel(it) }

    private val minimumPossibleAmountLiveData = assetLiveData.map {
        it.token.configuration.amountFromPlanks(BigInteger.ONE)
    }

    private var existentialDeposit: BigDecimal? = null

    val enteredFiatAmountLiveData = combine(assetLiveData, amountRawLiveData) { (asset: Asset, amount: String) ->
        amount.toBigDecimalOrNull()?.let {
            asset.token.fiatAmount(it)?.formatAsCurrency(asset.token.fiatSymbol)
        }
    }

    val continueButtonStateLiveData = combine(
        feeLoadingLiveData,
        feeLiveData,
        checkingEnoughFundsLiveData,
        amountRawLiveData,
        minimumPossibleAmountLiveData
    ) { (feeLoading: Boolean, fee: Fee?, checkingFunds: Boolean, amountRaw: String, minimumPossibleAmount: BigDecimal) ->
        when {
            feeLoading || checkingFunds -> ButtonState.PROGRESS
            fee != null && fee.transferAmount >= minimumPossibleAmount
                && amountRaw.isNotEmpty() -> ButtonState.NORMAL
            else -> ButtonState.DISABLED
        }
    }

    private val feeCalculationTriggerFlow = MutableStateFlow("0")

    init {
        feeFlow().launchIn(viewModelScope)
    }

    private suspend fun updateExistentialDeposit(tokenConfiguration: Chain.Asset) {
        val amountInPlanks = kotlin.runCatching {
            walletConstants.existentialDeposit(tokenConfiguration.chainId)
        }.getOrDefault(BigInteger.ZERO)

        existentialDeposit = tokenConfiguration.amountFromPlanks(amountInPlanks)
    }

    fun nextClicked() {
        checkEnoughFunds()
    }

    fun amountChanged(newAmountRaw: String) {
        viewModelScope.launch {
            amountEvents.value = newAmountRaw
            val fee = feeLiveData.value
            fee?.let {
                val amountFromLastFeeCalculation = (it.transferAmount - it.feeAmount).format()
                if (newAmountRaw != amountFromLastFeeCalculation) {
                    feeCalculationTriggerFlow.emit(newAmountRaw)
                }
            } ?: feeCalculationTriggerFlow.emit(newAmountRaw)
        }
    }

    fun backClicked() {
        router.back()
    }

    fun retry(retryReason: RetryReason) {
        when (retryReason) {
            RetryReason.LOAD_FEE -> retryLoadFee()
            RetryReason.CHECK_ENOUGH_FUNDS -> checkEnoughFunds()
        }
    }

    fun recipientAddressClicked() = launch {
        val recipientAddress = recipientModelLiveData.value?.address ?: return@launch
        val chainId = assetLiveData.value?.token?.configuration?.chainId ?: return@launch
        val chain = chainRegistry.getChain(chainId)
        val supportedExplorers = chain.explorers.getSupportedExplorers(BlockExplorerUrlBuilder.Type.ACCOUNT, recipientAddress)
        val externalActionsPayload = ExternalAccountActions.Payload(
            value = recipientAddress,
            chainId = chainId,
            chainName = chain.name,
            explorers = supportedExplorers
        )

        externalAccountActions.showExternalActions(externalActionsPayload)
    }

    fun warningConfirmed() {
        openConfirmationScreen()
    }

    override fun proceedAddress(address: String) {
        val transferDraft = buildTransferDraft() ?: return

        router.openConfirmTransfer(transferDraft)
    }

    override fun declinePhishingAddress() {
        router.back()
    }

    private suspend fun calculateFee(amount: BigDecimal?): Fee? {
        amount ?: return null
        _feeLoadingLiveData.postValue(true)
        val asset = interactor.getCurrentAsset(assetPayload.chainId, assetPayload.chainAssetId)
        val transfer = Transfer(recipientAddress, amount, asset.token.configuration)

        runCatching { interactor.getTransferFee(transfer) }.fold(
            {
                _feeLoadingLiveData.value = false
                return it
            },
            {
                _feeLoadingLiveData.value = false
                _feeErrorLiveData.postValue(Event(RetryReason.LOAD_FEE))
                return null
            }
        )
    }

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private fun feeFlow(): Flow<Fee?> = feeCalculationTriggerFlow
        .mapNotNull(String::toBigDecimalOrNull)
        .debounce(500.toDuration(DurationUnit.MILLISECONDS))
        .distinctUntilChanged()
        .onEach { _feeLoadingLiveData.postValue(true) }
        .mapLatest<BigDecimal, Fee?> { amount ->
            amountDecimalFlow.emit(amount)
            val asset = interactor.getCurrentAsset(assetPayload.chainId, assetPayload.chainAssetId)
            val transfer = Transfer(recipientAddress, amount, asset.token.configuration)

            interactor.getTransferFee(transfer)
        }
        .retry(RETRY_TIMES)
        .catch {
            _feeErrorLiveData.postValue(Event(RetryReason.LOAD_FEE))

            emit(null)
        }.onEach {
            feeLiveData.postValue(it)
            _feeLoadingLiveData.value = false
        }

    private suspend fun generateAddressModel(address: String): AddressModel {
        return addressIconGenerator.createAddressModel(address, AVATAR_SIZE_DP)
    }

    private fun checkEnoughFunds() {
        checkingEnoughFundsLiveData.value = true

        viewModelScope.launch {
            val asset = interactor.getCurrentAsset(assetPayload.chainId, assetPayload.chainAssetId)
            val transfer = Transfer(recipientAddress, amountDecimalFlow.value, asset.token.configuration)

            val result = interactor.checkTransferValidityStatus(transfer)

            if (result.isSuccess) {
                processHasEnoughFunds(result.requireValue())
            } else {
                _feeErrorLiveData.value = Event(RetryReason.CHECK_ENOUGH_FUNDS)
            }

            checkingEnoughFundsLiveData.value = false
        }
    }

    private fun processHasEnoughFunds(status: TransferValidityStatus) {
        when (status) {
            is Ok -> openConfirmationScreen()
            is Warning.Status -> transferValidityChecks.showTransferWarning(status)
            is Error.Status -> transferValidityChecks.showTransferError(status)
        }
    }

    private fun openConfirmationScreen() {
        viewModelScope.launch {
            proceedOrShowPhishingWarning(recipientAddress)
        }
    }

    private fun buildTransferDraft(): TransferDraft? {
        val fee = feeLiveData.value ?: return null
        val tip = tipAmountLiveData.value

        return TransferDraft(amountDecimalFlow.value, fee.feeAmount, assetPayload, recipientAddress, tip)
    }

    private fun retryLoadFee() {
        amountChanged(amountEvents.value)
    }

    fun quickInputSelected(value: Double) {
        viewModelScope.launch {
            val allAmount = assetModelLiveData.value?.available ?: return@launch
            val tip = tipAmountLiveData.value ?: BigDecimal.ZERO
            val amountToTransfer = (allAmount * value.toBigDecimal()) - tip
            val fee = calculateFee(amountToTransfer) ?: return@launch
            feeLiveData.value = fee

            val quickAmountWithoutExtraPays = amountToTransfer - fee.feeAmount

            if (quickAmountWithoutExtraPays < BigDecimal.ZERO) {
                return@launch
            }

            val newAmount = quickAmountWithoutExtraPays.format()
            amountDecimalFlow.emit(quickAmountWithoutExtraPays)
            amountEvents.emit(newAmount)
        }
    }
}

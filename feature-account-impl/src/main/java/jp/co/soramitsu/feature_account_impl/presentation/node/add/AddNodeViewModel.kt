package jp.co.soramitsu.feature_account_impl.presentation.node.add

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import jp.co.soramitsu.common.resources.ResourceManager
import jp.co.soramitsu.common.utils.combine
import jp.co.soramitsu.common.utils.requireException
import jp.co.soramitsu.common.view.ButtonState
import jp.co.soramitsu.feature_account_api.domain.interfaces.NodesSettingsScenario
import jp.co.soramitsu.feature_account_impl.R
import jp.co.soramitsu.feature_account_impl.domain.NodeHostValidator
import jp.co.soramitsu.feature_account_impl.presentation.AccountRouter
import jp.co.soramitsu.feature_account_impl.presentation.node.NodeDetailsRootViewModel
import kotlinx.coroutines.launch

class AddNodeViewModel @AssistedInject constructor(
    private val nodesSettingsScenario: NodesSettingsScenario,
    private val router: AccountRouter,
    private val nodeHostValidator: NodeHostValidator,
    resourceManager: ResourceManager,
    @Assisted private val chainId: String
) : NodeDetailsRootViewModel(resourceManager) {

    val nodeNameInputLiveData = MutableLiveData("")
    val nodeHostInputLiveData = MutableLiveData("")

    private val addingInProgressLiveData = MutableLiveData(false)

    val addButtonState = combine(
        nodeNameInputLiveData,
        nodeHostInputLiveData,
        addingInProgressLiveData
    ) { (name: String, host: String, addingInProgress: Boolean) ->
        when {
            addingInProgress -> LabeledButtonState(ButtonState.PROGRESS)
            name.isEmpty() -> LabeledButtonState(ButtonState.DISABLED, resourceManager.getString(R.string.error_message_enter_the_name))
            !nodeHostValidator.hostIsValid(host) -> LabeledButtonState(
                ButtonState.DISABLED,
                resourceManager.getString(R.string.error_message_enter_the_url_address)
            )
            else -> LabeledButtonState(ButtonState.NORMAL, resourceManager.getString(R.string.add_node_button_title))
        }
    }

    fun backClicked() {
        router.back()
    }

    fun addNodeClicked() {
        val nodeName = nodeNameInputLiveData.value ?: return
        val nodeHost = nodeHostInputLiveData.value ?: return

        addingInProgressLiveData.value = true

        viewModelScope.launch {
            val result = nodesSettingsScenario.addNode(chainId, nodeName, nodeHost)

            if (result.isSuccess) {
                router.back()
            } else {
                handleNodeException(result.requireException())
                addingInProgressLiveData.postValue(false)
            }
        }
    }

    @AssistedFactory
    interface AddNodeViewModelFactory {
        fun create(chainId: String): AddNodeViewModel
    }

    @Suppress("UNCHECKED_CAST")
    companion object {
        fun provideFactory(
            factory: AddNodeViewModelFactory,
            chainId: String
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return factory.create(chainId) as T
            }
        }
    }
}

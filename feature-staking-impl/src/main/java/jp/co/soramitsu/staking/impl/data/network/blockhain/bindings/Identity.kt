package jp.co.soramitsu.staking.impl.data.network.blockhain.bindings

import jp.co.soramitsu.common.data.network.runtime.binding.HelperBinding
import jp.co.soramitsu.common.data.network.runtime.binding.UseCaseBinding
import jp.co.soramitsu.common.data.network.runtime.binding.cast
import jp.co.soramitsu.common.data.network.runtime.binding.incompatible
import jp.co.soramitsu.common.utils.second
import jp.co.soramitsu.shared_utils.extensions.toHexString
import jp.co.soramitsu.shared_utils.runtime.RuntimeSnapshot
import jp.co.soramitsu.shared_utils.runtime.definitions.types.Type
import jp.co.soramitsu.shared_utils.runtime.definitions.types.composite.Struct
import jp.co.soramitsu.shared_utils.runtime.definitions.types.fromHexOrNull
import jp.co.soramitsu.staking.api.domain.model.Identity
import jp.co.soramitsu.staking.api.domain.model.RootIdentity
import jp.co.soramitsu.staking.api.domain.model.SuperOf

/*
Registration: {
  judgements: Vec<Judgement>;
  deposit: Balance;
  info: IdentityInfo;
}

IdentityInfo: {
  additional: Vec<IdentityInfoAdditional>;
  display: Data;
  legal: Data;
  web: Data;
  riot: Data;
  email: Data;
  pgpFingerprint: Option<H160>;
  image: Data;
  twitter: Data;
}
 */

@UseCaseBinding
fun bindIdentity(
    scale: String,
    runtime: RuntimeSnapshot,
    type: Type<*>
): Identity {
    val decoded = type.fromHexOrNull(runtime, scale)// as? Struct.Instance ?: incompatible()
    val struct = when (decoded) {
        is Struct.Instance -> decoded.cast<Struct.Instance>()
        is ArrayList<*> -> decoded.cast<ArrayList<*>>().first() as Struct.Instance
        else -> return RootIdentity.empty()
    }

    val identityInfo = struct.get<Struct.Instance>("info") ?: incompatible()

    val pgpFingerprint = identityInfo.get<ByteArray?>("pgpFingerprint")

    return RootIdentity(
        display = bindIdentityData(identityInfo, "display"),
        legal = bindIdentityData(identityInfo, "legal"),
        web = bindIdentityData(identityInfo, "web"),
        riot = bindIdentityData(identityInfo, "riot"),
        email = bindIdentityData(identityInfo, "email"),
        pgpFingerprint = pgpFingerprint?.toHexString(withPrefix = true),
        image = bindIdentityData(identityInfo, "image"),
        twitter = bindIdentityData(identityInfo, "twitter")
    )
}

@UseCaseBinding
fun bindSuperOf(
    scale: String,
    runtime: RuntimeSnapshot,
    type: Type<*>
): SuperOf {
    val decoded = type.fromHexOrNull(runtime, scale) as? List<*> ?: incompatible()

    val parentId: ByteArray = decoded.first().cast()

    return SuperOf(
        parentIdHex = parentId.toHexString(),
        childName = bindData(decoded.second()).asString()
    )
}

@HelperBinding
fun bindIdentityData(identityInfo: Struct.Instance, field: String): String? {
    val value = identityInfo.get<Any?>(field) ?: return null

    return bindData(value).asString()
}

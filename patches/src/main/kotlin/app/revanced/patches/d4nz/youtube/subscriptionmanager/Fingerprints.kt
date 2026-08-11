package app.revanced.patches.d4nz.youtube.subscriptionmanager

import app.revanced.patcher.accessFlags
import app.revanced.patcher.allOf
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.instructions
import app.revanced.patcher.invoke
import app.revanced.patcher.method
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType
import app.revanced.patcher.strings
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal const val ACCOUNT_IDENTITY_DESCRIPTOR =
    "Lcom/google/android/libraries/youtube/account/identity/AccountIdentity;"
internal const val LITHO_COMPONENT_TREE_DESCRIPTOR = "Lcom/facebook/litho/ComponentTree;"
internal const val SUBSCRIPTION_MANAGER_SWIPE_HANDLER_DESCRIPTOR =
    "Lapp/revanced/extension/d4nz/youtube/subscriptionmanager/SubscriptionManagerSwipeHandler;"

/** Mutable handle for the globally validated ComponentTree producer/consumer seam. */
internal val BytecodePatchContext.regularLithoCardBindMethod by gettingFirstMethodDeclaratively {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    returnType("V")
    parameterTypes("L", "I")
    instructions(
        allOf(
            Opcode.INVOKE_VIRTUAL(),
            method {
                parameterTypes.isEmpty() && returnType == LITHO_COMPONENT_TREE_DESCRIPTOR
            },
        ),
        Opcode.MOVE_RESULT_OBJECT(),
        allOf(
            Opcode.INVOKE_VIRTUAL(),
            method {
                parameterTypes == listOf(LITHO_COMPONENT_TREE_DESCRIPTOR) && returnType == "V"
            },
        ),
    )
}

/** Resolves YouTube's already-loaded active identity after process restoration. */
internal val BytecodePatchContext.resolvedAccountIdentityMethod by gettingFirstMethodDeclaratively {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    parameterTypes()
    returnType("Lajth;")
    strings("DefaultIdentityResolver could not resolve ")
}

/**
 * The active identity persistence transition. The signature and preference literals intentionally
 * identify the method structurally; its obfuscated class and method names are not stable.
 */
internal val BytecodePatchContext.accountIdentityTransitionMethod by gettingFirstMethodDeclaratively {
    accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
    parameterTypes(ACCOUNT_IDENTITY_DESCRIPTOR, "Z")
    returnType("Lcom/google/common/util/concurrent/ListenableFuture;")
    strings(
        "identity_version",
        "user_signed_out",
        "datasync_id",
        "user_identity_id",
        "user_account",
        "IS_INCOGNITO_SESSION_IDENTITY",
        "incognito_visitor_id",
    )
}

package app.revanced.patches.d4nz.youtube.subscriptionmanager

import app.revanced.patcher.accessFlags
import app.revanced.patcher.gettingFirstMethodDeclaratively
import app.revanced.patcher.parameterTypes
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.returnType
import app.revanced.patcher.strings
import com.android.tools.smali.dexlib2.AccessFlags

internal const val ACCOUNT_IDENTITY_DESCRIPTOR =
    "Lcom/google/android/libraries/youtube/account/identity/AccountIdentity;"

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

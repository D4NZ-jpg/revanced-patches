package app.revanced.patches.d4nz.youtube.subscriptionmanager

import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.extensions.instructions
import app.revanced.patcher.firstClassDef
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.all.misc.resources.addResources
import app.revanced.patches.all.misc.resources.addResourcesPatch
import app.revanced.patches.shared.misc.litho.filter.addLithoFilter
import app.revanced.patches.shared.misc.settings.preference.InputType
import app.revanced.patches.shared.misc.settings.preference.NonInteractivePreference
import app.revanced.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.revanced.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.revanced.patches.shared.misc.settings.preference.SwitchPreference
import app.revanced.patches.shared.misc.settings.preference.TextPreference
import app.revanced.patches.youtube.misc.litho.filter.lithoFilterPatch
import app.revanced.patches.youtube.misc.navigation.navigationBarHookPatch
import app.revanced.patches.youtube.misc.settings.PreferenceScreen
import app.revanced.patches.youtube.misc.settings.settingsPatch
import app.revanced.patches.youtube.video.information.videoInformationPatch
import app.revanced.patches.youtube.video.information.videoTimeHook
import app.revanced.util.p0Register
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private data class LithoCardBindHookMatch(
    val method: MutableMethod,
    val insertIndex: Int,
    val componentRegister: Int,
    val rootRegister: Int,
)

private fun Int.isOrdinaryInvokeRegister() = this in 0..15

private fun MutableMethod.findRegularLithoCardBindHookMatches() =
    instructions.indices.mapNotNull { index ->
        if (index + 2 >= instructions.size) return@mapNotNull null
        val producer = instructions[index]
        val moveResult = instructions[index + 1]
        val consumer = instructions[index + 2]
        val producerMethod = (producer as? ReferenceInstruction)?.reference as? MethodReference
            ?: return@mapNotNull null
        val producerRegisters = producer as? FiveRegisterInstruction ?: return@mapNotNull null
        val resultRegister = moveResult as? OneRegisterInstruction ?: return@mapNotNull null
        val consumerMethod = (consumer as? ReferenceInstruction)?.reference as? MethodReference
            ?: return@mapNotNull null
        val consumerRegisters = consumer as? FiveRegisterInstruction ?: return@mapNotNull null
        val componentRegister = producerRegisters.registerC
        val componentTreeRegister = resultRegister.registerA
        val rootRegister = consumerRegisters.registerC

        if (producer.opcode != Opcode.INVOKE_VIRTUAL || producerRegisters.registerCount != 1 ||
            producerMethod.parameterTypes.isNotEmpty() ||
            producerMethod.returnType != LITHO_COMPONENT_TREE_DESCRIPTOR ||
            !componentRegister.isOrdinaryInvokeRegister() ||
            moveResult.opcode != Opcode.MOVE_RESULT_OBJECT ||
            !componentTreeRegister.isOrdinaryInvokeRegister() ||
            consumer.opcode != Opcode.INVOKE_VIRTUAL || consumerRegisters.registerCount != 2 ||
            consumerMethod.parameterTypes.size != 1 ||
            consumerMethod.parameterTypes.single().toString() != LITHO_COMPONENT_TREE_DESCRIPTOR ||
            consumerMethod.returnType != "V" ||
            consumerRegisters.registerD != componentTreeRegister ||
            !rootRegister.isOrdinaryInvokeRegister()
        ) return@mapNotNull null

        LithoCardBindHookMatch(this, index + 3, componentRegister, rootRegister)
    }

private fun addSubscriptionManagerResources() {
    mapOf(
        "revanced_d4nz_subscription_manager_screen_title" to "Subscription manager",
        "revanced_d4nz_subscription_manager_screen_summary" to
            "Experimental Subscriptions feed settings",
        "revanced_d4nz_subscription_manager_about_title" to "About subscription manager",
        "revanced_d4nz_subscription_manager_about_summary" to
            """Automatically hides supported regular videos from your Subscriptions feed after you watch the selected percentage. Refresh the feed after watching a video.

Experimental left swipe can hide supported Subscription entries locally. Channel hiding is not available.""",
        "revanced_d4nz_subscription_manager_title" to "Enable subscription manager",
        "revanced_d4nz_subscription_manager_summary_on" to
            "Enabled for the Subscriptions feed",
        "revanced_d4nz_subscription_manager_summary_off" to "Disabled",
        "revanced_d4nz_subscription_manager_swipe_to_hide_title" to
            "Experimental: Swipe to hide",
        "revanced_d4nz_subscription_manager_swipe_to_hide_summary_on" to
            "Left swipe hides supported Subscription entries locally",
        "revanced_d4nz_subscription_manager_swipe_to_hide_summary_off" to
            "Left swipe hiding is off",
        "revanced_d4nz_subscription_manager_hide_watched_title" to "Hide watched videos",
        "revanced_d4nz_subscription_manager_hide_watched_summary_on" to
            "Hide watched videos detected in the Subscriptions feed",
        "revanced_d4nz_subscription_manager_hide_watched_summary_off" to
            "Watched videos are shown",
        "revanced_d4nz_subscription_manager_watched_threshold_title" to "Watched threshold",
        "revanced_d4nz_subscription_manager_watched_threshold_summary" to
            "Minimum watched progress percentage. Default is 80.",
        "revanced_d4nz_subscription_manager_debug_title" to "Debug logging",
        "revanced_d4nz_subscription_manager_debug_summary_on" to
            "Debug logging is enabled",
        "revanced_d4nz_subscription_manager_debug_summary_off" to
            "Debug logging is disabled",
    ).forEach { (name, value) -> addResources(name, value, formatted = true) }
}

val subscriptionManagerPatch = bytecodePatch(
    name = "Subscription manager",
    description = "Adds experimental settings to manage watched videos in the Subscriptions feed.",
    use = false,
) {
    dependsOn(
        addResourcesPatch,
        settingsPatch,
        lithoFilterPatch,
        navigationBarHookPatch,
        videoInformationPatch,
    )

    compatibleWith(
        "com.google.android.youtube"("20.40.45"),
    )

    extendWith("extensions/subscriptionmanager.rve")

    apply {
        val transitionMethod = accountIdentityTransitionMethod
        val transitionCommitMatches = transitionMethod.instructions.indices.filter { index ->
            if (index + 2 >= transitionMethod.instructions.size) return@filter false
            val call = transitionMethod.instructions[index]
            val result = transitionMethod.instructions[index + 1]
            val branch = transitionMethod.instructions[index + 2]
            val method = (call as? ReferenceInstruction)?.reference as? MethodReference
                ?: return@filter false
            val callRegisters = call as? FiveRegisterInstruction ?: return@filter false
            val resultRegister = result as? OneRegisterInstruction ?: return@filter false
            val branchRegister = branch as? OneRegisterInstruction ?: return@filter false
            val branchOffset = branch as? OffsetInstruction ?: return@filter false
            val identityRegister = transitionMethod.p0Register + 1
            val identityAddedToUpdate = (maxOf(0, index - 12) until index).any { priorIndex ->
                val prior = transitionMethod.instructions[priorIndex]
                val priorMethod = (prior as? ReferenceInstruction)?.reference as? MethodReference
                    ?: return@any false
                val priorRegisters = prior as? FiveRegisterInstruction ?: return@any false
                prior.opcode == Opcode.INVOKE_INTERFACE &&
                    priorMethod.toString() == "Ljava/util/Set;->add(Ljava/lang/Object;)Z" &&
                    priorRegisters.registerD == identityRegister
            }

            call.opcode == Opcode.INVOKE_VIRTUAL &&
                callRegisters.registerC == transitionMethod.p0Register &&
                method.definingClass == transitionMethod.definingClass &&
                method.returnType == "Z" &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes.all { it.startsWith("L") } &&
                result.opcode == Opcode.MOVE_RESULT &&
                branch.opcode == Opcode.IF_EQZ &&
                branchOffset.codeOffset < 0 &&
                resultRegister.registerA == branchRegister.registerA &&
                identityAddedToUpdate
        }
        if (transitionCommitMatches.size != 1) {
            throw PatchException(
                "Could not uniquely identify the committed AccountIdentity transition " +
                    "(found ${transitionCommitMatches.size} structural matches)",
            )
        }
        val transitionCommitIndex = transitionCommitMatches.single()
        val identityRegister = transitionMethod.p0Register + 1
        val transitionDispatchMatches =
            (transitionCommitIndex + 3 until transitionMethod.instructions.size).filter { index ->
                val branch = transitionMethod.instructions[index]
                val branchRegister = branch as? OneRegisterInstruction ?: return@filter false
                val dispatchFollows =
                    (index + 1 until minOf(index + 12, transitionMethod.instructions.size)).any {
                        val instruction = transitionMethod.instructions[it]
                        val reference =
                            (instruction as? ReferenceInstruction)?.reference as? MethodReference
                                ?: return@any false
                        instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                            reference.returnType ==
                            "Lcom/google/common/util/concurrent/ListenableFuture;" &&
                            reference.parameterTypes.size == 1
                    }
                branch.opcode == Opcode.IF_NEZ &&
                    branchRegister.registerA == identityRegister && dispatchFollows
            }
        if (transitionDispatchMatches.size != 1) {
            throw PatchException(
                "Could not uniquely identify the committed AccountIdentity dispatch " +
                    "(found ${transitionDispatchMatches.size} structural matches)",
            )
        }
        transitionMethod.addInstruction(
            transitionDispatchMatches.single(),
            "invoke-static {p1}, " +
                "Lapp/revanced/extension/d4nz/youtube/subscriptionmanager/SubscriptionManagerAccountHook;" +
                "->setAccount(Lcom/google/android/libraries/youtube/account/identity/AccountIdentity;)V",
        )

        val startupCandidates = firstClassDef(transitionMethod.definingClass).methods.filter { method ->
            val identityFactoryCount = method.instructions.count { instruction ->
                val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                instruction.opcode == Opcode.INVOKE_STATIC &&
                    reference?.definingClass == ACCOUNT_IDENTITY_DESCRIPTOR &&
                    reference.returnType == ACCOUNT_IDENTITY_DESCRIPTOR
            }
            val atomicStateWriteCount = method.instructions.count { instruction ->
                val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                    reference?.toString() ==
                    "Ljava/util/concurrent/atomic/AtomicReference;->set(Ljava/lang/Object;)V"
            }
            AccessFlags.PUBLIC.isSet(method.accessFlags) &&
                AccessFlags.FINAL.isSet(method.accessFlags) &&
                method.parameterTypes.isEmpty() && method.returnType == "V" &&
                identityFactoryCount >= 3 && atomicStateWriteCount == 1
        }
        val startupMatches = startupCandidates.mapNotNull { method ->
            val matches = method.instructions.indices.filter { index ->
                if (index + 2 >= method.instructions.size) return@filter false
                val identityWrite = method.instructions[index]
                val clearIdentity = method.instructions[index + 1]
                val builderWrite = method.instructions[index + 2]
                if (identityWrite.opcode != Opcode.IPUT_OBJECT ||
                    clearIdentity.opcode != Opcode.CONST_4 ||
                    builderWrite.opcode != Opcode.IPUT_OBJECT
                ) return@filter false

                val identityRegisters = identityWrite as? TwoRegisterInstruction ?: return@filter false
                val clearRegister = clearIdentity as? OneRegisterInstruction ?: return@filter false
                val clearLiteral = clearIdentity as? NarrowLiteralInstruction ?: return@filter false
                val builderRegisters = builderWrite as? TwoRegisterInstruction ?: return@filter false
                val identityField = (identityWrite as? ReferenceInstruction)?.reference as? FieldReference
                    ?: return@filter false
                val builderField = (builderWrite as? ReferenceInstruction)?.reference as? FieldReference
                    ?: return@filter false

                clearLiteral.narrowLiteral == 0 &&
                    clearRegister.registerA == identityRegisters.registerA &&
                    builderRegisters.registerA == identityRegisters.registerA &&
                    builderRegisters.registerB == identityRegisters.registerB &&
                    identityField.definingClass == builderField.definingClass &&
                    identityField.type == "Ljava/lang/Object;" &&
                    builderField.type == "Ljava/lang/Object;" &&
                    identityField.name != builderField.name
            }
            if (matches.size == 1) Triple(method, matches.single() + 1,
                (method.instructions[matches.single()] as TwoRegisterInstruction).registerA) else null
        }
        if (startupMatches.size != 1) {
            throw PatchException(
                "Could not uniquely prove the active AccountIdentity register at startup " +
                    "(found ${startupMatches.size} structural matches)",
            )
        }
        val (startupMethod, startupInsertIndex, startupIdentityRegister) = startupMatches.single()
        startupMethod.addInstruction(
            startupInsertIndex,
            "invoke-static {v$startupIdentityRegister}, " +
                "Lapp/revanced/extension/d4nz/youtube/subscriptionmanager/SubscriptionManagerAccountHook;" +
                "->setAccountFromStartup(Ljava/lang/Object;)V",
        )

        val resolvedIdentityMethod = resolvedAccountIdentityMethod
        val resolvedIdentityReturns = resolvedIdentityMethod.instructions.indices.filter { index ->
            resolvedIdentityMethod.instructions[index].opcode == Opcode.RETURN_OBJECT &&
                (resolvedIdentityMethod.instructions[index] as? OneRegisterInstruction)
                    ?.registerA?.isOrdinaryInvokeRegister() == true
        }
        if (resolvedIdentityReturns.size != 2) {
            throw PatchException(
                "Could not prove the resolved AccountIdentity returns " +
                    "(found ${resolvedIdentityReturns.size} structural matches)",
            )
        }
        resolvedIdentityReturns.asReversed().forEach { returnIndex ->
            val returnRegister =
                (resolvedIdentityMethod.instructions[returnIndex] as OneRegisterInstruction).registerA
            resolvedIdentityMethod.addInstruction(
                returnIndex,
                "invoke-static {v$returnRegister}, " +
                    "Lapp/revanced/extension/d4nz/youtube/subscriptionmanager/SubscriptionManagerAccountHook;" +
                    "->hydrateAccountFromActiveIdentity(Ljava/lang/Object;)V",
            )
        }

        addSubscriptionManagerResources()

        addLithoFilter(
            "Lapp/revanced/extension/d4nz/youtube/patches/litho/SubscriptionManagerFilter;",
        )

        val bindMethod = regularLithoCardBindMethod
        val bindMatches = bindMethod.findRegularLithoCardBindHookMatches()
        if (bindMatches.size != 1) {
            throw PatchException(
                "Could not uniquely identify the regular Litho card bind seam " +
                    "(found ${bindMatches.size} validated producer/result/consumer chains)",
            )
        }
        val bindMatch = bindMatches.single()
        val bindHook = SUBSCRIPTION_MANAGER_SWIPE_HANDLER_DESCRIPTOR +
            "->onLithoComponentBound(Ljava/lang/Object;Ljava/lang/Object;)V"
        bindMethod.addInstruction(
            bindMatch.insertIndex,
            "invoke-static {v${bindMatch.componentRegister}, v${bindMatch.rootRegister}}, $bindHook",
        )
        val injectedBindHooks = bindMethod.instructions.count { instruction ->
            val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            reference?.toString() == bindHook
        }
        if (injectedBindHooks != 1) {
            throw PatchException(
                "Could not verify the injected subscription card bind hook " +
                    "(found $injectedBindHooks calls)",
            )
        }

        videoTimeHook(
            "Lapp/revanced/extension/d4nz/youtube/subscriptionmanager/SubscriptionManagerPlayback;",
            "setVideoTime",
        )

        PreferenceScreen.FEED.addPreferences(
            PreferenceScreenPreference(
                key = "revanced_d4nz_subscription_manager_screen",
                sorting = Sorting.UNSORTED,
                preferences = setOf(
                    NonInteractivePreference("revanced_d4nz_subscription_manager_about"),
                    SwitchPreference("revanced_d4nz_subscription_manager"),
                    SwitchPreference("revanced_d4nz_subscription_manager_swipe_to_hide"),
                    SwitchPreference("revanced_d4nz_subscription_manager_hide_watched"),
                    TextPreference(
                        "revanced_d4nz_subscription_manager_watched_threshold",
                        inputType = InputType.NUMBER,
                    ),
                    SwitchPreference("revanced_d4nz_subscription_manager_debug"),
                ),
            ),
        )
    }
}

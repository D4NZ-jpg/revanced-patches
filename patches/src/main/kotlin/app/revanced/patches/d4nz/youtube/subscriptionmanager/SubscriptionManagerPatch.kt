package app.revanced.patches.d4nz.youtube.subscriptionmanager

import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.extensions.addInstructionsWithLabels
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

private data class RuntimeMethodContract(
    val classDescriptor: String,
    val methodName: String,
    val parameterTypes: List<String>,
    val returnType: String,
    val accessFlags: Int,
)

private fun BytecodePatchContext.requireRuntimeMethod(
    contract: RuntimeMethodContract,
): MutableMethod {
    val matches = firstClassDef(contract.classDescriptor).methods.filter { method ->
        method.name == contract.methodName &&
            method.parameterTypes.map { it.toString() } == contract.parameterTypes &&
            method.returnType == contract.returnType
    }
    if (matches.size != 1 || matches.single().accessFlags != contract.accessFlags) {
        throw PatchException(
            "Native Hide ABI method drifted: ${contract.classDescriptor}->" +
                "${contract.methodName} (found ${matches.size})",
        )
    }
    return matches.single()
}

private fun BytecodePatchContext.requireRuntimeField(
    classDescriptor: String,
    fieldName: String,
    fieldType: String,
    accessFlags: Int,
) {
    val matches = firstClassDef(classDescriptor).fields.filter { field ->
        field.name == fieldName && field.type == fieldType
    }
    if (matches.size != 1 || matches.single().accessFlags != accessFlags) {
        throw PatchException(
            "Native Hide ABI field drifted: $classDescriptor->$fieldName (found ${matches.size})",
        )
    }
}

private fun BytecodePatchContext.requireRuntimeExtension(
    classDescriptor: String,
    fieldName: String,
    extensionNumber: Int,
) {
    val publicStaticFinal = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or
        AccessFlags.FINAL.value
    requireRuntimeField(classDescriptor, fieldName, "Latek;", publicStaticFinal)
    val initializers = firstClassDef(classDescriptor).methods.filter { method ->
        method.name == "<clinit>" && method.parameterTypes.isEmpty() && method.returnType == "V"
    }
    val numberMatches = initializers.singleOrNull()?.instructions?.count { instruction ->
        instruction.opcode == Opcode.CONST &&
            (instruction as? NarrowLiteralInstruction)?.narrowLiteral == extensionNumber
    } ?: 0
    if (initializers.size != 1 || numberMatches != 1) {
        throw PatchException(
            "Native Hide extension drifted: $classDescriptor->$fieldName/$extensionNumber",
        )
    }
}

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

Experimental left swipe persistently hides supported Subscription entries and uses the native Hide action when its exact verified route is available. Channel hiding is not available.""",
        "revanced_d4nz_subscription_manager_title" to "Enable subscription manager",
        "revanced_d4nz_subscription_manager_summary_on" to
            "Enabled for the Subscriptions feed",
        "revanced_d4nz_subscription_manager_summary_off" to "Disabled",
        "revanced_d4nz_subscription_manager_swipe_to_hide_title" to
            "Experimental: Swipe to hide",
        "revanced_d4nz_subscription_manager_swipe_to_hide_summary_on" to
            "Deliberate left swipe persistently hides supported Subscription entries",
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

        val public = AccessFlags.PUBLIC.value
        val privateFinal = AccessFlags.PRIVATE.value or AccessFlags.FINAL.value
        val publicFinal = public or AccessFlags.FINAL.value
        val publicStatic = public or AccessFlags.STATIC.value
        val publicAbstract = public or AccessFlags.ABSTRACT.value
        val publicConstructor = public or AccessFlags.CONSTRUCTOR.value
        val finalSynthetic = AccessFlags.FINAL.value or AccessFlags.SYNTHETIC.value

        requireRuntimeExtension("Laxtl;", "a", 169495254)
        requireRuntimeExtension("Lbafw;", "b", 98150882)
        requireRuntimeExtension("Lawvp;", "a", 65153809)
        requireRuntimeField("Ljhv;", "c", "Laewu;", privateFinal)
        requireRuntimeField("Lanqc;", "b", "Laewu;", privateFinal)
        requireRuntimeField("Lanqb;", "b", "Lbagi;", publicFinal)
        requireRuntimeField("Lanqb;", "c", "Larbo;", publicFinal)
        requireRuntimeField("Lanqb;", "f", "Lavfw;", publicFinal)
        requireRuntimeField("Lgqg;", "a", "Ljava/lang/Object;", publicFinal)
        requireRuntimeField("Lgdh;", "a", "Lgev;", publicFinal)
        requireRuntimeField("Lgev;", "w", "Lgcq;", public)
        requireRuntimeField("Lgcq;", "b", "Lgcy;", public)
        requireRuntimeField("Lgcq;", "c", "I", publicFinal)
        requireRuntimeField("Lsky;", "E", "Ljava/util/List;", 0)
        requireRuntimeField("Lsov;", "f", "Lrks;", finalSynthetic)
        requireRuntimeField("Lbagi;", "c", "Latfd;", public)
        val arboClass = firstClassDef("Larbo;")
        if (arboClass.superclass != "Ljava/lang/Object;" ||
            arboClass.interfaces.none { it.toString() == "Ljava/util/Map;" }
        ) {
            throw PatchException("Native Hide endpoint-map ABI drifted")
        }
        requireRuntimeField("Lbagf;", "b", "I", public)
        requireRuntimeField("Lbagf;", "d", "Lbagk;", public)
        requireRuntimeField("Lbagk;", "b", "I", public)
        requireRuntimeField("Lbagk;", "e", "Lavfw;", public)

        requireRuntimeMethod(
            RuntimeMethodContract(
                "Lcom/facebook/litho/ComponentHost;", "a", emptyList(), "I", publicFinal,
            ),
        )
        requireRuntimeMethod(
            RuntimeMethodContract(
                "Lcom/facebook/litho/ComponentHost;", "b", listOf("I"), "Lgqg;", publicFinal,
            ),
        )
        requireRuntimeMethod(
            RuntimeMethodContract("Lgdh;", "a", listOf("Lgqg;"), "Lgdh;", publicStatic),
        )
        requireRuntimeMethod(
            RuntimeMethodContract(
                "Lsov;", "a",
                listOf("Landroid/view/View;", "Ltuy;", "Landroid/view/MotionEvent;"),
                "V", publicFinal,
            ),
        )
        requireRuntimeMethod(
            RuntimeMethodContract(
                "Lrks;", "j", emptyList(),
                "Lcom/google/protos/youtube/elements/CommandOuterClass\$Command;", publicFinal,
            ),
        )
        requireRuntimeMethod(
            RuntimeMethodContract(
                "Lateh;", "b", listOf("Latdw;"), "Ljava/lang/Object;", publicFinal,
            ),
        )
        requireRuntimeMethod(
            RuntimeMethodContract("Lasmq;", "J", listOf("Lateh;"), "I", publicStatic),
        )
        requireRuntimeMethod(
            RuntimeMethodContract(
                "Laeww;", "c", listOf("Lavfw;"), "Ljava/lang/Object;", publicStatic,
            ),
        )
        requireRuntimeMethod(
            RuntimeMethodContract("Laiml;", "fq", listOf("Lbagf;"), "Lavfw;", publicStatic),
        )
        requireRuntimeMethod(
            RuntimeMethodContract(
                "Laewu;", "c", listOf("Lavfw;", "Ljava/util/Map;"), "V", publicAbstract,
            ),
        )
        requireRuntimeMethod(
            RuntimeMethodContract("Ltuy;", "<init>", listOf("F", "F"), "V", publicConstructor),
        )
        val menuHandlerMethod = requireRuntimeMethod(
            RuntimeMethodContract(
                "Ljhv;", "b", listOf("Lavfw;", "Ljava/util/Map;"), "V", publicFinal,
            ),
        )
        val menuHandlerHook =
            "Lapp/revanced/extension/d4nz/youtube/subscriptionmanager/" +
                "SubscriptionManagerNativeHide;->onMenuCommandResolved(" +
                "Ljava/lang/Object;Ljava/lang/Object;Ljava/util/Map;)V"
        menuHandlerMethod.addInstruction(
            0,
            "invoke-static/range {p0 .. p2}, $menuHandlerHook",
        )
        if (menuHandlerMethod.instructions.count { instruction ->
                val reference =
                    (instruction as? ReferenceInstruction)?.reference as? MethodReference
                reference?.toString() == menuHandlerHook
            } != 1
        ) {
            throw PatchException("Could not verify the injected native Hide command hook")
        }

        val menuCoordinatorMethod = requireRuntimeMethod(
            RuntimeMethodContract("Lanqc;", "a", listOf("Lanqb;"), "V", publicFinal),
        )
        val menuScratchMatches = menuCoordinatorMethod.instructions.indices.filter { index ->
            if (index + 1 >= menuCoordinatorMethod.instructions.size) return@filter false
            val call = menuCoordinatorMethod.instructions[index]
            val reference = (call as? ReferenceInstruction)?.reference as? MethodReference
            val result = menuCoordinatorMethod.instructions[index + 1]
            call.opcode == Opcode.INVOKE_INTERFACE &&
                reference?.toString() == "Lanuw;->c()Z" &&
                result.opcode == Opcode.MOVE_RESULT &&
                result is OneRegisterInstruction
        }
        if (menuScratchMatches.size != 1) {
            throw PatchException(
                "Could not prove the native Hide menu scratch register " +
                    "(found ${menuScratchMatches.size})",
            )
        }
        val menuScratchRegister =
            (menuCoordinatorMethod.instructions[menuScratchMatches.single() + 1]
                as OneRegisterInstruction).registerA
        val nativeHideHook =
            "Lapp/revanced/extension/d4nz/youtube/subscriptionmanager/" +
                "SubscriptionManagerNativeHide;->onMenuResolved(" +
                "Ljava/lang/Object;Ljava/lang/Object;)Z"
        menuCoordinatorMethod.addInstructionsWithLabels(
            0,
            """
                invoke-static/range {p0 .. p1}, $nativeHideHook
                move-result v$menuScratchRegister
                if-eqz v$menuScratchRegister, :continue_native_menu
                return-void
            """,
            ExternalLabel("continue_native_menu", menuCoordinatorMethod.instructions.first()),
        )
        val nativeHideHooks = menuCoordinatorMethod.instructions.count { instruction ->
            val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            reference?.toString() == nativeHideHook
        }
        if (nativeHideHooks != 1) {
            throw PatchException(
                "Could not verify the injected native Hide menu hook " +
                    "(found $nativeHideHooks calls)",
            )
        }

        val adapterNotifyItemChangedMethods = firstClassDef("Lmx;").methods.filter { method ->
            method.name == "hf" &&
                method.parameterTypes.map { it.toString() } == listOf("I") &&
                method.returnType == "V" &&
                !AccessFlags.STATIC.isSet(method.accessFlags) &&
                AccessFlags.PUBLIC.isSet(method.accessFlags) &&
                AccessFlags.FINAL.isSet(method.accessFlags)
        }
        if (adapterNotifyItemChangedMethods.size != 1) {
            throw PatchException(
                "Could not verify the RecyclerView item-rebind ABI " +
                    "(found ${adapterNotifyItemChangedMethods.size} exact matches)",
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

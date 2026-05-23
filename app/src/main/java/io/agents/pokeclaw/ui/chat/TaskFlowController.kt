// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.chat

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshots.SnapshotStateList
import io.agents.pokeclaw.AppCapabilityCoordinator
import io.agents.pokeclaw.AppViewModel
import io.agents.pokeclaw.R
import io.agents.pokeclaw.ServiceBindingState
import io.agents.pokeclaw.TaskEvent
import io.agents.pokeclaw.agent.DirectDeviceDataGuard
import io.agents.pokeclaw.agent.PipelineRouter
import io.agents.pokeclaw.agent.TaskPromptEnvelope
import io.agents.pokeclaw.agent.llm.ModelConfigRepository
import io.agents.pokeclaw.service.ClawAccessibilityService
import io.agents.pokeclaw.service.ForegroundService
import io.agents.pokeclaw.service.AutoReplyManager
import io.agents.pokeclaw.tool.ToolRegistry
import io.agents.pokeclaw.ui.settings.SettingsActivity
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog
import java.util.concurrent.ExecutorService

data class TaskFlowUiState(
    val messages: SnapshotStateList<ChatMessage>,
    val modelStatus: MutableState<String>,
    val isAwaitingReply: MutableState<Boolean>,
    val isTaskRunning: MutableState<Boolean>,
)

/**
 * Owns task-mode send flow, typed TaskEvent rendering, and monitor start wiring.
 *
 * ComposeChatActivity keeps the shell; this controller keeps task-specific behavior.
 */
class TaskFlowController(
    private val activity: ComponentActivity,
    private val executor: ExecutorService,
    private val appViewModel: AppViewModel,
    private val chatSessionController: ChatSessionController,
    private val currentConversationId: () -> String,
    private val uiState: TaskFlowUiState,
    private val onPersistConversation: () -> Unit,
    private val onTaskSettled: (() -> Unit)? = null,
    private val onTaskTerminal: ((TaskEvent) -> Unit)? = null,
) {

    companion object {
        private const val TAG = "TaskFlowController"
    }

    private var sendTaskRetryCount = 0
    private var lastMonitorStatusNote: String? = null
    private val pipelineRouter = PipelineRouter(activity)

    fun sendTask(text: String) {
        if (appViewModel.isTaskRunning()) {
            val message = activity.getString(R.string.task_another_running)
            addSystem(message)
            onTaskTerminal?.invoke(TaskEvent.Failed(message))
            return
        }

        if (ModelConfigRepository.snapshot().isLocalActive() && isLikelyMonitorRequest(text)) {
            addUser(text)
            val message = activity.getString(R.string.task_local_monitor_guidance)
            addSystem(message)
            onTaskTerminal?.invoke(TaskEvent.Failed(message))
            return
        }

        DirectDeviceDataGuard.deterministicToolCall(text)?.let { directTool ->
            XLog.i(TAG, "sendTask: executing deterministic direct tool before LLM/accessibility gates")
            executeDirectToolTask(text, directTool)
            return
        }

        when (AppCapabilityCoordinator.accessibilityState(activity)) {
            ServiceBindingState.DISABLED -> {
                val directTool = DirectDeviceDataGuard.deterministicToolCall(text)
                if (directTool != null) {
                    XLog.i(TAG, "sendTask: executing non-interactive direct tool without Accessibility")
                    executeDirectToolTask(text, directTool)
                    return
                }
                if (canRunWithoutAccessibility(text)) {
                    XLog.i(TAG, "sendTask: allowing non-interactive task without Accessibility")
                } else {
                Toast.makeText(activity, R.string.task_enable_accessibility_to_run, Toast.LENGTH_LONG).show()
                addSystem(activity.getString(R.string.task_accessibility_needed_opening_settings))
                openSettings()
                sendTaskRetryCount = 0
                onTaskTerminal?.invoke(TaskEvent.Failed(activity.getString(R.string.task_accessibility_required)))
                return
                }
            }
            ServiceBindingState.CONNECTING -> {
                val directTool = DirectDeviceDataGuard.deterministicToolCall(text)
                if (directTool != null) {
                    XLog.i(TAG, "sendTask: executing non-interactive direct tool while Accessibility connects")
                    executeDirectToolTask(text, directTool)
                    return
                }
                if (canRunWithoutAccessibility(text)) {
                    XLog.i(TAG, "sendTask: allowing non-interactive task while Accessibility connects")
                } else {
                if (sendTaskRetryCount >= 1) {
                    Toast.makeText(activity, R.string.task_accessibility_not_connected, Toast.LENGTH_LONG).show()
                    addSystem(activity.getString(R.string.task_accessibility_toggle_settings))
                    openSettings()
                    sendTaskRetryCount = 0
                    onTaskTerminal?.invoke(TaskEvent.Failed(activity.getString(R.string.task_accessibility_not_connected)))
                    return
                }
                sendTaskRetryCount++
                addSystem(activity.getString(R.string.task_accessibility_connecting))
                executor.submit {
                    val connected = ClawAccessibilityService.awaitRunning(5000)
                    activity.runOnUiThread {
                        if (connected) {
                            sendTask(text)
                        } else {
                            Toast.makeText(activity, R.string.task_accessibility_not_connected, Toast.LENGTH_LONG).show()
                            addSystem(activity.getString(R.string.task_accessibility_go_toggle))
                            sendTaskRetryCount = 0
                            onTaskTerminal?.invoke(TaskEvent.Failed(activity.getString(R.string.task_accessibility_not_connected)))
                        }
                    }
                }
                return
                }
            }
            ServiceBindingState.DEGRADED -> {
                val directTool = DirectDeviceDataGuard.deterministicToolCall(text)
                if (directTool != null) {
                    XLog.i(TAG, "sendTask: executing non-interactive direct tool while Accessibility is degraded")
                    executeDirectToolTask(text, directTool)
                    return
                }
                if (canRunWithoutAccessibility(text)) {
                    XLog.i(TAG, "sendTask: allowing non-interactive task while Accessibility is degraded")
                } else {
                    Toast.makeText(activity, R.string.task_accessibility_disconnected, Toast.LENGTH_LONG).show()
                    addSystem(activity.getString(R.string.task_accessibility_disconnected_toggle))
                    openSettings()
                    sendTaskRetryCount = 0
                    onTaskTerminal?.invoke(TaskEvent.Failed(activity.getString(R.string.task_accessibility_disconnected)))
                    return
                }
            }
            ServiceBindingState.READY -> Unit
        }
        sendTaskRetryCount = 0

        ensureNotificationPermission()
        uiState.isAwaitingReply.value = false
        uiState.isTaskRunning.value = false

        if (!KVUtils.hasLlmConfig()) {
            Toast.makeText(activity, R.string.task_configure_llm_first, Toast.LENGTH_LONG).show()
            onTaskTerminal?.invoke(TaskEvent.Failed(activity.getString(R.string.task_configure_llm_first)))
            return
        }

        val agentPromptOverride = buildAgentPromptOverride(text)
        addUser(text)
        uiState.isAwaitingReply.value = true
        uiState.isTaskRunning.value = false
        XLog.i(TAG, "sendTask: isProcessing=TRUE")
        uiState.messages.add(ChatMessage(ChatMessage.Role.ASSISTANT, "..."))

        val taskId = "task_${System.currentTimeMillis()}"

        executor.submit {
            chatSessionController.prepareForTaskStart()

            activity.runOnUiThread {
                try {
                    appViewModel.startTask(text, taskId, agentPromptOverride = agentPromptOverride) { event ->
                        activity.runOnUiThread { handleTaskEvent(event) }
                    }
                } catch (e: Exception) {
                    XLog.e(TAG, "sendTask failed: ${e.message}", e)
                    addSystem(activity.getString(R.string.task_error_prefix, e.message ?: ""))
                    cleanupAfterTask()
                }
            }
        }
    }

    private fun executeDirectToolTask(text: String, toolCall: DirectDeviceDataGuard.DeterministicToolCall) {
        ensureNotificationPermission()
        addUser(text)
        uiState.isAwaitingReply.value = true
        uiState.isTaskRunning.value = false
        uiState.messages.add(ChatMessage(ChatMessage.Role.ASSISTANT, "..."))

        executor.submit {
            try {
                XLog.i(TAG, "executeDirectToolTask: tool=${toolCall.toolName}, params=${toolCall.params}")
                val result = ToolRegistry.getInstance().executeTool(toolCall.toolName, toolCall.params)
                activity.runOnUiThread {
                    val answer = result.data ?: result.error ?: activity.getString(R.string.task_done)
                    if (result.isSuccess) {
                        XLog.i(TAG, "executeDirectToolTask: success tool=${toolCall.toolName}")
                    } else {
                        XLog.w(TAG, "executeDirectToolTask: failed tool=${toolCall.toolName}, error=${result.error}")
                    }
                    replaceTypingIndicator(answer)
                    onTaskTerminal?.invoke(TaskEvent.Completed(answer))
                    cleanupAfterTask()
                }
            } catch (e: Exception) {
                XLog.e(TAG, "executeDirectToolTask failed: ${e.message}", e)
                activity.runOnUiThread {
                    replaceTypingIndicator(activity.getString(R.string.task_error_prefix, e.message ?: ""))
                    onTaskTerminal?.invoke(TaskEvent.Failed(e.message ?: activity.getString(R.string.task_direct_tool_failed)))
                    cleanupAfterTask()
                }
            }
        }
    }

    private fun canRunWithoutAccessibility(text: String): Boolean {
        if (DirectDeviceDataGuard.matchesNonInteractiveDeviceDataTask(text)) {
            return true
        }
        return when (pipelineRouter.route(text)) {
            is PipelineRouter.Route.DirectIntent -> true
            is PipelineRouter.Route.DirectTool -> true
            else -> false
        }
    }

    fun handleMonitorTask(text: String) {
        val target = MonitorTargetParser.fromTaskText(text)
        if (target == null) {
            addUser(text)
            addSystem(activity.getString(R.string.task_monitor_parse_failed))
            return
        }

        startMonitor(target, typedInput = text)
    }

    fun startMonitor(target: MonitorTargetSpec, typedInput: String? = null) {
        val trimmedLabel = target.label.trim()
        if (trimmedLabel.isEmpty()) {
            addSystem(activity.getString(R.string.task_monitor_parse_failed))
            return
        }

        typedInput?.let { addUser(it) }
        val missing = AppCapabilityCoordinator.missingMonitorRequirements(activity)
        if (missing.isNotEmpty()) {
            Toast.makeText(
                activity,
                activity.getString(R.string.task_enable_missing_requirements, missing.joinToString(" & ") { it.label }),
                Toast.LENGTH_LONG
            ).show()
            openSettings()
            onTaskTerminal?.invoke(TaskEvent.Failed(activity.getString(R.string.task_missing_monitor_permissions)))
            return
        }

        val contact = trimmedLabel
        val app = target.app
        uiState.isAwaitingReply.value = false
        uiState.isTaskRunning.value = false
        addSystem(activity.getString(R.string.task_setting_up_auto_reply, contact, app))

        val autoReplyManager = AutoReplyManager.getInstance()
        autoReplyManager.addTarget(contact, app)
        autoReplyManager.setEnabled(true)
        XLog.i(TAG, "startMonitor: enabled auto-reply for '${target.displayLabel}'")

        Handler(Looper.getMainLooper()).postDelayed({
            uiState.isAwaitingReply.value = false
            uiState.isTaskRunning.value = false
            addSystem(activity.getString(R.string.task_auto_reply_active, target.displayLabel))
            XLog.i(TAG, "startMonitor: monitor active, staying in PokeClaw")
        }, 1500)
    }

    private fun handleTaskEvent(event: TaskEvent) {
        try {
            when (event) {
                is TaskEvent.Completed -> {
                    replaceTypingIndicator(event.answer, event.modelName)
                    onTaskTerminal?.invoke(event)
                    cleanupAfterTask()
                    checkAutoReplyConfirmation()
                }
                is TaskEvent.Failed -> {
                    replaceTypingIndicator(activity.getString(R.string.task_error_prefix, event.error))
                    onTaskTerminal?.invoke(event)
                    cleanupAfterTask()
                }
                is TaskEvent.Cancelled -> {
                    removeTypingIndicator()
                    onTaskTerminal?.invoke(event)
                    cleanupAfterTask()
                }
                is TaskEvent.Blocked -> {
                    replaceTypingIndicator(activity.getString(R.string.task_blocked_by_system_dialog))
                    onTaskTerminal?.invoke(event)
                    cleanupAfterTask()
                }
                is TaskEvent.ToolAction -> {
                    uiState.isAwaitingReply.value = false
                    uiState.isTaskRunning.value = true
                    if (!event.toolName.contains("Finish", ignoreCase = true)) {
                        removeTypingIndicator()
                        addSystem("${event.toolName}...")
                    }
                }
                is TaskEvent.ToolResult -> {
                    uiState.isAwaitingReply.value = false
                    uiState.isTaskRunning.value = true
                    if (!event.success) addSystem(activity.getString(R.string.task_tool_failed, event.toolName))
                }
                is TaskEvent.Response -> {
                    uiState.isAwaitingReply.value = false
                    replaceTypingIndicator(event.text)
                }
                is TaskEvent.Progress -> {
                    uiState.isAwaitingReply.value = false
                    uiState.isTaskRunning.value = true
                    addSystem(event.description)
                }
                is TaskEvent.LoopStart -> {
                    uiState.isAwaitingReply.value = false
                    uiState.isTaskRunning.value = true
                }
                is TaskEvent.TokenUpdate, is TaskEvent.Thinking -> Unit
            }
        } catch (e: Exception) {
            XLog.w(TAG, "handleTaskEvent error", e)
        }
    }

    private fun replaceTypingIndicator(text: String, actualModelName: String? = null) {
        val modelTag = actualModelName
            ?: uiState.modelStatus.value.removePrefix("● ").split(" ·").firstOrNull()?.trim()
            ?: ""
        val idx = uiState.messages.indexOfLast { it.role == ChatMessage.Role.ASSISTANT && it.content == "..." }
        if (idx >= 0) {
            uiState.messages[idx] = ChatMessage(ChatMessage.Role.ASSISTANT, text, modelName = modelTag)
        } else {
            uiState.messages.add(ChatMessage(ChatMessage.Role.ASSISTANT, text, modelName = modelTag))
        }
        onPersistConversation()
    }

    private fun removeTypingIndicator() {
        val idx = uiState.messages.indexOfLast { it.role == ChatMessage.Role.ASSISTANT && it.content == "..." }
        if (idx >= 0) uiState.messages.removeAt(idx)
    }

    private fun cleanupAfterTask() {
        XLog.i(TAG, "cleanupAfterTask: isProcessing=FALSE")
        uiState.isAwaitingReply.value = false
        uiState.isTaskRunning.value = false
        appViewModel.clearTaskCallback()
        onTaskSettled?.invoke()
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                chatSessionController.loadModelIfReady(
                    conversationId = currentConversationId(),
                    visibleMessages = uiState.messages.toList(),
                )
            } catch (e: Exception) {
                XLog.e(TAG, "cleanupAfterTask: loadModel error", e)
            }
        }, 500)
    }

    private fun checkAutoReplyConfirmation() {
        val autoReplyManager = AutoReplyManager.getInstance()
        if (!autoReplyManager.isEnabled) {
            lastMonitorStatusNote = null
            return
        }
        val contacts = autoReplyManager.monitoredContacts.joinToString(", ")
        if (contacts.isBlank()) {
            lastMonitorStatusNote = null
            return
        }
        val note = activity.getString(R.string.task_auto_reply_status, contacts)
        if (note == lastMonitorStatusNote) return
        addSystem(note)
        lastMonitorStatusNote = note
        XLog.i(TAG, "checkAutoReplyConfirmation: monitor active, staying in PokeClaw")
    }

    private fun ensureNotificationPermission() {
        if (!AppCapabilityCoordinator.isNotificationPermissionGranted(activity)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun addUser(text: String) {
        uiState.messages.add(ChatMessage(ChatMessage.Role.USER, text))
    }

    private fun addSystem(text: String) {
        uiState.messages.add(ChatMessage(ChatMessage.Role.SYSTEM, text))
    }

    private fun openSettings() {
        activity.startActivity(Intent(activity, SettingsActivity::class.java))
    }

    private fun buildAgentPromptOverride(rawTask: String): String? {
        if (ModelConfigRepository.snapshot().isLocalActive()) {
            return null
        }

        val historyLines = CloudContextHandoffFormatter.conversationLines(uiState.messages)
        val backgroundStatus = buildBackgroundStatusContext()

        return TaskPromptEnvelope.build(
            chatHistoryLines = historyLines,
            currentRequest = rawTask,
            backgroundState = backgroundStatus,
        )
    }

    private fun buildBackgroundStatusContext(): String? {
        val autoReplyManager = AutoReplyManager.getInstance()
        if (!autoReplyManager.isEnabled) return null

        val contacts = autoReplyManager.monitoredContacts.toList()
        if (contacts.isEmpty()) return null

        return buildString {
            append("Background monitor active for: ")
            append(contacts.joinToString(", "))
            append('.')
        }
    }

    private fun isLikelyMonitorRequest(text: String): Boolean {
        val lower = text.lowercase()
        val mentionsMonitor = lower.contains("monitor") ||
            lower.contains("监控") ||
            lower.contains("監控") ||
            lower.contains("auto-reply") ||
            lower.contains("auto reply") ||
            lower.contains("autoreply") ||
            lower.contains("自动回复") ||
            lower.contains("自動回覆")
        val looksLikeWatchMessages = lower.contains("watch") &&
            (lower.contains("message") || lower.contains("messages") || lower.contains("reply"))
        return mentionsMonitor || looksLikeWatchMessages
    }
}

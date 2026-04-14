/*
 * Copyright (C) 2024 The AAOSP Project
 *
 * Licensed under the Apache License, Version 2.0
 */

package com.android.agenticlauncher

import android.app.Application
import android.llm.LlmManager
import android.llm.LlmRequest
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.Executors

class LauncherViewModel(application: Application) : AndroidViewModel(application) {

    private val llmManager: LlmManager? =
        application.getSystemService(LlmManager::class.java)

    private val executor = Executors.newSingleThreadExecutor()

    private val _uiState = MutableStateFlow(LauncherUiState())
    val uiState: StateFlow<LauncherUiState> = _uiState.asStateFlow()

    private val conversationHistory: MutableList<ConversationMessage> =
        Collections.synchronizedList(mutableListOf())

    private var currentSession: LlmManager.Session? = null

    /** Current persistent session ID for multi-turn continuity. */
    private var activeSessionId: String? = null

    init {
        checkServiceReady()
        loadSessionHistory()
    }

    override fun onCleared() {
        super.onCleared()
        currentSession?.cancel()
        executor.shutdown()
    }

    private fun checkServiceReady() {
        val ready = llmManager?.isReady == true
        val modelInfo = llmManager?.modelInfo
        _uiState.update { it.copy(
            serviceReady = ready,
            modelInfo = modelInfo,
            // ILlmService.getAvailableServers() returns the Java toString()
            // of List<McpServerInfo> — i.e.
            //   [McpServerInfo{name=..., packageName=..., tools=N, resources=M}, ...]
            // Build a synthetic list with one entry per tool so the UI count
            // matches what the LLM sees in its <tools> prompt block.
            availableTools = try {
                val raw = llmManager?.availableServers ?: ""
                val toolsRegex = Regex("tools=(\\d+)")
                toolsRegex.findAll(raw)
                    .flatMap { match ->
                        val n = match.groupValues[1].toIntOrNull() ?: 0
                        (1..n).asSequence().map { "tool" }
                    }
                    .toList()
            } catch (e: Exception) { emptyList() }
        ) }
    }

    // ------------------------------------------------------------------
    // Session history
    // ------------------------------------------------------------------

    /**
     * Load the list of past sessions from the LLM service.
     */
    fun loadSessionHistory() {
        val json = try {
            llmManager?.let {
                // listSessions is accessed via the service binder
                val method = it.javaClass.getMethod("listSessions", Int::class.java)
                method.invoke(it, 50) as? String
            }
        } catch (e: Exception) {
            null
        }

        if (json == null) {
            _uiState.update { it.copy(sessions = emptyList()) }
            return
        }

        try {
            val arr = JSONArray(json)
            val sessions = (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                SessionInfo(
                    sessionId = obj.getString("sessionId"),
                    title = obj.optString("title", "Untitled"),
                    updatedAt = obj.getLong("updatedAt"),
                    messageCount = obj.getInt("messageCount")
                )
            }
            _uiState.update { it.copy(sessions = sessions) }
        } catch (e: JSONException) {
            _uiState.update { it.copy(sessions = emptyList()) }
        }
    }

    /**
     * Resume a past session — load its history and continue the conversation.
     */
    fun resumeSession(sessionId: String) {
        activeSessionId = sessionId

        val json = try {
            llmManager?.let {
                val method = it.javaClass.getMethod("getSessionHistory", String::class.java)
                method.invoke(it, sessionId) as? String
            }
        } catch (e: Exception) {
            null
        }

        // Parse history into conversation messages
        conversationHistory.clear()
        if (json != null) {
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    conversationHistory.add(ConversationMessage(
                        role = obj.getString("role"),
                        content = obj.getString("content")
                    ))
                }
            } catch (e: JSONException) {
                // start fresh
            }
        }

        _uiState.update { it.copy(
            currentScreen = Screen.HOME,
            streamedText = "",
            messages = conversationHistory.toList(),
            serverUi = null,
            error = null,
            activeToolCall = null
        ) }
    }

    /**
     * Delete a single session.
     */
    fun deleteSession(sessionId: String) {
        try {
            llmManager?.let {
                val method = it.javaClass.getMethod("deleteSession", String::class.java)
                method.invoke(it, sessionId)
            }
        } catch (e: Exception) {
            // ignore
        }

        // If we just deleted the active session, reset
        if (sessionId == activeSessionId) {
            activeSessionId = null
            conversationHistory.clear()
        }

        loadSessionHistory()
    }

    /**
     * Delete all sessions.
     */
    fun clearAllSessions() {
        try {
            llmManager?.let {
                val method = it.javaClass.getMethod("clearAllSessions")
                method.invoke(it)
            }
        } catch (e: Exception) {
            // ignore
        }

        activeSessionId = null
        conversationHistory.clear()
        loadSessionHistory()

        _uiState.update { it.copy(
            streamedText = "",
            serverUi = null,
            error = null
        ) }
    }

    // ------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------

    fun navigateTo(screen: Screen) {
        _uiState.update { it.copy(currentScreen = screen) }
        if (screen == Screen.HISTORY) {
            loadSessionHistory()
        }
    }

    // ------------------------------------------------------------------
    // Conversation
    // ------------------------------------------------------------------

    fun submitQuery(query: String) {
        if (llmManager == null) {
            _uiState.update { it.copy(error = "LLM service not available") }
            return
        }
        // v0.5.1: if the cached readiness is false, re-poll before
        // giving up. The 3B model takes ~15s to mmap on cold boot; if
        // the user opens the launcher during that window, the init-time
        // checkServiceReady() caches `false` and the UI is stuck on
        // "no model loaded" forever. Re-querying on submit is
        // self-correcting — if ready now, we proceed; if still not
        // ready, the submit fails with the service's own error.
        if (_uiState.value.serviceReady != true) {
            checkServiceReady()
        }

        val userMsg = ConversationMessage("user", query)
        conversationHistory.add(userMsg)

        _uiState.update { it.copy(
            currentScreen = Screen.HOME,
            isGenerating = true,
            currentQuery = query,
            streamedText = "",
            messages = it.messages + userMsg,
            serverUi = null,
            activeToolCall = null,
            error = null
        ) }

        val builder = LlmRequest.Builder(query)
            .setRequestServerUi(true)
            .enableToolUse(true)
            .setConversationJson(buildConversationJson())
            .setTemperature(0.7f)
            .setMaxTokens(2048)

        // If we have an active session, pass it for continuity.
        // The service creates a new one when sessionId is null;
        // startNewConversation() / clearConversation() null this out
        // AND call endSession on the old id so SESSION-scoped consent
        // grants are cleared before a fresh turn.
        activeSessionId?.let { builder.setSessionId(it) }

        val request = builder.build()

        currentSession = llmManager.submit(request, executor, object : LlmManager.Callback() {

            override fun onToken(token: String) {
                _uiState.update { it.copy(
                    streamedText = it.streamedText + token
                ) }
            }

            override fun onToolCall(info: android.content.pm.mcp.McpToolCallInfo) {
                // Built-in framework tool (v0.5): launch_app has
                // packageName=="android" and is a framework-provided
                // capability with no MCP service. The dispatcher fires
                // STARTED so we can actually launch the app, then
                // synthesizes COMPLETED immediately. Fire-and-forget.
                if ("android" == info.packageName
                        && "launch_app" == info.toolName
                        && info.status == 0 /* STATUS_STARTED */) {
                    handleBuiltinLaunchApp(info.argumentsJson)
                }
                // Look up the owning app's icon + label so the launcher
                // can render attribution alongside the tool name. The
                // user always sees which app is being asked to do what.
                val pm = getApplication<android.app.Application>().packageManager
                val (appLabel, appIcon) = try {
                    val ai = pm.getApplicationInfo(info.packageName, 0)
                    pm.getApplicationLabel(ai).toString() to pm.getApplicationIcon(ai)
                } catch (e: Exception) { info.packageName to null }
                val toolCall = ToolCallState(
                    toolName = info.toolName,
                    packageName = info.packageName,
                    appLabel = appLabel,
                    appIcon = appIcon,
                    argumentsJson = info.argumentsJson,
                    status = info.status,
                    durationMs = info.durationMillis
                )
                // STATUS_PERMISSION_REQUIRED = the service is parked on a
                // consent gate. Pop the prompt; the UI will call
                // confirmToolCall() with the user's choice.
                val pending = if (info.status == 3) {
                    PendingConsent(
                        sessionId = info.sessionId,
                        toolName = info.toolName,
                        packageName = info.packageName,
                        appLabel = appLabel,
                        argumentsJson = info.argumentsJson
                    )
                } else null
                _uiState.update { it.copy(
                    activeToolCall = toolCall,
                    pendingConsent = pending ?: it.pendingConsent
                ) }
            }

            override fun onToolResult(info: android.content.pm.mcp.McpToolCallInfo) {
                // Keep the card on screen briefly with the final status
                // so the user sees "✓ Searched contacts in 240 ms"
                // before it fades. The viewmodel clears it when the
                // next stream chunk arrives.
                val pm = getApplication<android.app.Application>().packageManager
                val (appLabel, appIcon) = try {
                    val ai = pm.getApplicationInfo(info.packageName, 0)
                    pm.getApplicationLabel(ai).toString() to pm.getApplicationIcon(ai)
                } catch (e: Exception) { info.packageName to null }
                // Parse structured "needs_permission" errors so we can
                // pop the Open-Settings CTA (when BAL blocked our
                // in-app request activity).
                val missingPerm = parseNeedsPermission(info.resultJson)
                _uiState.update { it.copy(
                    activeToolCall = ToolCallState(
                        toolName = info.toolName,
                        packageName = info.packageName,
                        appLabel = appLabel,
                        appIcon = appIcon,
                        argumentsJson = info.argumentsJson,
                        status = info.status,
                        durationMs = info.durationMillis
                    ),
                    pendingConsent = it.pendingConsent
                        ?.takeUnless { p -> p.toolName == info.toolName },
                    pendingPermission = missingPerm
                        ?.copy(appLabel = appLabel) ?: it.pendingPermission
                ) }
            }

            override fun onServerUi(serverUiJson: String) {
                val uiElements = parseServerUi(serverUiJson)
                _uiState.update { it.copy(serverUi = uiElements) }
            }

            override fun onComplete(fullResponse: String) {
                val asstMsg = ConversationMessage("assistant", fullResponse)
                conversationHistory.add(asstMsg)

                // Capture the session ID from the session handle
                currentSession?.let { session ->
                    activeSessionId = session.id
                }

                _uiState.update { current ->
                    val ui = current.serverUi ?: parseServerUiFromText(fullResponse)
                    current.copy(
                        isGenerating = false,
                        // Completed turn moves from the streaming buffer
                        // into the immutable message log.
                        streamedText = "",
                        messages = current.messages + asstMsg,
                        serverUi = ui,
                        activeToolCall = null
                    )
                }
                currentSession = null
            }

            override fun onError(errorCode: Int, message: String) {
                _uiState.update { it.copy(
                    isGenerating = false,
                    error = "Error ($errorCode): $message",
                    activeToolCall = null
                ) }
                currentSession = null
            }
        })
    }

    /**
     * Fires the Android launch Intent for the app the model asked about
     * via the built-in `launch_app` tool. Fuzzy-matches the requested
     * name against every installed launchable app's label. On match,
     * fires [Intent.ACTION_MAIN] with [Intent.FLAG_ACTIVITY_NEW_TASK]
     * so the app takes foreground. Silent on no match — the user sees
     * the assistant's next turn ("I couldn't find an app named …").
     */
    private fun handleBuiltinLaunchApp(argsJson: String) {
        val name = try {
            JSONObject(argsJson).optString("name", "").trim()
        } catch (e: Exception) { "" }
        if (name.isEmpty()) return
        val ctx = getApplication<android.app.Application>()
        val pm = ctx.packageManager
        val launcherIntent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val candidates = pm.queryIntentActivities(launcherIntent, 0)
        val needle = name.lowercase()
        val match = candidates.firstOrNull { info ->
            val label = info.loadLabel(pm).toString().lowercase()
            label == needle || label.contains(needle) || needle.contains(label)
        } ?: return
        val launchIntent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?: return
        launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(launchIntent)
        } catch (e: Exception) { /* ignore */ }
    }

    fun cancel() {
        // v0.5.1 diagnostic: phantom cancel() calls have been observed
        // ~283ms after submit, killing the chain on the iter-1 boundary
        // before the final prose turn. Suspected IME lifecycle / touch
        // target overlap. Log a stack trace every time cancel() fires
        // so we can identify the caller. Remove the Throwable allocation
        // in v0.5.2 once the root cause is fixed; keep the log line.
        Log.w("AgenticLauncher", "cancel() called (isGenerating="
                + _uiState.value.isGenerating + ")", Throwable("cancel() stack"))
        currentSession?.cancel()
        _uiState.update { it.copy(
            isGenerating = false,
            activeToolCall = null
        ) }
        currentSession = null
    }

    fun executeAction(action: UiAction) {
        submitQuery("Execute: ${action.label} (tool: ${action.tool}, args: ${action.argsJson})")
    }

    fun clearConversation() {
        activeSessionId?.let { endServiceSession(it) }
        activeSessionId = null
        conversationHistory.clear()
        _uiState.update {
            LauncherUiState(
                serviceReady = llmManager?.isReady == true,
                modelInfo = llmManager?.modelInfo
            )
        }
    }

    fun startNewConversation() {
        activeSessionId?.let { endServiceSession(it) }
        activeSessionId = null
        conversationHistory.clear()
        _uiState.update { it.copy(
            currentScreen = Screen.HOME,
            streamedText = "",
            messages = emptyList(),
            serverUi = null,
            error = null,
            activeToolCall = null,
            pendingConsent = null
        ) }
    }

    // ------------------------------------------------------------------
    // HITL consent
    // ------------------------------------------------------------------

    /**
     * The user picked a consent button. Forwards to the service, which
     * releases the parked dispatcher and (if ALLOW) proceeds with the
     * tool invocation. Scope codes: 0=ONCE, 1=SESSION, 2=FOREVER.
     * Decision codes: 1=ALLOW, 2=DENY.
     */
    fun confirmToolCall(decision: Int, scope: Int) {
        val mgr = llmManager ?: return
        val pending = _uiState.value.pendingConsent ?: return
        executor.execute {
            try {
                val method = mgr.javaClass.getMethod(
                    "confirmToolCall",
                    String::class.java, String::class.java,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
                )
                method.invoke(mgr, pending.sessionId, pending.toolName, decision, scope)
            } catch (e: Exception) {
                // If the wrapper doesn't expose it, go through the raw binder.
                try {
                    val field = mgr.javaClass.getDeclaredField("mService").apply { isAccessible = true }
                    val svc = field.get(mgr)
                    val m = svc.javaClass.getMethod(
                        "confirmToolCall",
                        String::class.java, String::class.java,
                        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
                    )
                    m.invoke(svc, pending.sessionId, pending.toolName, decision, scope)
                } catch (e2: Exception) {
                    // swallow — the service-side 60s timeout will resolve as DENY
                }
            }
        }
        _uiState.update { it.copy(pendingConsent = null) }
    }

    /**
     * Best-effort parse of a tool result JSON to detect the
     * "needs_permission" protocol. Returns null when the result is a
     * success, a plain error, or anything we don't recognize.
     */
    private fun parseNeedsPermission(json: String?): PendingPermission? {
        if (json == null || !json.contains("needs_permission")) return null
        return try {
            val o = JSONObject(json)
            if (o.optString("error") != "needs_permission") return null
            PendingPermission(
                packageName = o.optString("package", ""),
                permission = o.optString("permission", ""),
                appLabel = o.optString("package", "")
            )
        } catch (e: JSONException) { null }
    }

    /**
     * User tapped "Open settings" on a PendingPermission — fire the
     * ACTION_APPLICATION_DETAILS_SETTINGS intent for the owning app so
     * they can grant the perm, then come back.
     */
    fun openAppDetailsForPending() {
        val pending = _uiState.value.pendingPermission ?: return
        val ctx = getApplication<android.app.Application>()
        val intent = android.content.Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.fromParts("package", pending.packageName, null)
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(intent)
        } catch (e: Exception) { /* no-op */ }
        _uiState.update { it.copy(pendingPermission = null) }
    }

    fun dismissPendingPermission() {
        _uiState.update { it.copy(pendingPermission = null) }
    }

    private fun endServiceSession(sessionId: String) {
        val mgr = llmManager ?: return
        executor.execute {
            try {
                val method = mgr.javaClass.getMethod("endSession", String::class.java)
                method.invoke(mgr, sessionId)
            } catch (e: Exception) {
                try {
                    val field = mgr.javaClass.getDeclaredField("mService").apply { isAccessible = true }
                    val svc = field.get(mgr)
                    val m = svc.javaClass.getMethod("endSession", String::class.java)
                    m.invoke(svc, sessionId)
                } catch (e2: Exception) { /* best-effort */ }
            }
        }
    }

    private fun buildConversationJson(): String? {
        if (conversationHistory.isEmpty()) return null
        val arr = JSONArray()
        val recent = synchronized(conversationHistory) {
            conversationHistory.takeLast(10)
        }
        for (msg in recent) {
            val obj = JSONObject()
            obj.put("role", msg.role)
            obj.put("content", msg.content)
            arr.put(obj)
        }
        return arr.toString()
    }

    // ------------------------------------------------------------------
    // Server UI JSON parsing
    // ------------------------------------------------------------------

    private fun parseServerUi(json: String): List<UiElement> {
        return try {
            val root = JSONObject(json)
            val uiArray = root.getJSONArray("ui")
            parseUiArray(uiArray)
        } catch (e: JSONException) {
            emptyList()
        }
    }

    private fun parseServerUiFromText(text: String): List<UiElement>? {
        val marker = "\"ui\""
        val markerIdx = text.indexOf(marker)
        if (markerIdx < 0) return null

        val braceStart = text.lastIndexOf('{', markerIdx)
        if (braceStart < 0) return null

        for (end in braceStart + 2..text.length) {
            if (text[end - 1] != '}') continue
            try {
                val candidate = text.substring(braceStart, end)
                val root = JSONObject(candidate)
                if (!root.has("ui")) continue
                return parseUiArray(root.getJSONArray("ui"))
            } catch (e: JSONException) {
                // try longer
            }
        }
        return null
    }

    private fun parseUiArray(array: JSONArray): List<UiElement> {
        val elements = mutableListOf<UiElement>()
        for (i in 0 until array.length()) {
            try {
                val obj = array.getJSONObject(i)
                when (obj.optString("type", "text")) {
                    "card" -> elements.add(UiElement.Card(
                        title = obj.optString("title", ""),
                        content = obj.optString("content", ""),
                        actions = parseActions(obj.optJSONArray("actions"))
                    ))
                    "list" -> elements.add(UiElement.ItemList(
                        title = obj.optString("title", ""),
                        items = parseStringArray(obj.optJSONArray("items"))
                    ))
                    "text" -> elements.add(
                        UiElement.Text(content = obj.optString("content", ""))
                    )
                    "action" -> elements.add(UiElement.ActionButton(
                        action = UiAction(
                            label = obj.optString("label", ""),
                            tool = obj.optString("tool", ""),
                            argsJson = obj.optJSONObject("args")?.toString() ?: "{}"
                        )
                    ))
                }
            } catch (e: JSONException) { /* skip */ }
        }
        return elements
    }

    private fun parseActions(array: JSONArray?): List<UiAction> {
        if (array == null) return emptyList()
        val actions = mutableListOf<UiAction>()
        for (i in 0 until array.length()) {
            try {
                val obj = array.getJSONObject(i)
                actions.add(UiAction(
                    label = obj.optString("label", ""),
                    tool = obj.optString("tool", ""),
                    argsJson = obj.optJSONObject("args")?.toString() ?: "{}"
                ))
            } catch (e: JSONException) { /* skip */ }
        }
        return actions
    }

    private fun parseStringArray(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).map { array.optString(it, "") }
    }
}

// ------------------------------------------------------------------
// Data classes
// ------------------------------------------------------------------

enum class Screen { HOME, HISTORY }

data class LauncherUiState(
    val currentScreen: Screen = Screen.HOME,
    val serviceReady: Boolean = false,
    val modelInfo: String? = null,
    val isGenerating: Boolean = false,
    val currentQuery: String = "",
    val streamedText: String = "",
    /**
     * Full chat log for the active conversation. Each user turn and
     * each completed assistant turn is appended as a [ConversationMessage].
     * The in-flight assistant turn lives in [streamedText] until it
     * completes, at which point it's appended here and [streamedText]
     * is cleared.
     */
    val messages: List<ConversationMessage> = emptyList(),
    val serverUi: List<UiElement>? = null,
    val activeToolCall: ToolCallState? = null,
    val pendingConsent: PendingConsent? = null,
    val pendingPermission: PendingPermission? = null,
    val error: String? = null,
    val availableTools: List<String> = emptyList(),
    val sessions: List<SessionInfo> = emptyList()
)

/** Consent prompt state surfaced to the UI when a tool requires HITL. */
data class PendingConsent(
    val sessionId: String,
    val toolName: String,
    val packageName: String,
    val appLabel: String,
    val argumentsJson: String
)

/**
 * Runtime-permission CTA surfaced when an MCP tool returned
 * {@code {"error":"needs_permission",...}} — typically because Android
 * 15 BAL blocked the tool's own permission-request activity.
 */
data class PendingPermission(
    val packageName: String,
    val permission: String,
    val appLabel: String
)

data class SessionInfo(
    val sessionId: String,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int
)

data class ToolCallState(
    val toolName: String,
    val packageName: String,
    val appLabel: String,
    val appIcon: android.graphics.drawable.Drawable?,
    val argumentsJson: String,
    val status: Int,    // McpToolCallInfo.STATUS_*
    val durationMs: Int // -1 while in-flight
)

data class ConversationMessage(
    val role: String,
    val content: String
)

data class UiAction(
    val label: String,
    val tool: String,
    val argsJson: String
)

sealed class UiElement {
    data class Card(
        val title: String,
        val content: String,
        val actions: List<UiAction>
    ) : UiElement()

    data class ItemList(
        val title: String,
        val items: List<String>
    ) : UiElement()

    data class Text(
        val content: String
    ) : UiElement()

    data class ActionButton(
        val action: UiAction
    ) : UiElement()
}

/*
 * Copyright (C) 2024 The AAOSP Project
 *
 * Licensed under the Apache License, Version 2.0
 */

package com.android.agenticlauncher

import android.app.Application
import android.llm.LlmManager
import android.llm.LlmRequest
import android.content.pm.mcp.McpServerInfo
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * ViewModel for the Agentic Launcher.
 *
 * Manages the conversation state, submits requests to the LLM System Service,
 * and parses server-driven UI responses for the Compose layer to render.
 */
class LauncherViewModel(application: Application) : AndroidViewModel(application) {

    private val llmManager: LlmManager? =
        application.getSystemService(LlmManager::class.java)

    private val executor = Executors.newSingleThreadExecutor()

    // UI state
    private val _uiState = MutableStateFlow(LauncherUiState())
    val uiState: StateFlow<LauncherUiState> = _uiState.asStateFlow()

    // Conversation history for multi-turn
    private val conversationHistory = mutableListOf<ConversationMessage>()

    // Current session handle for cancellation
    private var currentSession: LlmManager.Session? = null

    init {
        checkServiceReady()
    }

    private fun checkServiceReady() {
        val ready = llmManager?.isReady == true
        val modelInfo = llmManager?.modelInfo
        _uiState.value = _uiState.value.copy(
            serviceReady = ready,
            modelInfo = modelInfo,
            availableTools = llmManager?.availableServers
                ?.flatMap { it.tools.map { t -> t.name } }
                ?: emptyList()
        )
    }

    /**
     * Submit a user query to the LLM.
     */
    fun submitQuery(query: String) {
        if (llmManager == null) {
            _uiState.value = _uiState.value.copy(
                error = "LLM service not available"
            )
            return
        }

        // Add user message to history
        conversationHistory.add(ConversationMessage("user", query))

        // Update UI state
        _uiState.value = _uiState.value.copy(
            isGenerating = true,
            currentQuery = query,
            streamedText = "",
            serverUi = null,
            activeToolCall = null,
            error = null
        )

        // Build the request
        val request = LlmRequest.Builder(query)
            .setRequestServerUi(true)
            .enableToolUse(true)
            .setConversationJson(buildConversationJson())
            .setTemperature(0.7f)
            .setMaxTokens(2048)
            .build()

        // Submit to LLM service
        currentSession = llmManager.submit(request, executor, object : LlmManager.Callback() {

            override fun onToken(token: String) {
                val current = _uiState.value
                _uiState.value = current.copy(
                    streamedText = current.streamedText + token
                )
            }

            override fun onToolCall(toolName: String, argumentsJson: String) {
                _uiState.value = _uiState.value.copy(
                    activeToolCall = ToolCallState(toolName, argumentsJson)
                )
            }

            override fun onToolResult(toolName: String, resultJson: String) {
                _uiState.value = _uiState.value.copy(
                    activeToolCall = null
                )
            }

            override fun onServerUi(serverUiJson: String) {
                val uiElements = parseServerUi(serverUiJson)
                _uiState.value = _uiState.value.copy(
                    serverUi = uiElements
                )
            }

            override fun onComplete(fullResponse: String) {
                conversationHistory.add(
                    ConversationMessage("assistant", fullResponse)
                )

                // Try to parse server UI from the full response if not
                // already received via onServerUi
                val current = _uiState.value
                val ui = current.serverUi ?: parseServerUiFromText(fullResponse)

                _uiState.value = current.copy(
                    isGenerating = false,
                    streamedText = fullResponse,
                    serverUi = ui,
                    activeToolCall = null
                )
                currentSession = null
            }

            override fun onError(errorCode: Int, message: String) {
                _uiState.value = _uiState.value.copy(
                    isGenerating = false,
                    error = "Error ($errorCode): $message",
                    activeToolCall = null
                )
                currentSession = null
            }
        })
    }

    /**
     * Cancel the current generation.
     */
    fun cancel() {
        currentSession?.cancel()
        _uiState.value = _uiState.value.copy(
            isGenerating = false,
            activeToolCall = null
        )
        currentSession = null
    }

    /**
     * Execute an action from a server UI element (e.g., user taps a button
     * that triggers a tool call).
     */
    fun executeAction(action: UiAction) {
        submitQuery("Execute: ${action.label} (tool: ${action.tool}, args: ${action.argsJson})")
    }

    /**
     * Clear conversation and reset.
     */
    fun clearConversation() {
        conversationHistory.clear()
        _uiState.value = LauncherUiState(
            serviceReady = llmManager?.isReady == true,
            modelInfo = llmManager?.modelInfo
        )
    }

    private fun buildConversationJson(): String? {
        if (conversationHistory.isEmpty()) return null
        val arr = JSONArray()
        // Keep last 10 messages to stay within context
        val recent = conversationHistory.takeLast(10)
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

    /**
     * Parse structured UI JSON.
     *
     * Expected format:
     * {
     *   "ui": [
     *     {"type": "card", "title": "...", "content": "...", "actions": [...]},
     *     {"type": "list", "title": "...", "items": [...]},
     *     {"type": "text", "content": "..."},
     *     {"type": "action", "label": "...", "tool": "...", "args": {...}}
     *   ]
     * }
     */
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
        // Try to find JSON with "ui" key in the response text
        val jsonStart = text.indexOf("{")
        val jsonEnd = text.lastIndexOf("}")
        if (jsonStart < 0 || jsonEnd <= jsonStart) return null

        return try {
            val candidate = text.substring(jsonStart, jsonEnd + 1)
            val root = JSONObject(candidate)
            if (!root.has("ui")) return null
            parseUiArray(root.getJSONArray("ui"))
        } catch (e: JSONException) {
            null
        }
    }

    private fun parseUiArray(array: JSONArray): List<UiElement> {
        val elements = mutableListOf<UiElement>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val type = obj.optString("type", "text")
            when (type) {
                "card" -> elements.add(
                    UiElement.Card(
                        title = obj.optString("title", ""),
                        content = obj.optString("content", ""),
                        actions = parseActions(obj.optJSONArray("actions"))
                    )
                )
                "list" -> elements.add(
                    UiElement.ItemList(
                        title = obj.optString("title", ""),
                        items = parseStringArray(obj.optJSONArray("items"))
                    )
                )
                "text" -> elements.add(
                    UiElement.Text(
                        content = obj.optString("content", "")
                    )
                )
                "action" -> elements.add(
                    UiElement.ActionButton(
                        action = UiAction(
                            label = obj.optString("label", ""),
                            tool = obj.optString("tool", ""),
                            argsJson = obj.optJSONObject("args")?.toString() ?: "{}"
                        )
                    )
                )
            }
        }
        return elements
    }

    private fun parseActions(array: JSONArray?): List<UiAction> {
        if (array == null) return emptyList()
        val actions = mutableListOf<UiAction>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            actions.add(
                UiAction(
                    label = obj.optString("label", ""),
                    tool = obj.optString("tool", ""),
                    argsJson = obj.optJSONObject("args")?.toString() ?: "{}"
                )
            )
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

data class LauncherUiState(
    val serviceReady: Boolean = false,
    val modelInfo: String? = null,
    val isGenerating: Boolean = false,
    val currentQuery: String = "",
    val streamedText: String = "",
    val serverUi: List<UiElement>? = null,
    val activeToolCall: ToolCallState? = null,
    val error: String? = null,
    val availableTools: List<String> = emptyList()
)

data class ToolCallState(
    val toolName: String,
    val argumentsJson: String
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

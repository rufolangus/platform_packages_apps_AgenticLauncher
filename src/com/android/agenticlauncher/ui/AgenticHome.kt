/*
 * Copyright (C) 2024 The AAOSP Project
 *
 * Licensed under the Apache License, Version 2.0
 */

package com.android.agenticlauncher.ui

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.android.agenticlauncher.*

/**
 * Main composable for the Agentic Launcher home screen.
 *
 * Layout:
 * - Top: streaming response area / server-driven UI cards
 * - Bottom: input bar with send button
 *
 * When idle, shows a greeting and available tool count.
 * During generation, shows streamed tokens and tool call activity.
 * On completion, renders the server-driven UI if available,
 * otherwise shows the text response.
 */
@Composable
fun AgenticHome(
    viewModel: LauncherViewModel,
    onAppLaunch: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var inputText by rememberSaveable { mutableStateOf("") }

    Scaffold(
        bottomBar = {
            InputBar(
                text = inputText,
                onTextChange = { inputText = it },
                onSubmit = {
                    if (inputText.isNotBlank()) {
                        viewModel.submitQuery(inputText)
                        inputText = ""
                    }
                },
                isGenerating = uiState.isGenerating,
                onCancel = { viewModel.cancel() },
                serviceReady = uiState.serviceReady
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // Greeting / status
            if (!uiState.isGenerating && uiState.serverUi == null
                && uiState.streamedText.isEmpty()) {
                item {
                    GreetingCard(uiState)
                }
            }

            // Error
            uiState.error?.let { error ->
                item {
                    ErrorCard(error)
                }
            }

            // Active tool call indicator
            uiState.activeToolCall?.let { toolCall ->
                item {
                    ToolCallIndicator(toolCall)
                }
            }

            // Streamed text (during generation or if no server UI)
            if (uiState.streamedText.isNotEmpty() && uiState.serverUi == null) {
                item {
                    TextResponseCard(uiState.streamedText, uiState.isGenerating)
                }
            }

            // Server-driven UI elements
            uiState.serverUi?.let { elements ->
                items(elements) { element ->
                    ServerUiElement(
                        element = element,
                        onAction = { action -> viewModel.executeAction(action) }
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------
// Input Bar
// ------------------------------------------------------------------

@Composable
fun InputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
    isGenerating: Boolean,
    onCancel: () -> Unit,
    serviceReady: Boolean
) {
    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        if (serviceReady) "Ask anything..."
                        else "LLM loading..."
                    )
                },
                enabled = serviceReady && !isGenerating,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSubmit() })
            )

            Spacer(modifier = Modifier.width(8.dp))

            if (isGenerating) {
                FilledTonalButton(onClick = onCancel) {
                    Text("Stop")
                }
            } else {
                Button(
                    onClick = onSubmit,
                    enabled = serviceReady && text.isNotBlank()
                ) {
                    Text("Send")
                }
            }
        }
    }
}

// ------------------------------------------------------------------
// Greeting
// ------------------------------------------------------------------

@Composable
fun GreetingCard(state: LauncherUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "Hello",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (state.serviceReady)
                    "${state.availableTools.size} tools available from installed apps"
                else
                    "Loading AI model...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
            if (state.modelInfo != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = state.modelInfo!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
                )
            }
        }
    }
}

// ------------------------------------------------------------------
// Tool Call Indicator
// ------------------------------------------------------------------

@Composable
fun ToolCallIndicator(toolCall: ToolCallState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Using ${toolCall.toolName}...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
    }
}

// ------------------------------------------------------------------
// Text Response
// ------------------------------------------------------------------

@Composable
fun TextResponseCard(text: String, isGenerating: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge
            )
            if (isGenerating) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

// ------------------------------------------------------------------
// Error
// ------------------------------------------------------------------

@Composable
fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

// ------------------------------------------------------------------
// Server-Driven UI Renderer
// ------------------------------------------------------------------

/**
 * Renders a single server-driven UI element.
 *
 * This is the core of the agentic launcher — the LLM produces structured
 * JSON, and this composable renders it as Material 3 components.
 */
@Composable
fun ServerUiElement(
    element: UiElement,
    onAction: (UiAction) -> Unit
) {
    when (element) {
        is UiElement.Card -> ServerCard(element, onAction)
        is UiElement.ItemList -> ServerList(element)
        is UiElement.Text -> ServerText(element)
        is UiElement.ActionButton -> ServerActionButton(element.action, onAction)
    }
}

@Composable
fun ServerCard(card: UiElement.Card, onAction: (UiAction) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (card.title.isNotEmpty()) {
                Text(
                    text = card.title,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (card.content.isNotEmpty()) {
                Text(
                    text = card.content,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (card.actions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (action in card.actions) {
                        FilledTonalButton(
                            onClick = { onAction(action) }
                        ) {
                            Text(action.label)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ServerList(list: UiElement.ItemList) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (list.title.isNotEmpty()) {
                Text(
                    text = list.title,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            for ((index, item) in list.items.withIndex()) {
                if (index > 0) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }
                Text(
                    text = item,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
fun ServerText(element: UiElement.Text) {
    Text(
        text = element.content,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
fun ServerActionButton(action: UiAction, onAction: (UiAction) -> Unit) {
    Button(
        onClick = { onAction(action) },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(action.label)
    }
}

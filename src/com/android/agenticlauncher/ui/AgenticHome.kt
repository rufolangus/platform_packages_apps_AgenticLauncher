/*
 * Copyright (C) 2024 The AAOSP Project
 *
 * Licensed under the Apache License, Version 2.0
 */

package com.android.agenticlauncher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.agenticlauncher.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Main composable for the Agentic Launcher.
 *
 * Two screens:
 * - HOME: conversation view with input bar
 * - HISTORY: list of past sessions with resume/delete/clear
 */
@Composable
fun AgenticHome(
    viewModel: LauncherViewModel,
    onAppLaunch: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    when (uiState.currentScreen) {
        Screen.HOME -> HomeScreen(viewModel, uiState)
        Screen.HISTORY -> HistoryScreen(viewModel, uiState)
    }
}

// ------------------------------------------------------------------
// Home Screen
// ------------------------------------------------------------------

@Composable
private fun HomeScreen(viewModel: LauncherViewModel, uiState: LauncherUiState) {
    var inputText by rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = {
            HomeTopBar(
                hasContent = uiState.streamedText.isNotEmpty()
                        || uiState.serverUi != null,
                onHistory = { viewModel.navigateTo(Screen.HISTORY) },
                onNewChat = { viewModel.startNewConversation() },
                onClear = { viewModel.clearConversation() }
            )
        },
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
            // Greeting when idle
            if (!uiState.isGenerating && uiState.serverUi == null
                && uiState.streamedText.isEmpty()) {
                item { GreetingCard(uiState) }
            }

            uiState.error?.let { error ->
                item { ErrorCard(error) }
            }

            uiState.activeToolCall?.let { toolCall ->
                item { ToolCallIndicator(toolCall) }
            }

            if (uiState.streamedText.isNotEmpty() && uiState.serverUi == null) {
                item {
                    TextResponseCard(uiState.streamedText, uiState.isGenerating)
                }
            }

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
// Home Top Bar
// ------------------------------------------------------------------

@Composable
private fun HomeTopBar(
    hasContent: Boolean,
    onHistory: () -> Unit,
    onNewChat: () -> Unit,
    onClear: () -> Unit
) {
    Surface(
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .statusBarsPadding(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onHistory) {
                Icon(Icons.Default.History, contentDescription = "History")
            }

            Spacer(modifier = Modifier.weight(1f))

            if (hasContent) {
                IconButton(onClick = onNewChat) {
                    Icon(Icons.Default.Add, contentDescription = "New chat")
                }
            }
        }
    }
}

// ------------------------------------------------------------------
// History Screen
// ------------------------------------------------------------------

@Composable
private fun HistoryScreen(viewModel: LauncherViewModel, uiState: LauncherUiState) {
    var showClearDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            HistoryTopBar(
                onBack = { viewModel.navigateTo(Screen.HOME) },
                onClearAll = { showClearDialog = true },
                hasItems = uiState.sessions.isNotEmpty()
            )
        }
    ) { padding ->
        if (uiState.sessions.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.History,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                            .copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No conversations yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                            .copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = uiState.sessions,
                    key = { it.sessionId }
                ) { session ->
                    SessionCard(
                        session = session,
                        onResume = { viewModel.resumeSession(session.sessionId) },
                        onDelete = { viewModel.deleteSession(session.sessionId) }
                    )
                }
            }
        }
    }

    // Clear all confirmation dialog
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear all history?") },
            text = { Text("This will permanently delete all conversations and cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllSessions()
                        showClearDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Clear all")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun HistoryTopBar(
    onBack: () -> Unit,
    onClearAll: () -> Unit,
    hasItems: Boolean
) {
    Surface(
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .statusBarsPadding(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = "History",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            if (hasItems) {
                IconButton(onClick = onClearAll) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Clear all",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------
// Session Card
// ------------------------------------------------------------------

@Composable
private fun SessionCard(
    session: SessionInfo,
    onResume: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onResume() }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${session.messageCount} messages \u00b7 ${dateFormat.format(Date(session.updatedAt))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { showDeleteDialog = true }) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete conversation?") },
            text = { Text("\"${session.title}\" will be permanently deleted.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
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
                        if (true) "Ask anything..."
                        else "LLM loading..."
                    )
                },
                enabled = !isGenerating,  // Allow input even without model
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
                    enabled = text.isNotBlank()  // Allow send even without model
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
            state.modelInfo?.let { info ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = info,
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
            Text(
                text = "Using ${toolCall.toolName}...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
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
            Text(text = text, style = MaterialTheme.typography.bodyLarge)
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

@Composable
fun ServerUiElement(element: UiElement, onAction: (UiAction) -> Unit) {
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
                Text(text = card.title, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (card.content.isNotEmpty()) {
                Text(text = card.content, style = MaterialTheme.typography.bodyMedium)
            }
            if (card.actions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (action in card.actions) {
                        FilledTonalButton(onClick = { onAction(action) }) {
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
                Text(text = list.title, style = MaterialTheme.typography.titleMedium)
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
    Button(onClick = { onAction(action) }, modifier = Modifier.fillMaxWidth()) {
        Text(action.label)
    }
}

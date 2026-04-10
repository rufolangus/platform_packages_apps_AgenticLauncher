/*
 * Copyright (C) 2024 The AAOSP Project
 *
 * Licensed under the Apache License, Version 2.0
 */

package com.android.agenticlauncher

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.android.agenticlauncher.ui.AgenticHome
import com.android.agenticlauncher.ui.theme.AgenticLauncherTheme

/**
 * Main launcher activity for AAOSP.
 *
 * Replaces the traditional app grid with an agent-driven experience.
 * The user types or speaks a request, the LLM processes it (potentially
 * calling MCP tools on installed apps), and the result is rendered as
 * structured UI cards.
 */
class AgenticLauncherActivity : ComponentActivity() {

    private val viewModel: LauncherViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AgenticLauncherTheme {
                AgenticHome(
                    viewModel = viewModel,
                    onAppLaunch = { packageName -> launchApp(packageName) }
                )
            }
        }
    }

    private fun launchApp(packageName: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            startActivity(intent)
        }
    }
}

# Agentic Launcher

The home screen for [AAOSP](https://github.com/rufolangus/AAOSP) — an agent-driven launcher that replaces the traditional app grid with conversational AI powered by on-device MCP tool calling.

## What It Does

You talk to your phone. The LLM calls tools across your installed apps. The launcher renders the result.

```
You:       what's John's number?
[AAOSP]    calling search_contacts (ContactsMcp)...
AAOSP:     John Smith — mobile 555-1234, work 555-5678
           [Call]  [Message]
```

No app switching. No searching. One sentence, and the right tools are called automatically.

## Architecture

```
AgenticLauncherActivity (Compose)
  └── LauncherViewModel
       ├── LlmManager.submit()         → sends prompt (+ sessionId for continuity)
       ├── Callback.onToken()           → streams response text
       ├── Callback.onToolCall(info)    → tool-call card; on STATUS_PERMISSION_REQUIRED
       │                                  sets pendingConsent → ConsentPromptCard
       ├── Callback.onToolResult(info)  → final tool-call state; if result is
       │                                  {"error":"needs_permission",…} sets
       │                                  pendingPermission → PermissionRequiredCard
       ├── Callback.onServerUi()        → parses server-driven UI JSON
       ├── Callback.onComplete()        → renders final result
       ├── confirmToolCall(decision,    → forwards to ILlmService.confirmToolCall;
       │                   scope)          unblocks the service-side ConsentGate
       └── endServiceSession(oldId)     → on New Chat / Clear, clears SESSION grants
```

### Launching apps (v0.5)

The launcher is a launcher — not just a chat surface. The LLM can fire
an intent into any installed app via the framework-provided built-in
**`launch_app`** tool. No MCP required.

- User says *"open Settings"*, *"launch Camera"*, or *"start the
  browser"*.
- Model emits `<tool_call>launch_app{"name":"Settings"}</tool_call>`.
- Dispatcher routes with reserved `packageName="android"` →
  `handleBuiltinLaunchApp` fuzzy-matches the name against every
  installed launchable app (via
  `PackageManager.queryIntentActivities(ACTION_MAIN, CATEGORY_LAUNCHER)`)
  and fires `startActivity` with `getLaunchIntentForPackage`.
- Fire-and-forget: the service doesn't wait for launch confirmation.
  The real feedback is the app actually opening.

### Human-in-the-loop consent (v0.5)

Tools declared with `android:mcpRequiresConfirmation="true"` pause the
agentic loop and surface a **ConsentPromptCard** inline in the chat. The
card shows the owning app's name + label, the tool being invoked, and a
key:value readout of the arguments the model is about to send. Four
buttons: **Once** / **This chat** / **Always** / **Deny**.

- **Once** — ONCE grant, consumed on the next call.
- **This chat** — SESSION grant, cleared when the launcher starts a new
  chat (which in turn calls `ILlmService.endSession(oldId)`).
- **Always** — FOREVER grant (auto-downgraded to SESSION service-side
  for write-intent tools).
- **Deny** — returns `{"error":"denied_by_user"}` to the model and
  continues the chain.

If the tool's own MCP service needs a runtime Android permission it
doesn't yet hold, it first tries to pop the system permission dialog
in-app via a translucent activity. On Android 15 background-activity-
launch restrictions this may be blocked; in that case the tool returns
`{"error":"needs_permission",...}` and the launcher falls back to a
**PermissionRequiredCard** with a one-tap *Open settings* CTA to the
owning app's details page.

### Agentic chaining (v0.5)

A single user turn can trigger up to `LlmRequest.maxToolCalls` tool
invocations (default 5, hard cap 8). Each iteration emits typed
`onToolCall`/`onToolResult` callbacks; the UI shows each step's
tool-call card with iteration index so chained steps accumulate in
the chat rather than overwriting a single spinner. Cross-MCP chaining
works out of the box — e.g. `search_contacts` (ContactsMcp) →
`create_event` (CalendarMcp) for *"schedule dinner with Sarah
Tuesday 7pm"*.

### Conversation history (v0.5)

Every completed turn is appended to a scrollable message log.
`ChatMessageBubble` renders user turns as right-aligned primary
bubbles and assistant turns as left-aligned neutral bubbles. New
submits no longer overwrite the prior response — the history
accumulates until the user taps *New chat* (which also fires
`ILlmService.endSession` to clear SESSION-scoped consent grants).

### Session continuity (v0.5)

`LauncherViewModel` threads `activeSessionId` through every submit so
the system-side LLM sees prior-turn context. *New chat* nulls it and
fires `ILlmService.endSession(oldId)` to clear SESSION-scoped consent
grants; *Clear conversation* does the same.

### Server-Driven UI

The LLM produces structured JSON. The launcher renders it as Material 3 components:

| JSON Type | Renders As |
|---|---|
| `card` | Card with title, content, action buttons |
| `list` | Card with items and dividers |
| `text` | Plain text block |
| `action` | Button that triggers a tool call |

Action buttons loop back through the LLM — tapping "Call" sends a new prompt that invokes the dialer tool.

### Session History

- Conversations persist via `LlmSessionStore` in the LLM System Service
- History screen: browse, resume, or delete past conversations
- Clear all with confirmation dialog
- Sessions auto-titled from the first user message

## Tech Stack

- **Jetpack Compose** — declarative UI, Material 3, dynamic color (Material You)
- **StateFlow** — reactive state with atomic updates via `.update{}`
- **LlmManager** — SDK wrapper for the LLM System Service binder
- **Platform certificate** — installed in `/system/priv-app/`, holds `SUBMIT_LLM_REQUEST`

## Permissions

| Permission | Why |
|---|---|
| `SUBMIT_LLM_REQUEST` | Submit prompts to the LLM (signature\|privileged) |
| `QUERY_ALL_PACKAGES` | Discover installed apps for launching |

Regular apps cannot hold `SUBMIT_LLM_REQUEST` — only system apps signed with the platform certificate. Apps are MCP tool providers, not AI clients.

## Building

This app builds as part of the AAOSP tree:

```bash
# After repo sync with AAOSP local manifests:
source build/envsetup.sh
lunch aosp_cf_x86_64_phone-userdebug
m AgenticLauncher
```

Or build the full system image which includes it automatically.

## Part of AAOSP

This is one component of [Agentic AOSP](https://github.com/rufolangus/AAOSP). See the main repo for the full architecture, build instructions, and other components.

## License

Apache 2.0

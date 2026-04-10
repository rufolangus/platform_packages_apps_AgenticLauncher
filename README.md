# Agentic Launcher

The home screen for [AAOSP](https://github.com/rufolangus/AAOSP) — an agent-driven launcher that replaces the traditional app grid with conversational AI powered by on-device MCP tool calling.

## What It Does

You talk to your phone. The LLM calls tools across your installed apps. The launcher renders the result.

```
You: "What's John's number?"

[Using search_contacts...]

┌──────────────────────────────┐
│ John Smith                   │
│ Mobile: 555-1234             │
│ Work: 555-5678               │
│                              │
│  [Call]  [Message]           │
└──────────────────────────────┘
```

No app switching. No searching. One sentence, and the right tools are called automatically.

## Architecture

```
AgenticLauncherActivity (Compose)
  └── LauncherViewModel
       ├── LlmManager.submit()         → sends prompt to LLM System Service
       ├── Callback.onToken()           → streams response text
       ├── Callback.onToolCall()        → shows "Using contacts..."
       ├── Callback.onServerUi()        → parses server-driven UI JSON
       └── Callback.onComplete()        → renders final result
```

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

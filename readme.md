# PhonePilot

**An AI agent that understands, controls, and operates your Android phone.**

> Talk to your phone. Let the agent operate it.

PhonePilot is an experimental Android project that turns a smartphone into an AI-operable environment. Instead of just answering questions, it interprets a natural-language instruction, plans the steps, interacts with apps, observes the resulting UI, and continues until the task is done.

**Example:** *"Open WhatsApp and message Rahul that I'll reach in 20 minutes."*

## Status

🚧 **Architecture / MVP development.** The first milestone is a reliable Android control layer. Features below are targets, not yet complete.

## How It Works

PhonePilot runs an agent loop:

```
Observe → Think/Plan → Act → Observe again → ...
```

Because it reads the actual screen state each step, it can adapt when a button moves or an unexpected screen appears, rather than following a fixed script.

```
User (text/voice)
      ↓
   AI Agent  →  Action Planner
      ↓
 Structured Actions (JSON)
      ↓
 Action Validator
      ↓
 Action Executor
      ↓
 Android Control (Accessibility, Intents, System APIs)
      ↓
 Phone UI  →  Observe  →  back to Agent
```

### Control strategy (in order of preference)

1. **Native APIs / Intents** when available (e.g., set an alarm)
2. **AccessibilityService** for semantic UI interaction
3. **Vision model** as a fallback when an app exposes no usable accessibility tree

### Structured actions

The LLM never gets unrestricted device access. It emits validated JSON actions:

```json
{ "action": "open_app", "package": "com.android.settings" }
{ "action": "tap", "target": "Wi-Fi" }
{ "action": "type", "text": "Hello Rahul" }
```

Planned actions: `open_app`, `tap`, `long_press`, `type`, `scroll`, `back`, `home`, `wait`, `read_screen`, `find_element`

## Security

- **Low risk** (open apps, scroll, read, search): automated
- **Medium risk** (send messages, change settings): confirmation configurable
- **High risk** (payments, banking, passwords, OTPs, permanent deletion): explicit user involvement required
- The agent pauses at login screens; the user authenticates, then it resumes
- Safeguards: max actions per task, time limits, emergency stop, action logs

## Tech Stack

- **Android:** Kotlin, Android SDK, AccessibilityService, Intents, Foreground Services
- **AI:** Modular provider layer (cloud LLM, local LLM, multimodal/vision models)
- **Backend (optional):** agent orchestration, memory, model inference

## Design Principles

Agent-first · Observe before acting · Structured actions · Semantic interaction over coordinates · Native APIs when available · Human control for sensitive actions · Provider independence · Privacy by design · Modular architecture

## Disclaimer

PhonePilot is experimental. Android and app developers may restrict automation, and some apps may block or limit accessibility access. PhonePilot does not attempt to bypass app security mechanisms, and sensitive operations stay under explicit user control.

---

*From "How do I do this?" to "Do this for me."*

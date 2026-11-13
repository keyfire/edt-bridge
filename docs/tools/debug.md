---
title: "Debug"
description: "Debug tools: attach to the debug server of a running infobase, inspect threads and variables, step through code and evaluate expressions."
sidebar:
  label: "Debug"
  order: 10
---

These tools attach to the debug server (dbgs) of a running infobase and drive execution. Use a test
stand. All tools require a token; `edt_evaluate` also requires additional permissions.

With `EDT_BRIDGE_PII` set, the values that `edt_debug_inspect` and `edt_evaluate` return are
filtered of personal data; see [Personal data](/personal-data).

| Debug tool | What it does |
|------------|--------------|
| `edt_debug_attach` · `edt_debug_detach` | Attaches a debug session to the debug server of a running infobase and returns a `sessionId` for the other debug tools. Detaching terminates the session and frees the infobase. |
| `edt_debug_inspect` · `edt_debug_control` | Lists a session's threads and, for the suspended ones, their BSL stack frames and the top frame's variables, read-only. Then drives execution: `suspend` and `resume`, or `stepOver`, `stepInto` and `stepReturn` on a suspended thread. |
| `edt_evaluate` | Evaluates an arbitrary BSL expression in a suspended frame, which is code execution against the live infobase. It needs the token, `allowCodeExecution=true` on the call, and the server switch `EDT_BRIDGE_ALLOW_EVALUATE=1`, which is off by default. |

---
title: "Tools"
description: "Every edt_* tool the bridge exposes: reading the live model, writing through EDT's own engine, and debugging a running infobase."
sidebar:
  label: "Overview"
  order: 0
---

Tool names are `edt_*` in snake_case, parameters are camelCase: `projectName`, `fqn`, `queryText`.
Object names are Cyrillic, as in the configuration; the type prefix is English:
`Catalog.Контрагенты`, `Document.ЗаказКлиента`. An MCP host presents tools from all servers in one list. The `edt_` prefix identifies EDT tools;
for example, `edt_rename` can be distinguished from a server's `rename` tool.

A call with an undeclared parameter is rejected. The response suggests a similar name
(`deleteContents` → `deleteContent`) and lists the accepted parameters.

Together the tools close the whole cycle without leaving MCP: create, develop, build, deliver, debug.

![Full delivery cycle over MCP](https://raw.githubusercontent.com/keyfire/edt-bridge/main/docs/delivery.svg)

## Tool groups

The catalogue is divided by what a tool reads or changes. Each page has a table of its
tools and the details of their parameters and results.

| Group | What its tools do |
|-------|-------------------|
| <a id="read"></a><a id="project-and-runtime-diagnostics"></a>[Read: diagnostics and reference](https://docs.keyfire.ru/edt-bridge/tools/read-diagnostics/) | Projects and validation problems, support status, check descriptions, tool and property reference, the Syntax Helper. |
| [Read: metadata, code and queries](https://docs.keyfire.ru/edt-bridge/tools/read-metadata/) | Metadata objects and their structure, role rights, the command interface, references and calls, module text, search, query validation. |
| <a id="managed-form-preview"></a>[Read: forms and templates](https://docs.keyfire.ru/edt-bridge/tools/read-forms/) | Form structure and rendering, pictures, HTML, data composition and spreadsheet templates. |
| <a id="write"></a><a id="register-fields"></a>[Write: metadata objects](https://docs.keyfire.ru/edt-bridge/tools/write-objects/) | Create, delete and rename objects; attributes and register fields; common-module flags, subsystems, role rights, the command interface, adoption into an extension. |
| <a id="replace-an-existing-bsl-method"></a>[Write: modules](https://docs.keyfire.ru/edt-bridge/tools/write-modules/) | Add, replace, move and delete methods; module fragments; HTTP service routes; quick fixes. |
| <a id="placement-properties-and-validation-snapshots"></a>[Write: forms](https://docs.keyfire.ru/edt-bridge/tools/write-forms/) | Forms, their attributes, commands, handlers and items; audit and repair of duplicate IDs. |
| <a id="html-source-form-ids-and-validation"></a>[Write: templates](https://docs.keyfire.ru/edt-bridge/tools/write-templates/) | HTML templates, data composition schemas, spreadsheet templates. |
| [Write: projects and builds](https://docs.keyfire.ru/edt-bridge/tools/write-projects/) | Extension and external object projects, import, cleaning and deletion; `.cfe`, `.epf` and `.erf` builds; EDT applications. |
| <a id="infobases-the-cluster-and-the-platform"></a><a id="check-platform-build-readiness"></a><a id="incremental-configuration-loading"></a>[Infobases, the cluster and the platform](https://docs.keyfire.ru/edt-bridge/tools/infobases/) | Infobase update and dump, configurator agents, extensions in an infobase, cluster sessions, platform installations. |
| <a id="debug"></a>[Debug](https://docs.keyfire.ru/edt-bridge/tools/debug/) | Attach to a debug server, inspect threads and variables, step through code, evaluate expressions. |

## Plan and apply

Write tools resolve targets through EDT. Metadata operations export the model; source replacement
tools write the resolved workspace file. Apply requires a configured token. `apply=false` returns
a plan by default.

## Served by the wrapper

Three tools are served by `edt-bridge-mcp` itself. The wrapper lists them alongside the bridge
tools and answers without forwarding the call to EDT. `edt_open_gui` acts on the EDT that
hosts the bridge, and a tool cannot report on the process it has just ended.
`edt_test_form` and `edt_unit_tests` run the platform's client and need no EDT.

| Wrapper tool | What it does |
|--------------|--------------|
| `edt_open_gui` | Transfers the workspace to the GUI EDT. It stops the headless EDT, waits for its processes to exit, and opens EDT on the same workspace. The port closes before the runtime exits, so the processes must be allowed to finish. `force` terminates processes that do not stop in time, including the parent keepalive shell. The bridge becomes available once GUI EDT loads the plugin. |
| `edt_test_form` | Runs the main form of a built .epf or .erf in a fresh copy of the test infobase named by `EDT_BRIDGE_TEST_INFOBASE` through the platform's native test client (Linux with Xvfb): presses one command or plays a scenario and returns the messages, the error dialog and the exception text; the `read_form` step returns every visible field, form table and spreadsheet of the form; `assert_messages` counts the messages the preceding click added, so a repeated click is checked again. The infobase itself is never changed. Served by the wrapper; no EDT needed. |
| `edt_unit_tests` | Runs the YAXUnit tests of built extensions (.cfe) in a fresh copy of the test infobase named by `EDT_BRIDGE_TEST_INFOBASE` (Linux with Xvfb; YAXUnit loaded in the infobase): loads the extensions into the copy, runs their tests narrowed by module or test and returns every test with its status, time, message, expected and actual values and the lines of the tested code it failed on, plus the errors of the event log. The infobase itself is never changed. Served by the wrapper; no EDT needed. [YAXUnit tests](/native-forms/unit-tests) |

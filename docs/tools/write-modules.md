---
title: "Write: modules"
description: "Write tools for BSL modules: add, replace, move and delete methods, replace module fragments, add HTTP service routes and apply quick fixes."
sidebar:
  label: "Write: modules"
  order: 5
---

These tools change BSL modules: they add, replace, move and delete methods, replace
fragments outside methods, add HTTP service routes and apply EDT quick fixes.

Changes need a token and are returned as a plan until `apply=true` is passed; see
[Plan and apply](/tools#plan-and-apply).

| Write tool | What it does |
|------------|--------------|
| `edt_add_method` · `edt_delete_method` | Adds and deletes a procedure or function in a module's BSL, both guided by the model. The insert refuses any result that would not parse again. The cut takes the adjacent doc comment with it, and its plan returns the exact text that will go, which is why it needs `force`. Both address a module by FQN, `HTTPService.X` and `WebService.X` included. |
| `edt_add_route` | Adds a route to an `HTTPService`: a URL template plus one HTTP method. It generates the `uuid` of the template and its method. It resolves the `httpMethod` enum, and with `createHandler` it splices a `Функция <handler>(Запрос)` stub into the service module. |
| `edt_apply_fix` | Applies EDT's own quick fix to one marker of a check (`checkId` as `edt_project_errors` reports it, narrowed by `fqn` and `line`). A dry-run lists the variants; apply runs one, validates again and returns the changed lines and `remaining`. `hasFix=false` means EDT has no quick fix for the check. A module fix needs client EDT: EDT applies it through the module editor, and a headless bridge refuses with `requiresWorkbench=true`. In client EDT the bridge saves that editor (`editorSaved`) and refuses when the editor already holds unsaved changes. |
| `edt_replace_method` | Replaces one existing method after parsing it with EDT. Dry-run returns exact oldText/newText and the whole-module sourceHash. Apply requires that hash and force=true; stale source, unsaved editors and invalid replacements are refused. The method name and kind stay the same, and surrounding source is preserved. |
| `edt_replace_module_fragment` | Replaces an exact fragment of a module outside methods: compilation directives, module variables, regions, a `#If` block. The fragment must occur once; `edits` applies several fragments together, so a region can open and close in one call. The result must parse. Preview returns `sourceHash`, apply needs it back as `expectedHash` and a token. |
| `edt_move_method` | Moves a method within its module, preserving its exact text, documentation and directives. Supports nested region paths and neighboring methods, checks the intermediate and final module with the EDT parser, and requires `apply=true, force=true`. |

## Method placement

`edt_add_method` accepts one of `beforeMethod` and `afterMethod`. `region` accepts a full nested
path such as `Public/Helpers`; a short name must be unique. A neighbor must belong directly to the
selected scope. A missing or ambiguous target is refused. The plan returns `insertLine`,
`previousMethod` and `nextMethod`. `edt_move_method` uses the same destination options.
For the first method of an existing empty model module, pass its `fqn`. The bridge creates the
missing `.bsl` only on apply after checking the owner and module type; dry-run creates no file.
This applies to any typed module of an existing object, such as `ObjectModule` and
`ManagerModule` of a catalog or data processor, as well as to common and form modules.
An arbitrary missing `modulePath` is refused.

## Replace an existing BSL method

Use `edt_replace_method` to change a method's body, parameters, export flag or attached
comments and directives in one operation. The name and kind must remain the same.
Use `edt_rename` to rename a method and update its callers.

1. Read the source with `edt_module_text`. When preparing the full replacement, include
   the method's documentation, compilation directives and trailing comment. A targeted
   read may omit attachments; the dry-run's `oldText` shows the full range being replaced.
2. Check `edt_support_status` for the owner and save any open module editor.
3. Call `edt_replace_method` without `apply`. Review `oldText` and `newText` and keep
   `sourceHash`. The replacement contains exactly one method, without surrounding
   regions, preprocessor blocks, variables or statements at module level.
4. Apply the same replacement with `apply=true`, `force=true` and `expectedHash` set
   to that `sourceHash`. A change anywhere in the module requires a new dry-run.
5. Read the saved source and run `edt_project_errors`. Parsing checks syntax; the tool
   does not rewrite callers after a parameter change or prove runtime behavior.

Preview:

```json
{
  "projectName": "Demo",
  "fqn": "CommonModule.Api",
  "methodName": "Calculate",
  "methodText": "Function Calculate(Value) Export\n    Return Value + 1;\nEndFunction"
}
```

Apply with the hash returned by that preview:

```json
{
  "projectName": "Demo",
  "fqn": "CommonModule.Api",
  "methodName": "Calculate",
  "methodText": "Function Calculate(Value) Export\n    Return Value + 1;\nEndFunction",
  "expectedHash": "<sourceHash from the dry-run>",
  "apply": true,
  "force": true
}
```

`oldText` and `newText` include attachments: omitting an old comment or directive from
`methodText` removes it. `sourceHash` covers the entire UTF-8 module, including its BOM;
`resultHash` identifies the proposed or saved result. The replacement adopts the source's
line endings. Other source text and its BOM are retained. Invalid UTF-8 and malformed
Unicode are refused. An identical replacement returns `unchanged=true` without writing.
A missing or ambiguous method, a dirty editor or a parse error in the source also prevents
a write. This operation does not create a missing module.

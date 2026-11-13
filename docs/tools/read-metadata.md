---
title: "Read: metadata, code and queries"
description: "Read tools for metadata objects, role rights, the command interface, references and calls, module text, search and query validation."
sidebar:
  label: "Read: metadata and code"
  order: 2
---

These tools read the metadata objects of a project, role rights and the command interface,
find references and calls, return module text, search modules and queries, and validate
query text.

| Tool | What it returns |
|------|-----------------|
| `edt_metadata_objects` · `edt_metadata_details` | Top-level metadata objects, optionally filtered by type (`Catalog`, `Document`, ...) and by a name substring. Then one object's core properties and its structure: attributes, tabular sections, forms, commands, templates, dimensions, resources, enum values, each attribute with its value type. Common attributes include usage composition, auto-use and separator settings; event subscriptions include source types, event and handler. Scheduled jobs include `use`, `methodName`, `predefined`, `key`, `restartCountOnFailure` and `restartIntervalOnFailure` in `properties`, including model defaults. Values are strings; these describe metadata, not the current scheduler state. |
| `edt_role_rights` | The rights of a role on one object, in the order EDT shows them: the English and Russian name, the value, whether the role sets it itself, and the conditions of a data access restriction. `value` is null when the base configuration decides, as for an adopted role in an extension. Without `object`, lists the objects the role has rights on and the role's flags. |
| `edt_command_interface` | The command interface of a subsystem section as EDT derives it: the groups of the navigation and actions panels, the commands in the order shown, their visibility in common and by role, and what was customized. Commands and groups carry the names `CommandInterface.cmi` uses, such as `Catalog.Products.StandardCommand.OpenList` and `NavigationPanelImportant`. |
| `edt_find_references` · `edt_outgoing_calls` · `edt_outgoing_structures` | Return incoming references and outgoing calls. Metadata references come from EDT's cross-reference index; `method` selects BSL call sites of `CommonModule.X.method`. Outgoing calls list the methods called by a module, method, or form one level out, with call-site counts and an ExtAPI layer flag. The third tool extracts the recognized top-level keys of the `Структура` passed to each qualified outgoing call. Method search also accepts literal object-manager calls such as `Catalogs.Items.Method(...)`; `moduleType` does not narrow this search. Metadata mode also reports `queryReferences` from dynamic-list and DCS dataset queries; `queryCoverageComplete` and errors report the limits of that separate scan. Method mode searches BSL call sites. |
| `edt_module_text` · `edt_go_to_definition` · `edt_symbol_info` | Return BSL module text or one method, addressed by FQN or modulePath; a whole-module read also lists the procedures and functions with signatures, a method read does so only with `includeMethods=true`. Also return a symbol's definition at a position, including kind, name, owning object, and location, or the element and computed expression value types at a position. |
| `edt_search_modules` | Full-text search across a project's BSL modules, by substring or regular expression, with an optional path filter. It reads through Eclipse's file buffers, so a module open in an editor is searched as it stands right now, unsaved edits included. `edt_find_references` answers "who calls this method"; this one answers "where does this text appear". Regular expressions use Unicode word classes and case folding. Texts in `Form.form` and `Template.dcs` are outside the BSL search. |
| `edt_search_queries` | Searches the query fields of dynamic lists in `Form.form` and datasets in `Template.dcs`, by text or Unicode regular expression. Returns the path, form/attribute or template/dataset, query line and XML field line. Parse errors and truncation set `complete=false`. |
| `edt_validate_query` | Checks a 1C query against the project's live metadata: syntax and semantics, so unknown tables and fields and type errors come back with positions. Takes the query text, or a module address (`modulePath`, optionally one `method`). Given a module, the bridge pulls the `\|`-framed query literals out of the live module itself and checks each one. The batch is followed as a whole too, which EDT does not do. A query that reads a temporary table no earlier query puts there (`ПОМЕСТИТЬ`), or one an earlier query has dropped, makes the text invalid. For a module literal that is an `INFO` note instead, because the table may come from a shared temporary table manager or from another literal. |

Dynamic-list queries are first parsed with the EDT Ql language. A syntax error triggers a second
parse with QlDcs, which supports data-composition extensions such as `{WHERE ...}` and
`{LEFT JOIN ...}`. If parsing still fails, `queryFailures` reports the failure and
`queryCoverageComplete` remains false. Use `edt_search_queries` to search those query fields by text.

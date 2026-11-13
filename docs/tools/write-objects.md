---
title: "Write: metadata objects"
description: "Write tools for metadata objects: create, delete, rename, attributes and register fields, common modules, subsystems, role rights and the command interface."
sidebar:
  label: "Write: metadata objects"
  order: 4
---

These tools create, change, rename and delete metadata objects and their attributes, set
common-module properties, subsystem contents, role rights and the command interface, and
adopt objects into an extension.

Changes need a token and are returned as a plan until `apply=true` is passed; see
[Plan and apply](/tools#plan-and-apply).

| Write tool | What it does |
|------------|--------------|
| `edt_create_object` · `edt_delete_object` | Creates a new top-level object – Catalog, Document, Enum, InformationRegister and the rest – through EDT's factory and the per-type initializer, and registers it in the Configuration. Deletion uses EDT refactoring and requires `force`. The preview reports indexed references and possible uses in BSL, query text and form bindings. These uses can remain after deletion; the response does not promise a complete cascade. |
| `edt_add_attribute` · `edt_modify_attribute` · `edt_remove_attribute` | Adds or changes an attribute, register resource or dimension (`kind`, default `attribute`). Addition checks the name across all three field lists. Changes support the type, Russian synonym and comment. `edt_remove_attribute` removes attributes; use `edt_delete_object` with the member FQN for resources and dimensions. Optional `use=ForFolder\|ForItem\|ForFolderAndItem` applies to hierarchical fields; `multiLine` controls multiline input. Invalid or inapplicable options are rejected before writing. Modification preserves the field UUID; `properties` reports the actual values. |
| `edt_rename` | Renames a method, metadata object or member and updates references through EDT's refactoring engine. The preview reports known affected files and the completeness of file and edit counts. Requires `force` because external uses of the old name may break. |
| `edt_adopt_object` | Adopts an object of the base configuration into an extension project through EDT's own `IModelObjectAdopter`. Nothing in an extension can intercept that object until this happens, and it is the step that completes `edt_create_extension`. |
| `edt_modify_object` · `edt_update_subsystem` | Sets common-module execution flags and result reuse, or adds/removes existing objects from a subsystem. Both validate the whole request before writing and show a plan. Removing members additionally requires `force=true`. `extendModule=true` enables an adopted common module for extension. |
| `edt_modify_role_rights` | Sets rights of a role on one object the way the rights editor of EDT does: a value equal to the role's default removes the explicit entry. Exports the role and its `Rights.rights`. An unknown right is refused with the rights the object has, and a right with a data access restriction is never changed. In an extension the role and the object must be the extension's own or adopted. Dry-run by default; apply needs a token. |
| `edt_modify_command_interface` | Changes `CommandInterface.cmi` of a subsystem section: the visibility of a command in common and by role, its group within its panel, the order of a group, or a return to the default. Takes the names `edt_command_interface` prints. An adopted subsystem without its own `CommandInterface.cmi` gets one. Dry-run by default; apply needs a token. |

## Deleting an object

`edt_delete_object` separates EDT's indexed references from lexical candidates in `referenceAudit`.
`scanComplete` describes the stated file scan; `complete=false` keeps semantic coverage explicit.
Check `countIsLowerBound`, errors and samples before deciding to force deletion. After apply,
`deletionConfirmed` confirms target absence in the live BM model. `remainingPossibleReferences`
and `retainedFormControls` report the observed remaining uses and controls with cleared data paths;
the response states the limits of the export wait and after-audit.

## Register fields

`edt_add_attribute` and `edt_modify_attribute` accept `kind`: `attribute` (default),
`resource` or `dimension`. Resources and dimensions require an owner that supports
that field list. The operations support information, accumulation, accounting and
calculation registers. Existing calls without `kind` continue to address attributes.

Addition checks names case-insensitively across the owner's `attributes`, `resources`
and `dimensions`, including a second check in the write transaction. A collision returns
`nameAvailable=false` and the exact `conflictingFqns`. The dry-run writes nothing.

For accumulation, accounting and calculation register resources, use one explicit
`Число` type, for example `Число(15,2)`. Non-numeric, composite, reference and defined
types are refused for these resources before writing. Information-register resources
retain the general type syntax. An explicit `kind` selects that list even if an old
configuration already contains the same name in another list; duplicate names inside
the selected list are refused.

For example, preview a resource addition:

```json
{
  "projectName": "Demo",
  "ownerFqn": "InformationRegister.Versions",
  "kind": "resource",
  "name": "Version",
  "type": "Строка(20)"
}
```

To change that field, call `edt_modify_attribute` with the same owner, name and kind,
and at least one of `newType`, `newSynonymRu` or `newComment`. An empty synonym or
comment clears it; omitting a property keeps it. Changing the type returns a warning
about data and code compatibility. Both operations require `apply=true` to write.
The response distinguishes an in-memory commit from an accepted export request;
acceptance does not confirm that serialization has finished.

`edt_remove_attribute` continues to remove attributes only. If a modify/remove call
addresses a resource or dimension as an attribute, the response names the actual
member FQN and suggests `kind`, `edt_delete_object` and `edt_rename`. Delete or rename
those members through the latter tools, using `targetFqn`, for example
`InformationRegister.Versions.Resource.Version`.

## Renaming

`edt_rename` accepts `method="CommonModule.Api.Run"`, or `methodName="Run"` with an owner FQN
or `modulePath`. It uses EDT's method refactoring. The preview lists `affectedFiles` with change
and edit counts before `apply=true, force=true`. Save affected editors before applying a rename.
`nativeTextEditCount` counts the text edits exposed by EDT. Model changes can have
`editCount=null` and `editCountComplete=false`; `changeCount` still reports the operations.
Metadata mode also returns `targetPath`. Some metadata refactoring items expose no file mapping:
`coverageComplete=false`, `unmappedItemCount` and `unmappedItems` identify that partial preview.
The known file list is then a subset; inspect the native `items` before applying.

## Common modules and subsystems

For a new common module, `edt_create_object` accepts a `properties` object. The same object goes
to `edt_modify_object` for an existing module: `global`, `server`, `externalConnection`,
`clientManagedApplication`, `clientOrdinaryApplication`, `serverCall` and `privileged` are booleans;
`returnValuesReuse` is `DontUse`, `DuringRequest` or `DuringSession`. Adopted modules retain the
base configuration's execution flags. Pass `extendModule=true` to `edt_adopt_object` or
`edt_modify_object` to enable extending an adopted common module without replacing its body.
`edt_update_subsystem` takes `fqn` and arrays of object FQNs in `add` and `remove`.
Creating and adopting objects preserve an already alphabetic Configuration list, ignoring case.
A list in a custom order keeps that order and receives a new object at the end.

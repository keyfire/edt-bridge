---
title: "Write: forms"
description: "Write tools for managed forms: attributes, commands, event handlers and items, item placement and properties, and repair of duplicate IDs."
sidebar:
  label: "Write: forms"
  order: 6
---

These tools add managed forms and change their attributes, commands, event handlers and
items. `edt_form_identifiers` audits the visual IDs of a form and renumbers duplicates.

Changes need a token and are returned as a plan until `apply=true` is passed; see
[Plan and apply](/tools#plan-and-apply).

| Write tool | What it does |
|------------|--------------|
| `edt_add_form` | Adds a managed form to a metadata object through EDT's own form generator, the engine behind the "New form" wizard. The generator creates the form, its items and its module. With `defaultForm=true`, the form kind selects its own default slot, for example `defaultObjectForm` or `defaultFolderForm` for a catalog. Unsupported slots fail during preflight. `defaultForm` confirms an applied assignment; `defaultFormProperty` names its slot. |
| `edt_add_form_attribute` · `edt_modify_form_attribute` · `edt_remove_form_attribute` | Adds, changes and removes a form attribute, or a column of a value-table attribute when you pass `columnOf`. Ids come from EDT's form identifier service. Besides the metadata type grammar these take the platform types a form may hold (`ТаблицаЗначений`, `СписокЗначений` and so on) and the object types `ВнешняяОбработкаОбъект.X` and `СправочникОбъект.X`, which can be used for the main attribute. Removal lists the items bound to the attribute and needs `force`. |
| `edt_add_form_command` · `edt_modify_form_command` · `edt_remove_form_command` | Adds, changes and removes a form command. Adding can write the handler procedure's stub into the form module as well, creating that module when the form has none. Removal lists the buttons wired to the command and needs `force`. |
| `edt_add_form_handler` | Registers an event handler on a form or on one of its items. This creates the `handlers` entry in `Form.form`. Without it, the platform does not call the procedure and validation does not report an error. The allowed events come from EDT itself, so a misspelled one is refused with the list attached. It can also write the stub, with the signature the event declares and the directive its environments imply. |
| `edt_add_form_item` · `edt_modify_form_item` · `edt_remove_form_item` | Adds, changes and removes a form's visual items: field, table, button, group, decoration. It goes through EDT's own `IFormItemManagementService`, the service the form editor calls. A table bound to a value-table attribute gets its columns filled in automatically. A `titleRu` given to the table stays on the table, and the generated columns keep EDT's own default. The `newName` parameter renames the item in the form model while preserving handler procedure names. Removal takes everything nested inside and needs `force`. |
| `edt_form_identifiers` | Full-containment audit of visual IDs, including tooltips, menus and command bars. Returns duplicate occurrence paths and a snapshot hash without allocating IDs. Attributes and commands have separate namespaces. With explicit `itemPaths`, previews selected duplicate occurrences to renumber. Apply needs the current `expectedHash` and token. Unselected IDs, object data and order are preserved. Reserved IDs, ambiguous numeric references and any open matching form editor prevent repair. |
| `edt_move_form_item` | Moves an existing form item by sibling index or anchor while preserving identity, id, children, properties and events. Refuses cycles and moves that require converting the item kind or data binding. |

## Item placement and choice lists

`edt_add_form_item` and `edt_move_form_item` accept exactly one placement selector: `index`
(zero-based), `beforeItem` or `afterItem`. A move calculates the index after excluding the moved
item. An omitted `parent` keeps its parent; `parent=""` selects the form root. Add and modify
support `groupDirection` (alias `group`), `representation`, `showTitle`, `united`, `titleLocation`,
`maxHeight` and `verticalStretch` where the item type supports them. Unknown enums and settings
for the wrong item type are refused. `fieldType` selects `InputField`, `RadioButtonField` or
`CheckBoxField`. `choiceList` replaces the choices of an input or radio field:

```json
{
  "choiceList": [
    {
      "value": "all",
      "titleRu": "Все"
    },
    {
      "value": "selected",
      "titleRu": "Выбранные"
    }
  ]
}
```

Choice values can be strings, numbers or booleans; references and enum values are not supported.
An empty list clears the choices. `edt_form_structure` returns item IDs and properties for checking
the result. A dynamic-list attribute also returns `customQuery`, `mainTable` and `queryText` under
`dynamicList`. `queryLimit` bounds each returned query (default 20000, maximum 100000 characters);
`queryTextTruncated` reports omitted text.

## Managed-form properties

For `kind=decoration`, `edt_add_form_item` accepts `decorationType=Label` or
`decorationType=Picture`. When omitted, the tool selects `Picture` if the parent container
allows it, otherwise it uses EDT's default for that container. A dry run checks the container and
returns `properties.decorationType`. Apply creates the decoration through EDT with matching `extInfo`.

`edt_add_form_item` and `edt_modify_form_item` accept `fieldType=HTMLDocumentField`.
EDT initializes its `HtmlFieldExtInfo` and preserves the data path when changing the field kind.
`edt_add_form_handler` resolves its `OnClick` event (`ПриНажатии`) and the native handler signature.

- Fields support `width`, `height`, `horizontalStretch` and `verticalStretch` when present on their ext-info.
- Input fields also accept `choiceButton`, `clearButton`, `textEdit` and `listChoiceMode`. A `choiceList` alone does not restrict typed values; use `listChoiceMode=true` for a list-only field.
- `choiceFoldersAndItems` sets what an input field bound to a hierarchical reference lets the user choose: `Auto`, `Items`, `Folders` or `FoldersAndItems`. Other field kinds refuse it; `edt_form_structure` reports the current value.
- Tables accept `changeRowSet`, `autoInsertNewRow`, `changeRowOrder` and `readOnly`. Table columns support their field properties, including `width` and `readOnly`.
- `edt_form_structure` includes the native `AutoCommandBar` and its buttons. Use its returned name as `parent` when adding or moving a button. The structural bar itself cannot be moved or deleted.

The `properties` map contains the observed design-time values after apply. Runtime code may override them.
Invalid options are validated as a set before any setting is assigned. Unlimited `Строка` types include an explicit empty string qualifier.

## Duplicate form IDs

For duplicate form IDs, audit first and select paths from `audit.items`. Leave one
occurrence of each duplicate ID unselected. Preview those `itemPaths`, close the matching
form editor, then apply with its current `expectedHash`. The tool resets native allocator
caches, as does adding items, attributes and commands. `exportAccepted` confirms acceptance
of the export request; check saved `Form.form` and validation for the persistent result.
Audit and preview do not require a write token.

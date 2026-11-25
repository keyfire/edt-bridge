---
title: "Write: templates"
description: "Write tools for templates: HTML and text templates, data composition schemas and spreadsheet documents."
sidebar:
  label: "Write: templates"
  order: 7
---

These tools create templates and replace their content: HTML and text templates, data
composition schemas and spreadsheet documents.

Changes need a token and are returned as a plan until `apply=true` is passed; see
[Plan and apply](/tools#plan-and-apply).

| Write tool | What it does |
|------------|--------------|
| `edt_replace_html_template` | Replaces the exact complete source of one existing HTML locale. Preview returns old/new text and hashes. Apply requires `expectedHash`, `force=true`, `apply=true` and a token. Dirty editors, changed locale mappings, stale/read-only resources and malformed UTF-8 refuse the write. Other locales are preserved. When another EDT operation holds the project, for example an infobase update waiting for an answer, apply writes nothing and returns `busy=true`; repeat the call after that operation completes. |
| `edt_add_template` | Creates a template on a report, external report, data processor or other object: a data composition schema with an empty schema, or with `templateType=SpreadsheetDocument` an empty print form layout; `setMainSchema` makes it the report's main schema, so the client opens the report with the configuration's common report form. `templateType=HTMLDocument` creates an HTML template with one page and `TextDocument` a text template; both take the starting text from `text`. Dry-run by default; apply needs a token, waits until EDT has written the content file and returns its `sourceHash` for the first write. |
| `edt_write_spreadsheet` | Replaces the whole template with the given columns and areas. A template with features the description does not carry is refused unless `dropUnsupported=true`. Preview returns the change and `sourceHash`; apply needs it back as `expectedHash` and a token. |
| `edt_replace_dcs` | Replaces the complete schema source; EDT reloads the schema from the file. Preview returns old/new text and hashes; apply requires `expectedHash`, `force=true` and a token. Right after `edt_add_template`, a schema file that EDT has not written yet is awaited up to 15 s. Preview and apply check the schema first. A text EDT cannot read is refused with the line and column where its reader stops: EDT would keep the previous schema and its problems, and build that one. `problems` lists a data set without a data source and a settings variant without structure, and `valid` is false while there is an error. After apply, `revalidated` says whether EDT has taken the schema and checked it. The rest of the platform format is checked by the build, `edt_dump_external_object`. |
| `edt_modify_dcs` | Changes a part of a schema without sending the whole XML. `edits`: `addField` (`dataSet`, `dataPath`, `titleRu`), `addParameter` (`name`, `titleRu`, `type`: String(N), Number(D,F), Date, DateTime, Boolean, StandardPeriod), `setQuery` (`dataSet`, `query`), `replaceFragment` (`fragment`, `replacement`: an exact piece of the text `edt_read_dcs` returns, refused when it matches no place or several), `setField` (`dataSet`, `dataPath`, `titleRu` and/or `format`, e.g. `ЧДЦ=2`), `removeField` (`dataSet`, `dataPath`). The rest of the file keeps its bytes; a duplicate is refused. The answer names the changed lines and `resultHash`, not the schema text. Apply takes `expectedHash` = `sourceHash` of `edt_read_dcs` or of the dry-run. The changed schema is checked as `edt_replace_dcs` checks it: a text EDT cannot read is not written, and `problems` lists a data set without a data source and a variant without structure. |

## Creating an HTML or a text template

`edt_add_template` with `templateType=HTMLDocument` (alias `HTMLДокумент`) creates an HTML
template with one page. The page is in the language EDT edits the project in, reported in
`language`. EDT keeps the list of pages in `Template.htmldoc` and each page in a file named
after its language, such as `ru.html`. `TextDocument` (alias `ТекстовыйДокумент`) creates a
text template, whose content is the file `Template.txt`.

`text` is the starting text of the page or of the text template, written exactly as given. It
is empty when omitted, and a schema or a spreadsheet refuses it. After `apply=true`,
`contentPath` names the file. For an HTML template, `sourceHash` is the `expectedHash` of the
first `edt_replace_html_template`. A text template has no tool of its own to change it: its text
is the file that `contentPath` names, and EDT reads it as it is. Other pages of an HTML template
and common templates are not created by this tool.

For example, a help page of a data processor:

```json
{
  "projectName": "Demo",
  "ownerFqn": "DataProcessor.Subscriptions",
  "name": "Help",
  "templateType": "HTMLDocument",
  "text": "<html><body><p>How to fill in the request</p></body></html>"
}
```

## Replacing an HTML template

Read the requested HTML locale before replacing it. The hash covers exact UTF-8 bytes:
a physical BOM is represented by a leading U+FEFF character in `text`. Include that
character to retain the BOM. Line endings and trailing whitespace are written as supplied.
An empty string deliberately empties the locale; omitted text is refused. A no-op writes
nothing. Save affected template editors before replacement.

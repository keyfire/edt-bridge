---
title: "Write: templates"
description: "Write tools for templates: HTML templates, data composition schemas and spreadsheet documents."
sidebar:
  label: "Write: templates"
  order: 7
---

These tools create templates and replace their content: HTML templates, data composition
schemas and spreadsheet documents.

Changes need a token and are returned as a plan until `apply=true` is passed; see
[Plan and apply](/tools#plan-and-apply).

| Write tool | What it does |
|------------|--------------|
| `edt_replace_html_template` | Replaces the exact complete source of one existing HTML locale. Preview returns old/new text and hashes. Apply requires `expectedHash`, `force=true`, `apply=true` and a token. Dirty editors, changed locale mappings, stale/read-only resources and malformed UTF-8 refuse the write. Other locales are preserved. When another EDT operation holds the project, for example an infobase update waiting for an answer, apply writes nothing and returns `busy=true`; repeat the call after that operation completes. |
| `edt_add_template` | Creates a template on a report, external report, data processor or other object: a data composition schema with an empty schema, or with `templateType=SpreadsheetDocument` an empty print form layout; `setMainSchema` makes it the report's main schema, so the client opens the report with the configuration's common report form. Dry-run by default; apply needs a token, waits until EDT has written the content file and returns its `sourceHash` for the first write. |
| `edt_write_spreadsheet` | Replaces the whole template with the given columns and areas. A template with features the description does not carry is refused unless `dropUnsupported=true`. Preview returns the change and `sourceHash`; apply needs it back as `expectedHash` and a token. |
| `edt_replace_dcs` | Replaces the complete schema source; EDT reloads the schema from the file. Preview returns old/new text and hashes; apply requires `expectedHash`, `force=true` and a token. Right after `edt_add_template`, a schema file that EDT has not written yet is awaited up to 15 s. EDT validation does not check the schema XML: build with `edt_dump_external_object` to check it. |
| `edt_modify_dcs` | Adds fields to a data set and parameters to a schema without sending the whole XML: `edits` of `addField` (`dataSet`, `dataPath`, `titleRu`) and `addParameter` (`name`, `titleRu`, `type`: String(N), Number(D,F), Date, DateTime, Boolean, StandardPeriod). The rest of the file keeps its bytes; a duplicate is refused. Apply needs the dry-run `sourceHash`. EDT validation does not check the schema XML: build with `edt_dump_external_object` to check it. |

## Replacing an HTML template

Read the requested HTML locale before replacing it. The hash covers exact UTF-8 bytes:
a physical BOM is represented by a leading U+FEFF character in `text`. Include that
character to retain the BOM. Line endings and trailing whitespace are written as supplied.
An empty string deliberately empties the locale; omitted text is refused. A no-op writes
nothing. Save affected template editors before replacement.

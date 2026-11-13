---
title: "Read: forms and templates"
description: "Read tools for managed forms, pictures, HTML templates, data composition schemas and spreadsheet templates."
sidebar:
  label: "Read: forms and templates"
  order: 3
---

These tools read managed forms, pictures and templates: HTML, data composition schemas and
spreadsheet documents. Two of them render a form or a spreadsheet template to PNG.

| Tool | What it returns |
|------|-----------------|
| `edt_form_structure` · `edt_form_render` · `edt_picture_export` | Return a managed form's structure, its image, and the content of a configuration picture. The structure includes a tree of fields, groups, tables, buttons, and decorations. Items include data bindings, static visible/enabled/readOnly, event handlers, input-field properties, command-to-button links, and conditional appearance. Form attributes, commands, parameters, and handlers are also returned. EDT's offscreen renderer renders the form to PNG with a choice of interface variant and theme. For CommonPicture, the content of Picture.zip is returned. |
| `edt_read_html_template` | Exact UTF-8 source and `sourceHash` for one existing common or nested HTML-template locale. Requires explicit `templateFqn` and `language`; returns available languages and the model-resolved file path. |
| `edt_read_dcs` | Reads the exact source of a data composition schema template - the schema's designer XML - with the path and `sourceHash`. |
| `edt_read_spreadsheet` | Reads a spreadsheet template - a print form layout - as column widths and named row areas of cells: `text`, `parameter` or `template`, with `bold`, `fontSize`, `align`, `border`, `colspan` and `wrap`. The answer is the input of `edt_write_spreadsheet`; `unsupported` names what the file also has and the description does not carry, such as pictures and colors. |
| `edt_spreadsheet_render` | Draws the template to a PNG the way the template editor shows it: column widths, text, `<Parameter>` for a parameter, bold, alignment, borders, merged cells and area names. Needs no platform and no display. |

`edt_form_identifiers` audits the visual IDs of a form without changing it; it is described with
the [form write tools](/tools/write-forms).

## Managed-form preview

`edt_form_render` uses the native EDT renderer for `TAXI` and `VERSION8_5`. Enable native
rendering and buffered image capture with the JVM options `-DnativeFormLayoutRender=true` and
`-DnativeFormBufferedLayoutRender=true`. Headless EDT with GTK also needs a working graphical
session, for example an X server provided by Xvfb.
When passing extra JVM options to the GUI launcher `1cedt`, use `--launcher.appendVmargs`
to preserve the arguments from the product INI file.

Use `ok=true`, `pngPath` and a positive `fileBytes` to verify that the call produced an image.
The result's `diagnostics` can report the SWT platform, display initialization, renderer settings
and render stage. If EDT returns no image, see `.metadata/.log` in the EDT workspace or use the
form preview in GUI EDT. Native worker crashes can also produce `hs_err_pid*.log` in the EDT
working directory.

With EDT 2026.2.2 and platform 8.5.1.1529, Windows headless EDT with SWT win32 produced PNGs
for both variants. In the tested Linux GTK/Xvfb profile, both GUI and headless EDT reached a
native SCom worker that crashed during initialization. That profile did not produce a preview.
`edt-bridge-mcp test-form` runs the main form of a compiled EPF or ERF, or a document/catalog
form selected by an internal navigation link, in an explicitly selected file test infobase.
JSON scenarios check field presentations, spreadsheet contents, and message counts; `reopen`
checks the same target in a new session. See [native form checks](https://docs.keyfire.ru/edt-bridge/native-forms/) for
environment preparation, examples, and limits.

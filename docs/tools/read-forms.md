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
| `edt_form_structure` · `edt_form_render` · `edt_picture_export` | Return a managed form's structure, its image, and the content of a configuration picture. The structure includes a tree of fields, groups, tables, buttons, and decorations. Items include data bindings, static visible/enabled/readOnly, event handlers, input-field properties, command-to-button links, and conditional appearance. Form attributes, commands, parameters, and handlers are also returned; a command carries its caption in `title` and its tooltip in `toolTip`. The form is drawn to PNG the way the 1C client shows it, in the "Version 8.5" or Taxi interface, light or dark theme, any page, with sample data; EDT's form editor preview is still available. For CommonPicture, the content of Picture.zip is returned. |
| `edt_read_html_template` | Exact UTF-8 source and `sourceHash` for one existing common or nested HTML-template locale. Requires explicit `templateFqn` and `language`; returns available languages and the model-resolved file path. |
| `edt_read_dcs` | Reads the exact source of a data composition schema template - the schema's designer XML - with the path and `sourceHash`. |
| `edt_read_spreadsheet` | Reads a spreadsheet template - a print form layout - as column widths and named row areas of cells: `text`, `parameter` or `template`, with `bold`, `fontSize`, `align`, `border`, `colspan` and `wrap`. The answer is the input of `edt_write_spreadsheet`; `unsupported` names what the file also has and the description does not carry, such as pictures and colors. |
| `edt_spreadsheet_render` | Draws the template to a PNG the way the template editor shows it: column widths, text, `<Parameter>` for a parameter, bold, alignment, borders, merged cells and area names. Needs no platform and no display. |

`edt_form_identifiers` audits the visual IDs of a form without changing it; it is described with
the [form write tools](/tools/write-forms).

## Managed-form preview

`edt_form_render` draws a form the way the 1C client shows it (`engine="client"`, the default). The
bridge lays the form out with EDT's own engine and hands the result over; the `edt-bridge-mcp`
wrapper draws it as an HTML page and saves a PNG made by Chromium. Arguments:

- `interface` – `v85` ("Version 8.5", the default) or `taxi`; `theme` – `light` or `dark`. The
  platform has no dark theme for Taxi, and the call refuses that pair.
- `page` – the page to show, by name or title; the answer lists the pages of the form in `pages`.
- `data` – sample data: `title` for the window, `values` by item name or data path (`2` is the
  third state of a check box), `tables` with `rows` (lists in column order or objects by column
  name; `_level`, `_open`, `_check` for trees and marks) and `current`, `hidden` and `shown` for
  items whose visibility the form code changes, `messages` for important messages the form code
  shows (`text`, `type` – `info`, `warning`, `success` or `error`, and `group`, the form group the
  message goes into; without it the message stands under the header).
- `density` – `auto` (the form's own scale variant), `normal` or `compact`. The client may also
  choose compact for a form whose scale variant is Auto; that choice is made by the server and is
  not in the form, so pass `density="compact"` for such a form.
- `scale` – the PNG scale in percent, 150 by default; `outPath` – the PNG path, the HTML page is
  saved next to it.

Titles are in the language of the configuration, including standard attributes and standard
commands, with the synonyms the configuration gives them. Command bars show what the platform puts
into them (`autoCommandBar`), the rest goes to the "more" menu. Icons and fonts are the web
client's own: the drawing reads them from the 1C:Enterprise 8.5 installation on this machine
(`platformPath` or `EDT_BRIDGE_PLATFORM` choose one) and keeps them in the user cache; they are not
part of EDT-Bridge. Without a platform the form is drawn with system fonts and plain marks, and
`warnings` says so. `warnings` also names element kinds the drawing has no look for yet and `data`
keys that matched nothing. The PNG needs the `web` extra (`pipx inject edt-bridge-mcp playwright`,
then `playwright install chromium`).

Sizes and colors of the drawing were measured on the 8.5.1 web client. A window the client opens
smaller than the form scrolls; the drawing shows the whole form. Elements the form code adds or
fills when it opens are data: pass them in `data`. The wrapper reads the form module and names in
`warnings` what the code decides: elements it adds at runtime, a layout that differs by interface
variant, items whose visibility or choice from a list it sets. A read-only field has no drop-down
or calendar button, and an editable date field gets a calendar.

To save a screenshot in the "Version 8.5" interface, specify the name of an open EDT project and
the fully qualified name of the form. In PowerShell, pass the arguments in a JSON file.

```powershell
$argumentsFile = Join-Path $env:TEMP 'edt-form-render.json'
$arguments = @{
    projectName = 'Demo'
    formFqn     = 'Catalog.Products.Form.ItemForm'
    interface   = 'v85'
    theme       = 'light'
    outPath     = Join-Path $env:TEMP 'product-form.png'
}
$arguments | ConvertTo-Json | Set-Content -LiteralPath $argumentsFile -Encoding UTF8
edt-bridge-mcp call edt_form_render --json-file $argumentsFile
```

The command saves `product-form.png` and `product-form.html` in the temporary directory and
returns their paths in `pngPath` and `htmlPath`.

`engine="edt"` returns EDT's form editor preview, as before; `variant`, `density`, `ratio`, `width`
and `height` apply to it. It uses the native EDT renderer for `TAXI` and `VERSION8_5`. Enable native
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

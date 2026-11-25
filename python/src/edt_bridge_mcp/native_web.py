"""An isolated Apache publication, form layout and Chromium screenshots."""
from __future__ import annotations

import os
import re
import secrets
import shutil
import socket
import tempfile
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import quote, urlencode


ENV_HTTPD = "EDT_BRIDGE_TEST_WEB_HTTPD"
TEST_PORT = 1538
COPY_PREFIX = "edt-bridge-web-"
_FIELD = re.compile(r"form[0-9]+_(.+?)(?:_[a-z][0-9]+)?$")
#: What one text of the web form holds at most: items, rows and columns of a table, characters of a value.
LAYOUT_LIMITS = {"maxItems": 300, "maxRows": 30, "maxColumns": 30, "maxValueLength": 2048, "maxRawLength": 512_000}
MAX_TEXT = 200
MAX_FORM_TEXT = 48_000
WEB_OBSERVATION_LIMITS = {
    "maxItems": 64, "maxColumns": 30,
    "maxMessages": 30, "maxMessageLength": 2048,
    "sheets": {"maxFields": 4, "maxRows": 30, "maxColumns": 15, "maxCellLength": 512},
}
#: The names of hidden items one text lists at most.
MAX_HIDDEN = 100
#: The clipped cells one spreadsheet field lists at most.
MAX_CLIPPED = 30
#: The fields of an item line of the web form text.
LEGEND = "name | kind | title | value | x,y wxh | flags"
_TAG = "data-edt-bridge-mask"
_MASK_STYLE = """
html, html * { background-image: none !important; border-image-source: none !important;
         list-style-image: none !important; mask-image: none !important; content: normal !important;
         animation: none !important; transition: none !important; }
*::before, *::after, *::marker { content: none !important; }
iframe, frame, img, input[type=image], svg, canvas, video, object, embed, math,
table, [role=grid], [role=treegrid], [role=table], [role=listbox],
.frameGrid, .grid, .gridContent, [data-edt-bridge-opaque] {
    opacity: 0 !important;
}
"""
# A text node can split its value between styled children. Examine both the node's parent and
# elements with direct text among children. Use the closest 1C element ID as the field name.
_SNAPSHOT = r"""
tag => {
    const rows = [];
    const nodes = [document.body, ...document.body.querySelectorAll("*")];
    for (const node of nodes) {
        node.removeAttribute(tag);
        node.removeAttribute("data-edt-bridge-opaque");
    }
    for (const node of nodes) {
        const style = getComputedStyle(node);
        if (style.visibility !== "visible" || (!node.getClientRects().length && style.display !== "contents")) continue;
        if (node.shadowRoot || node.localName.includes("-")) {
            node.setAttribute("data-edt-bridge-opaque", "");
            continue;
        }
        const control = node.matches("input, textarea, select, [contenteditable]:not([contenteditable=false])");
        const owner = node.closest("[id^=form]");
        const id = owner?.id || "";
        const direct = [...node.childNodes].some(child => child.nodeType === Node.TEXT_NODE && child.data.trim());
        const inline = node.children.length > 0 && [...node.children].every(
            child => /^(inline|contents)/.test(getComputedStyle(child).display));
        let text = direct || inline || control ? node.innerText : "";
        if (node.matches("input, textarea")) text = node.value || node.placeholder;
        if (node.matches("select")) text = [...node.selectedOptions].map(option => option.label).join("\n");
        if (!text) continue;
        let area = node;
        while (area && !area.getClientRects().length) area = area.parentElement;
        if (!area) throw new Error("No mask area for visible DOM text");
        area.setAttribute(tag, ((area.getAttribute(tag) || "") + " " + rows.length).trim());
        rows.push([id, text, control]);
    }
    return rows;
}
"""


# The form as text for a model without vision. The web client names the parts of an item by suffixes of
# its id: form3_Number is the item, form3_Number_i0 its input, form3_Number#title_text its title. Some
# captions are drawn from data attributes by CSS, so innerText misses them: window titles and page tabs
# are read from those attributes.
_LAYOUT = r"""
({title: wanted, maxItems, maxRows, maxColumns, maxValueLength, maxRawLength, sheetNames = []}) => {
    const clean = text => String(text || "").replace(/\s+/g, " ").trim();
    let rawLength = 0, rawTruncated = false;
    const readText = text => {
        const value = String(text ?? "");
        if (value.length > maxValueLength || rawLength + value.length > maxRawLength) {
            rawTruncated = true;
            return "";
        }
        rawLength += value.length;
        return value;
    };
    const shown = node => {
        if (!node || !node.isConnected) return false;
        const style = getComputedStyle(node);
        return style.display !== "none" && style.visibility === "visible" && node.getClientRects().length > 0;
    };
    const rect = node => {
        const box = node.getBoundingClientRect();
        return [box.left, box.top, box.width, box.height];
    };
    const overflows = node => Boolean(node) && (node.scrollWidth > node.clientWidth + 1
                                                || node.scrollHeight > node.clientHeight + 1);
    const selected = node => Boolean(node) && (node.classList.contains("select") || node.classList.contains("checked")
                                               || node.getAttribute("aria-checked") === "true");
    const windowTitle = node => {
        for (let scope = node; scope; scope = scope.parentElement) {
            const caption = scope.querySelector("[id$=headerTopLine_title]");
            if (caption) {
                return caption.getAttribute("title") || caption.parentElement.dataset.title || caption.innerText;
            }
        }
        return "";
    };
    // Every form on the screen: the one in the main window and those in the floating windows over it.
    const forms = [...document.querySelectorAll("[id^=form][id$=_mainGroup]")].filter(shown).map(group => {
        const floating = group.closest("[id^=ps][id$=win]");
        return {group, floating, prefix: group.id.slice(0, -"mainGroup".length), title: windowTitle(group),
                z: floating ? Number(getComputedStyle(floating).zIndex) || 0 : -1,
                area: floating ? floating.querySelector("[id$=formContent]") || floating : group};
    });
    if (!forms.length) return null;
    // The title of a modified object ends with an asterisk that the test manager does not report.
    const normal = text => clean(text).replace(/\s*\*$/, "").toLowerCase();
    const named = forms.filter(form => wanted && normal(form.title) === normal(wanted));
    const target = named.length === 1 ? named[0] : [...forms].sort((a, b) => b.z - a.z)[0];
    const origin = rect(target.group);
    const view = rect(target.area);
    // The share of a box inside the window and its scrolled containers: an item can lie in the form and still
    // be out of sight, past the window edge or below a scrolled panel.
    const visibleShare = node => {
        let [left, top, width, height] = rect(node);
        let right = left + width, bottom = top + height;
        const area = width * height;
        if (!area) return 1;
        const clip = (x, y, w, h) => {
            left = Math.max(left, x); top = Math.max(top, y);
            right = Math.min(right, x + w); bottom = Math.min(bottom, y + h);
        };
        clip(...view);
        clip(0, 0, innerWidth, innerHeight);
        for (let scope = node.parentElement; scope && scope !== target.area; scope = scope.parentElement) {
            if (getComputedStyle(scope).overflow !== "visible") clip(...rect(scope));
        }
        return Math.max(0, right - left) * Math.max(0, bottom - top) / area;
    };
    const place = node => {
        const [left, top, width, height] = rect(node);
        return `${Math.round(left - origin[0])},${Math.round(top - origin[1])} ${Math.round(width)}x${Math.round(height)}`;
    };
    // The parts of one item: form3_Number is the item, form3_Number_div its frame, form3_Number_i0 its input,
    // form3_Number#title_text its title, form3_Number_CB a button of the input.
    const part = /^(.*?)(#.*|_div|_i[0-9]+|_DLB|_CB|_OB|_CLR|_container|_right|_search|_col_[0-9]+|_cmd_[0-9_]+)$/;
    // Not items of the form layout: the entries of submenus and context menus, the editors of table columns.
    const service = /^(popup_|allActions)|_(all_)?sub(_submenu)?$|_submenu$|_preloader$/;
    const items = new Map();
    const pages = new Map();
    for (const node of target.area.querySelectorAll("[id]")) {
        if (node.id.startsWith("thpage_" + target.prefix)) {
            pages.set(node.id.slice(("thpage_" + target.prefix).length), node);
            continue;
        }
        if (!node.id.startsWith(target.prefix)) continue;
        const id = node.id.slice(target.prefix.length);
        const match = part.exec(id);
        const name = match ? match[1] : id;
        if (!name || name.startsWith("$") || name === "mainGroup" || service.test(name)) continue;
        if (node.parentElement.closest(".grid, [id$=_submenu], .submenu")) continue;
        if (!items.has(name)) items.set(name, {});
        items.get(name)[match ? match[2] : ""] = node;
    }
    const lines = [], hidden = [], tables = [];
    let truncated = false;
    const describe = (name, kind, title, value, box, extra, options = []) => {
        if (lines.length >= maxItems) {
            truncated = true;
            return;
        }
        const flags = extra.filter(Boolean);
        const share = visibleShare(box);
        if (share === 0) flags.push("offscreen");
        else if (share < 0.98) flags.push("cut");
        lines.push([name, kind, readText(title), readText(value), place(box), flags, options.map(readText)]);
    };
    const describePages = (group) => {
        for (const [page, header] of pages) {
            if (header.closest(".frameTabs") !== group) continue;
            const content = document.getElementById(target.prefix + page + "_div");
            const label = header.querySelector("[data-content]");
            const text = label ? label.dataset.content : header.innerText;
            if (shown(header)) {
                describe(page, "page", text, "", header, [selected(header) && "current"]);
            } else if (shown(content)) {
                // Pages shown without tabs: the page in view is the current one.
                describe(page, "page", text, "", content, ["current"]);
            } else {
                hidden.push(page);
            }
        }
    };
    const describeTable = (name, main) => {
        const grid = document.getElementById("grid_" + target.prefix + name) || main;
        const indexed = cells => new Map([...cells].filter(shown).map(
            (cell, position) => [Number(cell.getAttribute("colindex") ?? position), cell.innerText]));
        const headers = indexed(grid.querySelectorAll(".gridHead:not(.gridFoot) .gridBox"));
        const body = grid.querySelector(".gridBody");
        const rows = [...grid.querySelectorAll(".gridBody .gridLine")].filter(shown);
        const sample = rows.slice(0, maxRows);
        const cells = sample.map(row => indexed(row.querySelectorAll(".gridBox")));
        const indexes = new Set(headers.keys());
        for (const row of cells) {
            for (const index of row.keys()) indexes.add(index);
        }
        const columns = [...indexes].sort((a, b) => a - b).slice(0, maxColumns);
        tables.push({name, columns: columns.map(index => readText(headers.get(index))), current: sample.findIndex(selected),
                     rows: cells.map(row => columns.map(index => readText(row.get(index)))),
                     more: rows.length > maxRows || indexes.size > maxColumns || overflows(body)});
    };
    for (const [name, parts] of items) {
        const main = parts[""] || parts["#text"] || parts["#image"];
        const caption = parts["#title_text"];
        const input = parts["_i0"];
        const box = [main, parts["_div"], parts["#text_div"], parts["#image_div"], input].find(shown);
        if (box && !(box.getBoundingClientRect().width && box.getBoundingClientRect().height)) continue;
        if (!box) {
            if (main && (parts["_div"] || parts["#text_div"])) hidden.push(name);
            continue;
        }
        const has = name => Boolean(main) && main.classList.contains(name);
        // A button of the form body wraps its press in a frame; a button of a command bar is the press itself.
        const press = has("press") ? main : main && main.querySelector(":scope > a.press");
        let title = caption && shown(caption) ? caption.innerText : "";
        let kind, value = "", extra = [], options = [];
        if (named.length === 1 && sheetNames.includes(name)) {
            kind = "spreadsheet";
        } else if (input || has("field")) {
            kind = "input";
            value = input && input.type !== "password" ? String(input.value ?? "") : "";
            // An empty date or number shows its input mask, which is not a value.
            if (!/[^\s._:\-/()]/.test(value)) value = "";
            const hint = main && main.querySelector(".inputHint");
            if (!title && !value && hint && shown(hint)) {
                title = hint.innerText;
                extra.push("hint");
            }
            extra.push(input && input.disabled && "disabled",
                       ((input && input.readOnly) || has("fieldReadOnly")) && "readonly",
                       overflows(input) && "clipped", input && document.activeElement === input && "focus");
        } else if (has("checkbox")) {
            kind = "check";
            value = selected(main) ? "on" : "off";
        } else if (has("switch")) {
            kind = "switch";
            value = selected(main) ? "on" : "off";
        } else if (has("frameTumbler") || has("frameRadio")) {
            kind = "choice";
            const nodes = [...main.querySelectorAll(".tumblerItem, .radioItem")].filter(shown);
            value = nodes.filter(selected).map(option => option.innerText).join(", ");
            options = nodes.slice(0, maxColumns).map(option => option.innerText);
        } else if (press) {
            kind = "button";
            value = press.innerText.trim() ? press.innerText : press.getAttribute("title") || main.getAttribute("title");
            const state = name => press.classList.contains(name);
            extra.push(state("pressDisabled") && "disabled", state("pressDefault") && "default",
                       overflows(press) && "clipped");
        } else if (has("staticText")) {
            kind = has("staticTextHyper") ? "link" : "text";
            value = main.innerText;
            extra.push(overflows(main) && "clipped");
        } else if (has("frameTabs")) {
            kind = "pages";
        } else if (has("grid")) {
            kind = "table";
        } else if (main && main.querySelector(":scope > .imageContainer")) {
            kind = "picture";
        } else if (has("frameCommand")) {
            continue;
        } else if (title) {
            kind = "group";
        } else {
            continue;
        }
        if (title && overflows(caption) && !extra.includes("clipped")) extra.push("clipped");
        describe(name, kind, title, value, box, extra, options);
        if (kind === "pages") describePages(main);
        if (kind === "table") describeTable(name, main);
        if (truncated) break;
    }
    // A window over the form: a warning, a question or an error the form did not expect.
    const over = forms.filter(form => form.z > target.z)
        .sort((a, b) => b.z - a.z).map(form => form.title || form.group.innerText);
    const others = forms.filter(form => form !== target && form.z <= target.z).map(form => form.title)
        .filter(Boolean);
    const panel = document.getElementById((target.floating ? target.floating.id.slice(0, -3) : "") + "messageCell");
    const messages = shown(panel) ? [...panel.querySelectorAll(".gridBody .gridLine")].filter(shown)
        .map(line => line.innerText).filter(Boolean) : [];
    const limited = values => values.slice(0, maxRows).map(readText);
    return {title: readText(target.title), matchedTitle: named.length === 1, prefix: target.prefix,
            size: `${Math.round(origin[2])}x${Math.round(origin[3])}`, items: lines, tables, hidden,
            messages: limited(messages), over: limited(over), others: limited(others),
            truncated: truncated || rawTruncated || [messages, over, others].some(values => values.length > maxRows)};
}
"""


# The cells of a spreadsheet field whose text the web client cuts. The field draws its cells inside same-origin
# frames: a row is div.R<n> with the row number in "y", a cell is div[x] with the column number in "x", its text an
# absolutely placed span. A cell lets a long text run on over empty neighbours (overflow visible) and cuts it when the
# next cell has a value (overflow hidden): a text wider or taller than such a cell is what the user cannot read.
_SHEET_CLIPS = r"""
({prefix, names, limit}) => {
    const result = Object.create(null);
    for (const name of names) {
        const field = document.getElementById(prefix + name);
        if (!field) continue;
        const found = [], seen = new Set();
        let more = false;
        frames: for (const frame of field.querySelectorAll("iframe")) {
            let doc = null;
            try { doc = frame.contentDocument; } catch (error) { continue; }
            if (!doc || !doc.body) continue;
            const view = doc.defaultView;
            for (const cell of doc.body.querySelectorAll("div[y] > div[x]")) {
                const text = (cell.textContent || "").trim();
                if (!text) continue;
                const style = view.getComputedStyle(cell);
                const cutX = style.overflowX !== "visible", cutY = style.overflowY !== "visible";
                if (style.visibility !== "visible" || (!cutX && !cutY)) continue;
                // The glyphs, not the span: the span of a cell that may run on is wider than its text.
                const t = {left: Infinity, right: -Infinity, top: Infinity, bottom: -Infinity};
                const walker = doc.createTreeWalker(cell, 4);
                for (let node = walker.nextNode(); node; node = walker.nextNode()) {
                    if (!node.data.trim()) continue;
                    const range = doc.createRange();
                    range.selectNodeContents(node);
                    for (const box of range.getClientRects()) {
                        t.left = Math.min(t.left, box.left); t.right = Math.max(t.right, box.right);
                        t.top = Math.min(t.top, box.top); t.bottom = Math.max(t.bottom, box.bottom);
                    }
                }
                if (t.right < t.left) continue;
                t.width = t.right - t.left; t.height = t.bottom - t.top;
                const c = cell.getBoundingClientRect();
                const wide = cutX && (t.left < c.left - 1 || t.right > c.right + 1);
                const tall = cutY && (t.top < c.top - 1 || t.bottom > c.bottom + 1);
                if (!wide && !tall) continue;
                const row = cell.parentElement.getAttribute("y"), column = cell.getAttribute("x");
                const key = row + ":" + column;
                if (seen.has(key)) continue;
                if (found.length >= limit) {
                    more = true;
                    break frames;
                }
                seen.add(key);
                found.push([Number(row) + 1, Number(column) + 1, text, Math.round(t.width), Math.round(c.width),
                            Math.round(t.height), Math.round(c.height), tall]);
            }
        }
        result[name] = {cells: found, more};
    }
    return result;
}
"""


class WebError(Exception):
    def __init__(self, status, message, details=None):
        super().__init__(message)
        self.status = status
        #: Fields the answer of the tool carries besides the status and the message.
        self.details = details or {}


def client_url(port, client_id, navigation_link, language, user):
    locale = "ru_RU" if language.casefold().startswith("ru") else "en_US"
    query = "TestClient&" + urlencode({"TestClientID": client_id, **({"N": user} if user else {})})
    fragment = "#" + quote(navigation_link, safe="/.:?=") if navigation_link else ""
    return f"http://127.0.0.1:{port}/test/{locale}/?{query}{fragment}"


def mask_indexes(rows, flt):
    indexes = []
    for index, (element_id, value, control) in enumerate(rows):
        match = _FIELD.fullmatch(element_id)
        name = match[1] if match else None
        if (control and not name) or flt.named(name, value) != value:
            indexes.append(index)
    return indexes


def copy_infobase(stack, source):
    root = Path(stack.enter_context(tempfile.TemporaryDirectory(prefix=COPY_PREFIX)))
    base = root / "base"
    base.mkdir(mode=0o700)
    shutil.copyfile(source / "1Cv8.1CD", base / "1Cv8.1CD")
    if os.geteuid() == 0:
        import pwd

        try:
            worker = pwd.getpwnam("www-data")
        except KeyError as error:
            raise WebError("dependency_missing", "Apache needs the www-data worker account.") from error
        root.chmod(0o711)
        for path in (base, base / "1Cv8.1CD"):
            os.chown(path, worker.pw_uid, worker.pw_gid)
    return root, base


def _quote_config(path):
    value = str(path)
    if any(char in value for char in '\r\n"\\$'):
        raise WebError("web_publication_failed", "Apache paths cannot contain quotes, backslashes or dollar signs.")
    return f'"{value}"'


def _apache_config(folder, base, port, modules, wsap):
    pub = folder / "publication"
    pub.mkdir(mode=0o755)
    descriptor = pub / "default.vrd"
    ET.ElementTree(ET.Element("point", {
        "xmlns": "http://v8.1c.ru/8.2/virtual-resource-system", "base": "/test", "ib": f'File="{base}";',
    })).write(descriptor, encoding="utf-8", xml_declaration=True)
    unixd = modules / "mod_unixd.so"
    load_unixd = f"LoadModule unixd_module {_quote_config(unixd)}" if unixd.is_file() else ""
    # Apache runs one worker: the test socket and the browser session must share a process.
    return f"""
ServerRoot {_quote_config(folder)}
Listen 127.0.0.1:{port}
ServerName 127.0.0.1
PidFile {_quote_config(folder / "httpd.pid")}
ErrorLog {_quote_config(folder / "httpd-error.log")}
LoadModule mpm_event_module {_quote_config(modules / "mod_mpm_event.so")}
LoadModule authz_core_module {_quote_config(modules / "mod_authz_core.so")}
LoadModule alias_module {_quote_config(modules / "mod_alias.so")}
{load_unixd}
LoadModule _1cws_module {_quote_config(wsap)}
{"User www-data" if os.geteuid() == 0 else ""}
{"Group www-data" if os.geteuid() == 0 else ""}
ServerLimit 1
ThreadLimit 64
StartServers 1
ThreadsPerChild 64
MinSpareThreads 1
MaxSpareThreads 64
MaxRequestWorkers 64
Alias "/test" {_quote_config(str(pub) + "/")}
<Directory {_quote_config(pub)}>
    AllowOverride None
    Options None
    Require all granted
    SetHandler 1c-application
    ManagedApplicationDescriptor {_quote_config(descriptor)}
</Directory>
"""


def _accepting(port):
    with socket.socket() as listener:
        listener.settimeout(0.2)
        return listener.connect_ex(("127.0.0.1", port)) == 0


def wait_for_browser(page, seconds, failure=None):
    if failure is None:
        from playwright.sync_api import Error as failure
    try:
        # Sync Playwright dispatches request interception callbacks only while its event loop runs.
        page.wait_for_timeout(max(1, int(seconds * 1000)))
    except failure as error:
        raise WebError("session_exited", "The browser closed during the form check.") from error


def _wait_port(process, port, deadline, owns_listener=None, pause=time.sleep):
    while process.poll() is None and time.monotonic() < deadline:
        if (owns_listener is None or owns_listener(process, port)) and _accepting(port):
            return
        pause(min(0.1, max(0.0, deadline - time.monotonic())))
    if process.poll() is not None:
        raise WebError("web_publication_failed", f"Apache exited with code {process.returncode} while opening port {port}.")
    raise WebError("web_publication_failed", f"Apache did not open port {port} before the time limit.")


def _milliseconds(deadline):
    return max(1, int((deadline - time.monotonic()) * 1000))


def _lock(stack):
    # Apache needs 1538 for its single worker. Reserving the other standard port serializes runs
    # before Apache opens the test listener, without leaving a lock file from an interrupted run.
    guard = stack.enter_context(socket.socket())
    try:
        guard.bind(("127.0.0.1", TEST_PORT + 1))
    except OSError as error:
        raise WebError("web_publication_failed", "The standard web test port 1539 is already in use.") from error
    if _accepting(TEST_PORT):
        raise WebError("web_publication_failed", "The standard web test ports 1538–1539 are already in use.")


#: The buttons that answer a window a configuration opens over the main window when a session starts. The
#: Standard Subsystems Library opens one when a file infobase lies at another path than where it was opened before
#: and lacks its check file, as every copy of a test run does: "This is a copy of the infobase" keeps the scheduled
#: work with external resources of the copy blocked, the answer a test run needs.
START_ANSWERS = ("Это копия информационной базы", "This is a copy of the infobase")
#: How long one step of File - Open waits before the bridge looks for a window over the main window again.
OPEN_STEP_MS = 15_000
#: How long a modal window without a known answer may stay before the run stops.
UNKNOWN_WINDOW_SECONDS = 15
# The titles of the floating windows, the top one first.
_WINDOWS = r"""
() => [...document.querySelectorAll("[id^=ps][id$=win]")]
    .filter(node => node.getClientRects().length > 0 && getComputedStyle(node).visibility === "visible")
    .sort((a, b) => (Number(getComputedStyle(b).zIndex) || 0) - (Number(getComputedStyle(a).zIndex) || 0))
    .map(node => {
        const caption = node.querySelector("[id$=headerTopLine_title]");
        return caption ? caption.getAttribute("title") || caption.parentElement.dataset.title || caption.innerText : "";
    })
    .map(title => String(title || "").replace(/\s+/g, " ").trim()).filter(Boolean).slice(0, 5)
"""


def _window_titles(page):
    try:
        return [str(title)[:MAX_TEXT] for title in page.evaluate(_WINDOWS) or []]
    except Exception:  # noqa: BLE001 - the titles only explain a failure that is reported anyway
        return []


def _modal(page):
    return page.locator("#modalSurface").is_visible()


def _answer_start_windows(page, deadline, failure):
    """Answer the windows over the main window; the titles of those answered.

    A window the bridge has no answer for may be a short one, a wait indicator, so it gets a few seconds. A window
    that stays is reported with its title at once: the run would otherwise wait for File - Open until its time limit.
    """
    answered = []
    waited = 0.0
    while _modal(page):
        titles = _window_titles(page)
        button = next((locator for locator in (page.get_by_title(caption, exact=True) for caption in START_ANSWERS)
                       if locator.count()), None)
        if button is not None and len(answered) < 3:
            try:
                button.first.click(timeout=min(5_000, _milliseconds(deadline)))
                answered.append(titles[0] if titles else "")
            except failure:
                # A window over it takes the click, such as a list a navigation link opened: Escape closes that one.
                page.keyboard.press("Escape")
        elif waited >= UNKNOWN_WINDOW_SECONDS or _milliseconds(deadline) <= 1_000:
            raise WebError("web_start_window", "A window over the main window of the web client blocks File - Open"
                           + (": " + "; ".join(titles) if titles else "") + ".",
                           {"blockingWindows": titles, "answeredWindows": answered})
        else:
            waited += 1
        wait_for_browser(page, 1, failure)
    return answered


def _open_external(page, external, deadline, failure):
    """Submit the upload dialog; the titles of the start windows answered on the way.

    The platform handles any subsequent security prompt. A window can open over the main window after the menu was
    pressed, so each attempt of File - Open first answers the start windows and closes a menu left open.
    """
    answered = []
    for attempt in range(3):
        answered += _answer_start_windows(page, deadline, failure)
        step = min(OPEN_STEP_MS, _milliseconds(deadline))
        try:
            page.locator("#captionbarMore").click(timeout=step)
            page.locator("#FileButton").hover(timeout=step)
            page.locator("#fileOpen").click(timeout=step)
            break
        except failure:
            if attempt == 2 or _milliseconds(deadline) <= 1_000:
                raise
            page.keyboard.press("Escape")
    page.locator("#fileSelectInput").set_input_files(str(external), timeout=_milliseconds(deadline))
    confirm = page.locator("#fileSelectDialogOk")
    if confirm.is_visible():
        confirm.click(timeout=_milliseconds(deadline))
    return answered


def start(stack, paths, platform, navigation_link, external, user, language, deadline, spawn, free_port,
          owns_listener):
    httpd = Path(os.environ.get(ENV_HTTPD, "/usr/local/apache2/bin/httpd")).resolve()
    modules = httpd.parent.parent / "modules"
    if not modules.is_dir() and httpd.name == "apache2":
        modules = Path("/usr/lib/apache2/modules")
    wsap = platform / "wsap24.so"
    if (not httpd.is_file() or not wsap.is_file() or
            any(not (modules / f"mod_{name}.so").is_file() for name in ("mpm_event", "authz_core", "alias"))):
        raise WebError("dependency_missing", f"Set {ENV_HTTPD} to Apache 2.4 with its event MPM, authz_core "
                                             "and alias modules. The platform installation needs wsap24.so.")
    try:
        from playwright.sync_api import Error, sync_playwright
    except ImportError as error:
        raise WebError("dependency_missing", "The web client requires Playwright and its Chromium browser.") from error
    _lock(stack)
    root, base = paths
    folder = Path(stack.enter_context(tempfile.TemporaryDirectory(prefix="apache-", dir=root)))
    folder.chmod(0o711)
    port = free_port()
    config = folder / "httpd.conf"
    config.write_text(_apache_config(folder, base, port, modules, wsap), encoding="utf-8", newline="")
    process = spawn(stack, [str(httpd), "-f", str(config), "-DFOREGROUND"], os.environ, folder / "httpd-process.log")
    _wait_port(process, port, deadline, owns_listener)
    try:
        playwright = stack.enter_context(sync_playwright())
        browser = playwright.chromium.launch(args=["--no-proxy-server"], timeout=_milliseconds(deadline))
        stack.callback(browser.close)
        page = browser.new_page(viewport={"width": 1280, "height": 900},
                                accept_downloads=False, service_workers="block")
        prefix = f"http://127.0.0.1:{port}/"
        page.route("**/*", lambda route: route.continue_() if route.request.url.startswith(prefix) else route.abort())
        websocket_prefix = f"ws://127.0.0.1:{port}/"
        page.route_web_socket("**/*", lambda route:
                              route.connect_to_server() if route.url.startswith(websocket_prefix) else route.close())
        client_id = "EdtBridge" + secrets.token_hex(12)
        url = client_url(port, client_id, navigation_link, language, user)
        response = page.goto(url, wait_until="domcontentloaded", timeout=_milliseconds(deadline))
    except Error as error:
        raise WebError("web_publication_failed", "Chromium could not open the temporary publication.") from error
    if response is None or not response.ok:
        status = response.status if response is not None else "no response"
        raise WebError("web_publication_failed", f"The temporary publication returned HTTP {status}.")
    _wait_port(process, TEST_PORT, deadline, pause=lambda seconds: wait_for_browser(page, seconds))
    answered = []
    if external:
        try:
            answered = _open_external(page, external, deadline, Error)
        except Error as error:
            raise WebError("web_publication_failed", "Chromium could not open the external file upload dialog.",
                           {"blockingWindows": _window_titles(page)}) from error
    return process, page, client_id, answered


def _freeze(page):
    # Chromium leaves DevTools evaluation enabled while application scripts are suspended.
    # An application callback cannot change the form between reading it and the capture.
    page.context.new_cdp_session(page).send("Emulation.setScriptExecutionDisabled", {"value": True})


def form_text(raw, flt, *, sheets=(), table_columns=None, clips=None):
    """The form read from the DOM as lines for a model, with the values passed through the filter.

    A value whose field name or title names personal data is replaced whole, other text by its shape, the
    same rules as in the other answers. Lines keep the answer compact: a JSON object per item would take
    several times the tokens."""
    remaining = MAX_FORM_TEXT
    limit_reached = False

    def shortened(text):
        flat = " ".join(text.split())
        return (flt.text(flat) if flat != text else text)[:MAX_TEXT]

    def caption(text):
        return shortened(flt.text(text))

    def value(name, title, text):
        return shortened(flt.named(name if flt.kind_of(name) else title, text))

    def line(fields, flags=()):
        return " | ".join(fields) + (" | " + " ".join(flags) if flags else "")

    def fits(text):
        nonlocal remaining, limit_reached
        if len(text) > remaining:
            limit_reached = True
            return False
        remaining -= len(text) + 1
        return True

    def bounded(lines):
        result = []
        for text in lines:
            if not fits(text):
                break
            result.append(text)
        return result

    items = []
    for name, kind, title, text, box, flags, options in raw.get("items") or []:
        if options:
            labels = "/".join(value(name, title, option) for option in options)
            flags = [*flags, "options=" + labels[:MAX_TEXT]]
        item = line([name, kind, caption(title), value(name, title, text), box], flags)
        if not fits(item):
            break
        items.append(item)
    tables = []
    for table in raw.get("tables") or []:
        name = table["name"]
        columns = table.get("columns") or []
        by_title = {}
        for column in (table_columns or {}).get(name, []):
            key = " ".join(column["title"].split()).casefold()
            by_title.setdefault(key, []).append(column["name"])
        keys = [" ".join(title.split()).casefold() for title in columns]
        owners = [by_title[key][0] if keys.count(key) == 1 and len(by_title.get(key, ())) == 1 else ""
                  for key in keys]
        header = line(caption(title) for title in columns)
        if not fits(header):
            break
        raw_rows = table.get("rows") or []
        withheld = False

        def table_cell(index, cell):
            nonlocal withheld
            owner = owners[index] if index < len(owners) else ""
            title = columns[index] if index < len(columns) else ""
            if cell and flt.active and not owner and not flt.kind_of(title):
                withheld = True
                return "[withheld]"
            return value(owner, title, cell)

        rows = bounded(line(table_cell(index, cell) for index, cell in enumerate(row)) for row in raw_rows)
        entry = {"name": name, "columns": header, "rows": rows}
        if withheld:
            entry["withheld"] = True
        current = table.get("current", -1)
        if 0 <= current < len(rows):
            entry["currentRow"] = current + 1
        if table.get("more") or len(rows) < len(raw_rows):
            entry["more"] = True
        tables.append(entry)
    spreadsheets = []
    for sheet in sheets:
        name, title = sheet["name"], sheet["title"]
        header = caption(title)
        if not fits(header):
            break
        rows = bounded(line(value(name, title, cell) for cell in row) for row in sheet["rows"])
        entry = {"name": name, "title": header, "rows": rows}
        if sheet.get("truncated") or len(rows) < len(sheet["rows"]):
            entry["more"] = True
        clipped = (clips or {}).get(name) or {}
        if clipped.get("cells"):
            entry["clipped"] = bounded(
                line([f"R{row}C{column}", value(name, title, text),
                      f"{width}/{cell_width} px" + (f", {height}/{cell_height} px high" if cut_vertically
                                                    else "")])
                for row, column, text, width, cell_width, height, cell_height, cut_vertically in clipped["cells"])
            if clipped.get("more") or len(entry["clipped"]) < len(clipped["cells"]):
                entry["clippedMore"] = True
        spreadsheets.append(entry)
    text = {"title": caption(raw.get("title") or ""), "size": raw.get("size", ""), "legend": LEGEND,
            "items": items}
    for key, found in (("tables", tables), ("spreadsheets", spreadsheets),
                       ("messages", [caption(item) for item in raw.get("messages") or []]),
                       ("overForm", [caption(item) for item in raw.get("over") or []]),
                       ("otherWindows", [caption(item) for item in raw.get("others") or []])):
        if found:
            text[key] = found
    hidden = list(dict.fromkeys(raw.get("hidden") or []))
    if hidden:
        # A large configuration hides dozens of items by its options; the first ones are enough to tell
        # a hidden item from an absent one.
        more = len(hidden) - MAX_HIDDEN
        text["hidden"] = ", ".join(hidden[:MAX_HIDDEN]) + (f" and {more} more" if more > 0 else "")
    if raw.get("truncated") or limit_reached:
        text["truncated"] = True
    return text


def read_form(page, flt, title, *, sheets=(), table_columns=None, panel_messages=(), panel_truncated=False):
    """The form in front, or the one titled `title`, as text; see form_text."""
    from playwright.sync_api import Error

    try:
        _freeze(page)
        raw = page.evaluate(_LAYOUT, dict(LAYOUT_LIMITS, title=title or "",
                                          sheetNames=[sheet["name"] for sheet in sheets]))
    except Error as error:
        raise WebError("form_text_failed", "Chromium could not read the form from the DOM.") from error
    if not raw:
        raise WebError("form_text_failed", "The web client shows no form.")
    if not raw.get("matchedTitle"):
        return form_text(raw, flt)
    if not raw.get("messages"):
        raw = dict(raw, messages=panel_messages, truncated=raw.get("truncated") or panel_truncated)
    clips = None
    if sheets and raw.get("prefix"):
        try:
            clips = page.evaluate(_SHEET_CLIPS, {"prefix": raw["prefix"], "names": [sheet["name"] for sheet in sheets],
                                                 "limit": MAX_CLIPPED})
        except Error:
            # The cells are read anyway; only the marks of a cut text are missing.
            clips = None
    return form_text(raw, flt, sheets=sheets, table_columns=table_columns, clips=clips)


def capture(page, output, flt, deadline):
    """Save the Chromium capture: with the field masks applied when the personal data filter is on."""
    from playwright.sync_api import Error

    try:
        _freeze(page)
        options = {"animations": "disabled", "caret": "hide", "timeout": _milliseconds(deadline)}
        if flt.active:
            rows = page.evaluate(_SNAPSHOT, _TAG)
            if not rows:
                raise WebError("screenshot_failed", "No visible DOM content was available for filtering.")
            masked = [page.locator(f'[{_TAG}~="{index}"]') for index in mask_indexes(rows, flt)]
            options.update(mask=masked, mask_color="#202020", style=_MASK_STYLE)
        png = page.screenshot(**options)
    except Error as error:
        raise WebError("screenshot_failed", "Chromium could not capture the masked DOM.") from error
    destination = output / "screenshot.png"
    destination.write_bytes(png)
    return {"screenshot": str(destination), "screenshotMasked": flt.active}

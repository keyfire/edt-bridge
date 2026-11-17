"""An isolated Apache publication and a Chromium screenshot with DOM masks."""
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


class WebError(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status


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


def wait_for_browser(page, seconds):
    from playwright.sync_api import Error

    try:
        # Sync Playwright dispatches request interception callbacks only while its event loop runs.
        page.wait_for_timeout(max(1, int(seconds * 1000)))
    except Error as error:
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


def _open_external(page, external, deadline):
    """Submit the upload dialog. The platform handles any subsequent security prompt."""
    page.locator("#captionbarMore").click(timeout=_milliseconds(deadline))
    page.locator("#FileButton").hover(timeout=_milliseconds(deadline))
    page.locator("#fileOpen").click(timeout=_milliseconds(deadline))
    page.locator("#fileSelectInput").set_input_files(str(external), timeout=_milliseconds(deadline))
    confirm = page.locator("#fileSelectDialogOk")
    if confirm.is_visible():
        confirm.click(timeout=_milliseconds(deadline))


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
    if external:
        try:
            _open_external(page, external, deadline)
        except Error as error:
            raise WebError("web_publication_failed", "Chromium could not open the external file upload dialog.") from error
    return process, page, client_id


def capture(page, output, flt, deadline):
    """Save only the pixels from the Chromium capture with the field masks applied."""
    from playwright.sync_api import Error

    try:
        devtools = page.context.new_cdp_session(page)
        # Chromium leaves DevTools evaluation enabled while application scripts are suspended.
        # An application callback cannot change a protected field between analysis and capture.
        devtools.send("Emulation.setScriptExecutionDisabled", {"value": True})
        rows = page.evaluate(_SNAPSHOT, _TAG)
        if not rows:
            raise WebError("screenshot_failed", "No visible DOM content was available for filtering.")
        masked = [page.locator(f'[{_TAG}~="{index}"]') for index in mask_indexes(rows, flt)]
        png = page.screenshot(mask=masked, mask_color="#202020", style=_MASK_STYLE,
                              animations="disabled", caret="hide", timeout=_milliseconds(deadline))
    except Error as error:
        raise WebError("screenshot_failed", "Chromium could not capture the masked DOM.") from error
    destination = output / "screenshot.png"
    destination.write_bytes(png)
    return {"screenshot": str(destination), "screenshotMasked": True}

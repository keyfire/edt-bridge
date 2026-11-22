"""A managed form drawn the way the 1C client shows it: HTML from the bridge's client view model.

The bridge (edt_form_render with engine "model") hands over EDT's laid-out form description: the
element tree with titles in the language of the configuration, the buttons the platform puts into
each command bar, field buttons, tumbler items, pages. This module turns it into an HTML page in the
look of the web client - "Version 8.5" or "Taxi", light or dark - and form_render.py takes a picture
of it. Sizes and colors were measured on a live 8.5.1 web client; icons and fonts are the client's
own, read from the platform installed on this machine (client_assets.py), never shipped here.

The model keeps EDT's class names (HippoLayEdit, HippoLayTumbler, ...). Anything this module does
not know is drawn as nothing and named in the warnings, so a new element kind shows up as a gap the
caller is told about rather than as a crash.
"""

from __future__ import annotations

import html
import re
from dataclasses import dataclass, field
from typing import Any, Callable

INTERFACES = ("v85", "taxi")
THEMES = ("light", "dark")

#: Platform pictures of standard commands and the 8.5 client picture they are drawn with (pic_<file>
#: from core85_root.res, svg_<id> from the client sprite as a fallback).
COMMAND_ICONS = {
    "AddListItem": "pic_plus", "CreateListItem": "pic_plus", "Add": "pic_plus", "Create": "pic_plus",
    "MoveUp": "pic_arrowUp", "MoveDown": "pic_arrowDown", "Change": "pic_pencil", "Find": "pic_search",
    "FindInList": "pic_search", "CancelSearch": "pic_searchClose", "Delete": "pic_trash",
    "DeleteListItem": "pic_trash", "SetDeletionMark": "pic_trashClose", "Print": "pic_print",
    "OutputList": "pic_listPrint", "Refresh": "pic_arrowRound", "Reread": "pic_arrowRound",
    "CloneListItem": "pic_paperDoublePlus", "CopyToClipboard": "pic_paperDouble",
    "SortListAsc": "pic_letterSortAscending", "SortListDesc": "pic_letterSortDescending",
    "ListSettings": "pic_listSettings", "CustomizeForm": "pic_formSettings",
}

#: Palette of the web client, light and dark (custom properties of .themeLight / .themeDark, 8.5.1).
PALETTE = {
    "light": {
        "page": "#f2f2f2", "win": "#ffffff", "text": "#1c1c1f", "value": "#4d4d54", "muted": "#81818a",
        "disabled": "#b5b5bd", "line": "#dbdbdb", "row-line": "#ededf2", "field-line": "#999999",
        "field-bg": "#ffffff", "ro-bg": "#f2f2f2", "fill": "#f2f2f2", "btn": "#dbdbdb", "brand": "#ffdd00",
        "brand-text": "#403700", "icon": "#404040", "icon-muted": "#999999", "link": "#007aff",
        "action": "#278bf9", "check": "#278bf9", "chk-dis": "#c5c5c5", "chk-dis-on": "#8dc2fb",
        "required": "#ff3e33", "badge-bg": "#e5ffec", "badge-text": "#048f27",
        "current": "#ededed", "shadow": "rgba(64,64,64,.16)", "tumbler-on": "#ffffff",
    },
    "dark": {
        "page": "#404040", "win": "#1a1a1a", "text": "#ededf2", "value": "#b8b8bf", "muted": "#84848c",
        "disabled": "#525259", "line": "#575757", "row-line": "#3a3a40", "field-line": "#999999",
        "field-bg": "#1a1a1a", "ro-bg": "#404040", "fill": "#404040", "btn": "#575757", "brand": "#ffe433",
        "brand-text": "#403b19", "icon": "#f2f2f2", "icon-muted": "#999999", "link": "#3395ff",
        "action": "#5aa6f9", "check": "#5aa6f9", "chk-dis": "#6b6b6b", "chk-dis-on": "#37597e",
        "required": "#ff6e66", "badge-bg": "#194023", "badge-text": "#8aeda2",
        "current": "#404040", "shadow": "rgba(0,0,0,.5)", "tumbler-on": "#575757",
    },
}

#: Taxi has a light theme only in the platform; its palette is its own (web client 8.5.1, interface Taxi).
TAXI_PALETTE = {
    "page": "#ffffff", "win": "#ffffff", "text": "#333333", "value": "#4b4b4b", "muted": "#4d4d4d",
    "disabled": "#a0a0a0", "line": "#b3b3b3", "row-line": "#e6e6e6", "field-line": "#a0a0a0",
    "field-bg": "#ffffff", "ro-bg": "#f5f5f5", "fill": "#f2f2f2", "btn": "#ffffff", "brand": "#ffe100",
    "brand-text": "#333333", "icon": "#4d4d4d", "icon-muted": "#888888", "link": "#1c55ae",
    "check": "#4d4d4d", "required": "#ff0000", "badge-bg": "#e5ffec", "badge-text": "#048f27",
    "current": "#fbed9e", "shadow": "rgba(0,0,0,.2)", "tumbler-on": "#ffffff", "group-title": "#009646",
    "help": "#1c55ae",
}

#: Density of the 8.5 client: normal and compact (form property ScaleVariant), CSS pixels.
DENSITY = {
    "normal": {
        "lbl-fs": "14.667px", "lbl-lh": "20px", "in-h": "40px", "in-fs": "16px", "in-lh": "24px",
        "in-r": "8px", "in-px": "11px", "gap-lbl": "3px", "gap-v": "17px", "gap-h": "16px", "lbl-h": "18px", "lbl-hr": "20px", "card-r": "16px",
        "card-p": "16px", "card-fs": "20px", "card-lh": "32px", "row-h": "40px", "cell-fs": "14.667px",
        "bar-h": "53px", "bar-fs": "14.667px", "btn-h": "40px", "btn-fs": "16px", "tab-fs": "16px",
        "tab-lh": "24px", "chk": "24px", "chk-tbl": "20px", "chk-fs": "16px", "spin": "32px", "radius-win": "16px",
        "char": "7.7", "char-pad": "21", "line-gap": "12px", "page-top": "20px", "sep-m": "8px", "blink-h": "20px",
    },
    "compact": {
        "lbl-fs": "12px", "lbl-lh": "16px", "in-h": "32px", "in-fs": "13.333px", "in-lh": "20px",
        "in-r": "6px", "in-px": "9px", "gap-lbl": "3px", "gap-v": "14px", "gap-h": "12px", "lbl-h": "15px", "lbl-hr": "16px", "card-r": "12px",
        "card-p": "12px", "card-fs": "16px", "card-lh": "24px", "row-h": "33px", "cell-fs": "12px",
        "bar-h": "44px", "bar-fs": "12px", "btn-h": "32px", "btn-fs": "13.333px", "tab-fs": "13.333px",
        "tab-lh": "20px", "chk": "20px", "chk-tbl": "16px", "chk-fs": "13.333px", "spin": "26px", "radius-win": "16px",
        "char": "6.4", "char-pad": "18", "line-gap": "6px", "page-top": "16px", "sep-m": "13.5px", "blink-h": "26px",
    },
}

#: Important message banners by kind: (ground, mark) in 8.5 light, 8.5 dark and Taxi.
NOTE_COLORS = {
    "info": {"light": ("#b8ebff", "#32c1fa"), "dark": ("#2c5b6e", "#64cffa"), "taxi": ("#b8ebff", "#0f77a1")},
    "warning": {"light": ("#fff1ab", "#ffd500"), "dark": ("#706427", "#ffdd33"), "taxi": ("#fff1ab", "#ffd500")},
    "success": {"light": ("#abf7be", "#0cdc40"), "dark": ("#256635", "#37dc60"), "taxi": ("#abf7be", "#0cdc40")},
    "error": {"light": ("#ffbbb8", "#ff3e33"), "dark": ("#70302d", "#ff6e66"), "taxi": ("#ffbbb8", "#ff3e33")},
}
NOTE_KINDS = {"информация": "info", "info": "info", "information": "info", "предупреждение": "warning",
              "warning": "warning", "успех": "success", "success": "success", "ошибка": "error", "error": "error"}
NOTE_ICONS = {"info": "svg_infoSmall", "warning": "svg_triangleExclamation", "success": "svg_check",
              "error": "svg_triangleExclamationFill"}

#: Vertical spacing of a group (8.5): the half interval of the compact density is measured, 6px instead of 14px.
VERTICAL_SPACING = {"None": "0px", "Half": "calc(var(--gap-v) * 3 / 7)", "Double": "calc(var(--gap-v) * 2)"}

#: Taxi metrics (web client 8.5.1, interface Taxi), CSS pixels.
TAXI_DENSITY = {
    "lbl-fs": "13.333px", "lbl-lh": "16px", "lbl-h": "16px", "lbl-hr": "16px", "in-h": "25px", "in-fs": "13.333px",
    "in-lh": "16px", "in-r": "3px", "in-px": "4px", "gap-lbl": "6px", "gap-v": "9px", "gap-h": "16px",
    "card-r": "0px", "card-p": "0px", "card-fs": "16px", "card-lh": "19.2px", "row-h": "29px",
    "cell-fs": "13.333px", "bar-h": "27px", "bar-fs": "13.333px", "btn-h": "26px", "btn-fs": "13.333px",
    "tab-fs": "13.333px", "tab-lh": "16px", "chk": "14px", "chk-fs": "13.333px", "spin": "19px",
    "radius-win": "0px", "char": "9.3", "char-pad": "39", "line-gap": "4px", "page-top": "10px",
}

FONT_STACK = "Almarai, Roboto, sans-serif"
FONT_STACK_MEDIUM = 'Almarai, "Roboto Medium", sans-serif'
TAXI_FONT_STACK = "Almarai, Arial, sans-serif"

CSS = """
* { box-sizing: border-box; }
html, body { margin: 0; padding: 0; }
body { background: var(--page); font-family: var(--font); color: var(--text); -webkit-font-smoothing: antialiased; }
.stage { padding: 18px; display: inline-block; }
.win { background: var(--win); border-radius: var(--radius-win); box-shadow: 0 6px 24px var(--shadow);
       padding: 24px; width: max-content; min-width: 360px; display: flex; flex-direction: column; }
.sized-window .stage { padding: 3px; }
.sized-window .win > .body, .sized-window .body > .lgrid:has(> .side-tabs:only-child) { flex: 1 1 auto; }
.sized-window .body > .lgrid:has(> .side-tabs:only-child) { grid-template-rows: minmax(0, 1fr); }
.head { display: flex; align-items: center; gap: 10px; min-height: 40px; }
.head .title { font-size: 24px; line-height: 40px; color: var(--text); white-space: nowrap; }
.head .create-action { display: inline-flex; flex: 0 0 auto; align-items: center; gap: 8px; height: 32px;
                       color: var(--action); font: 16px/24px var(--font-medium); white-space: nowrap; }
.right { margin-left: auto; display: flex; align-items: center; gap: 8px; }
.circ { width: 32px; height: 32px; border-radius: 48px; background: var(--fill); display: inline-flex;
        align-items: center; justify-content: center; flex: 0 0 32px; }
.pill { height: 32px; border-radius: 80px; background: var(--fill); display: inline-flex; align-items: center;
        gap: 6px; padding: 0 12px; font-size: 14.667px; color: var(--text); white-space: nowrap; }
.nav { display: flex; align-items: center; margin-top: 28px; min-height: 40px; }
.nav .ntitle { font-size: 20px; line-height: 40px; color: var(--text); display: inline-flex; align-items: center; }
.body { margin-top: 20px; display: flex; flex-direction: column; gap: var(--gap-v); }
.foot { display: flex; justify-content: flex-end; gap: 16px; margin-top: 28px; }
.btn { height: var(--btn-h); border-radius: var(--in-r); background: var(--btn); color: var(--text);
       font-family: var(--font-medium); font-size: var(--btn-fs); display: inline-flex; align-items: center;
       padding: 0 12px; white-space: nowrap; }
.btn.main { background: var(--brand); color: var(--brand-text); }
.col { display: flex; flex-direction: column; gap: var(--gap-v); min-width: 0; }
.row { display: flex; flex-direction: row; gap: var(--gap-h); align-items: stretch; min-width: 0; }
.col > .vgrow { flex: 1 1 auto; }
.card.vgrow > .col, .card.vgrow > .row { flex: 1 1 auto; }
.row > .stretch, .row > .grow { flex: 1 1 auto; }
.row > .fixed { flex: 0 0 auto; }
.row > .grow.col > .chkrow:only-child { flex-grow: 1; }
/* A lone text in a row stands level with the middle of a taller neighbour (a button). */
.row > div:has(> .deco:only-child) { justify-content: center; }
.col > * { align-self: stretch; }
.card { border: 0.8px solid var(--line); border-radius: var(--card-r); padding: var(--card-p);
        display: flex; flex-direction: column; gap: var(--gap-v); }
.card > .ctitle { font-size: var(--card-fs); line-height: var(--card-lh); color: var(--text); white-space: nowrap; }
.gtitle { font-size: var(--lbl-fs); line-height: var(--lbl-lh); color: var(--muted); white-space: nowrap; }
.fld { display: flex; flex-direction: column; gap: var(--gap-lbl); min-width: 0; }
.fld > .lbl { height: var(--lbl-h); }
.fld > .lbl.reqd { height: var(--lbl-hr); margin-bottom: 1px; }
.fld.left { flex-direction: row; align-items: center; gap: 8px; }
.lbl { font-size: var(--lbl-fs); line-height: var(--lbl-lh); color: var(--muted); white-space: nowrap;
       display: flex; align-items: center; gap: 6px; }
.lbl .t { overflow: hidden; text-overflow: ellipsis; }
.lbl .req { color: var(--required); }
.lbl .info { margin-left: auto; display: inline-flex; }
.frow { display: flex; align-items: center; gap: 8px; min-width: 0; }
.inp { height: var(--in-h); border: 0.8px solid var(--field-line); border-radius: var(--in-r); background: var(--field-bg);
       padding: 0 var(--in-px); font-size: var(--in-fs); line-height: var(--in-lh); color: var(--value);
       display: flex; align-items: center; white-space: nowrap; overflow: hidden; flex: 1 1 auto; min-width: 0; }
.inp .v { overflow: hidden; text-overflow: ellipsis; flex: 1 1 auto; }
.inp .ph { color: var(--muted); }
.inp.ro { background: var(--ro-bg); }
.inp.dis { background: var(--ro-bg); color: var(--disabled); }
.inp.multi { align-items: flex-start; padding-top: 6px; white-space: pre-wrap; }
.inp .ib { display: inline-flex; margin-left: 6px; flex: 0 0 auto; }
.spin { width: var(--spin); height: var(--spin); border-radius: var(--in-r); background: var(--fill);
        display: inline-flex; align-items: center; justify-content: center; flex: 0 0 auto; }
.spins { display: inline-flex; gap: 4px; flex: 0 0 auto; }
.openb { display: inline-flex; width: 32px; justify-content: center; flex: 0 0 auto; }
.chkrow { display: flex; align-items: center; gap: 0; font-size: var(--chk-fs); line-height: 20px; color: var(--text); white-space: nowrap; }
.chkrow .t { margin-left: 0; }
.chk { width: var(--chk); height: var(--chk); border: 1.6px solid var(--icon-muted); border-radius: 5px; flex: 0 0 auto;
       display: inline-flex; align-items: center; justify-content: center; margin-right: 0; background: var(--field-bg); }
.chkbox { display: inline-flex; width: 20px; height: 20px; align-items: center; justify-content: center; margin-right: 0; }
.chk.on { border-color: var(--check); }
.chk { position: relative; }
.chk.part { border-color: var(--check); }
.chk.part::after { content: ""; position: absolute; left: 4px; right: 4px; height: 2px; border-radius: 2px;
                   background: var(--check); }
.note { display: flex; align-items: flex-start; gap: 8px; border-radius: 14px; padding: 12px 14px; font-size: 13.333px;
        line-height: 20px; color: var(--value); white-space: pre-wrap; }
.note > .ni { display: inline-flex; flex: 0 0 auto; }
.chk-gap { width: 0; }
.tumbler { display: inline-flex; background: var(--fill); border-radius: var(--in-r); padding: 2px; gap: 0; align-self: flex-start; }
.tumbler .ti { font-size: var(--in-fs); line-height: var(--in-lh); color: var(--muted); padding: calc((var(--in-h) - 4px - var(--in-lh)) / 2) 12px;
               border-radius: calc(var(--in-r) - 2px); white-space: nowrap; }
.tumbler .ti.on { background: var(--tumbler-on); color: var(--value); box-shadow: 0 1px 3px var(--shadow); }
.radios { display: flex; flex-direction: column; gap: 6px; }
.radios.h { flex-direction: row; gap: 16px; }
.radio { display: flex; align-items: center; gap: 8px; font-size: var(--chk-fs); line-height: 20px; color: var(--text); white-space: nowrap; }
.rdot { width: 20px; height: 20px; border-radius: 50%; border: 1.6px solid var(--icon-muted); flex: 0 0 auto;
        display: inline-flex; align-items: center; justify-content: center; background: var(--field-bg); }
.rdot.on { border-color: var(--check); }
.rdot.on::after { content: ""; width: 10px; height: 10px; border-radius: 50%; background: var(--check); }
.deco { font-size: var(--lbl-fs); line-height: var(--lbl-lh); color: var(--text); white-space: pre-wrap; max-width: 760px; }
.deco.link { color: var(--link); }
.blink { color: var(--check); font-family: var(--font-medium); font-size: var(--chk-fs); line-height: var(--blink-h, 20px); white-space: nowrap;
         align-self: center; }
.pic { min-height: 120px; display: flex; align-items: center; flex: 1 1 auto;
       justify-content: center; color: var(--value); font-size: var(--lbl-fs); padding: 12px; }
.sep { height: 1px; background: var(--line); margin: var(--sep-m, 8px) 12px; }
.avatar { width: 56px; height: 56px; border-radius: 50%; background: var(--fill); flex: 0 0 auto; }
.tabs { display: flex; gap: 4px; border-bottom: 0.8px solid var(--line); }
.tab { padding: 2px 12px 7px; font-size: var(--tab-fs); line-height: var(--tab-lh); color: var(--muted); white-space: nowrap;
       display: inline-flex; align-items: center; gap: 6px; border-bottom: 3px solid transparent; margin-bottom: -0.8px; }
.tab.on { color: var(--text); border-bottom-color: var(--brand); }
.badge { min-width: 24px; height: 24px; border-radius: 160px; background: var(--badge-bg); color: var(--badge-text);
         font-size: 12px; display: inline-flex; align-items: center; justify-content: center; padding: 0 6px; }
.pages { display: flex; flex-direction: column; min-width: 0; }
.pbody { display: grid; flex: 1 1 auto; }
.pbody > .page { grid-area: 1 / 1; }
.page { display: flex; flex-direction: column; gap: var(--gap-v); padding-top: var(--page-top); }
.tblock { display: flex; flex-direction: column; gap: 16px; min-width: 0; }
.cbar { display: flex; align-items: center; gap: 0; background: var(--fill); border-radius: var(--in-r);
        min-height: var(--bar-h); padding: 0 8px; }
.cbtn { display: inline-flex; align-items: center; gap: 4px; height: 36px; padding: 0 12px; font-size: var(--bar-fs);
        color: var(--text); white-space: nowrap; }
.cbtn.dis { color: var(--disabled); }
.cdiv { width: 1px; height: 28px; background: var(--line); margin: 0 4px; }
.cbar .right { gap: 0; }
.cbar .right .cbtn.search { gap: 8px; padding: 0 16px 0 12px; }
.cbar .right .cbtn.more { padding: 0 8px; }
table.grid { border-collapse: collapse; width: 100%; }
table.grid th { font-family: var(--font-medium); font-weight: 400; font-size: var(--cell-fs); color: var(--text); text-align: left;
                height: var(--row-h); padding: 0 8px; white-space: nowrap; }
table.grid td { font-size: var(--cell-fs); color: var(--value); height: var(--row-h); padding: 0 8px; white-space: nowrap;
                border-top: 0.8px solid var(--row-line); max-width: 560px; overflow: hidden; text-overflow: ellipsis; }
table.grid tr.cur td { background: var(--current); }
table.grid td.num { text-align: right; padding-right: 12px; }
table.grid td.sel, table.grid th.sel { width: 47px; padding: 0 0 0 12px; }
table.grid td.link { color: var(--link); }
table.grid td.wrap { white-space: normal; }
table.grid tr.fill td { border-top: 0.8px solid var(--row-line); }
.tw { display: inline-flex; width: 24px; vertical-align: middle; }
.hide { display: none; }
.ghost { visibility: hidden; }
.tblock.vgrow { flex: 1 1 auto; }
.tbox { min-width: 0; }
.tblock.vgrow > .tbox { flex: 1 1 auto; }
.trow { display: flex; gap: 8px; min-width: 0; }
.trow.vgrow { flex: 1 1 auto; }
.trow > .tblock { flex: 1 1 auto; }
.trow > .info, .trow > .help { display: inline-flex; flex: 0 0 auto; align-self: flex-start; margin: 0; }
.tfit { display: flex; width: 0; min-width: 100%; }
.tumbler { max-width: 100%; }
.tumbler .ti { min-width: 0; overflow: hidden; text-overflow: ellipsis; flex: 0 1 auto; }
.tdrop { display: inline-flex; align-items: center; gap: 4px; height: var(--in-h); padding: 0 12px; align-self: flex-start;
         border-radius: var(--in-r); background: var(--tumbler-on); box-shadow: 0 0 0 0.8px var(--line), 0 1px 3px var(--shadow); }
.tdrop .opts { display: inline-grid; justify-items: center; font-size: var(--in-fs); line-height: var(--in-lh); color: var(--value); }
.tdrop .opts > span { grid-area: 1 / 1; white-space: nowrap; }
.tdrop .opts > span.off { visibility: hidden; }
"""

TAXI_CSS = """
.win { box-shadow: 0 2px 10px var(--shadow); border: 0.8px solid #b3b3b3; padding: 0 12px 12px; min-width: 320px; }
.head { padding: 6px 0 2px; min-height: 40px; gap: 6px; }
.head .star { display: inline-flex; width: 18px; }
.head .title { font-size: 20px; line-height: 24px; color: #333333; }
.head .right { gap: 14px; align-items: center; }
.deco.link { text-decoration: underline; }
.tnav { display: flex; align-items: center; gap: 20px; min-height: 28px; margin-top: 0; }
.tnav .on { background: var(--fill); border-radius: 3px; padding: 6px 10px; color: var(--text); font-size: 13.333px; }
.tnav { width: 0; min-width: 100%; overflow: hidden; white-space: nowrap; }
.tnav .lnk { color: var(--link); font-size: 13.333px; border-bottom: 0.8px solid var(--link); line-height: 15px; flex: 0 0 auto; }
.tbar { display: flex; align-items: center; gap: 9px; margin-top: 3px; min-height: 27px; }
.tbtn { height: 26px; padding: 0 12px; border: 0.8px solid rgba(0,0,0,.3); border-bottom-color: rgba(0,0,0,.4);
        border-radius: 2px; background: #ffffff; box-shadow: 0 1px 0 rgba(0,0,0,.2); font-size: 13.333px; color: #333;
        display: inline-flex; align-items: center; gap: 5px; white-space: nowrap; }
.tbtn.main { background: var(--brand); font-weight: 700; }
.tbtn[aria-disabled="true"], .cbtn[aria-disabled="true"], .tmenuitem[aria-disabled="true"] { opacity: .45; box-shadow: none; }
.tsubmenu { position: relative; flex: 0 0 auto; }
.tsubmenu > summary { cursor: pointer; }
.tsubmenu > summary::-webkit-details-marker { display: none; }
.tmenu { position: absolute; inset: 100% auto auto 0; z-index: 2; min-width: 100%; width: max-content;
         border: 0.8px solid #b3b3b3; background: var(--win); box-shadow: 0 2px 6px var(--shadow); }
.tmenuitem { display: flex; align-items: center; gap: 4px; min-height: 24px; padding: 2px 12px;
             font-size: 13.333px; white-space: nowrap; }
.tmenu > .tsubmenu > .tmenu { inset: 0 auto auto 100%; }
.tmenusep { display: block; border-top: 0.8px solid #d9d9d9; }
.body { margin-top: 6px; gap: var(--gap-v); }
.foot { display: none; }
.lbl { color: var(--muted); }
.fld.left { flex-direction: row; align-items: center; gap: var(--gap-lbl); }
.lgrid { display: grid; grid-template-columns: minmax(0, max-content) minmax(min(45%, 120px), 1fr);
         column-gap: var(--gap-lbl); row-gap: var(--gap-v);
         align-items: center; align-content: start; }
.lgrid > * { grid-column: 1 / -1; }
.lgrid > .vgrow { align-self: stretch; }
.lgrid > .lgrid { display: grid; grid-template-columns: subgrid; }
.page > .vgrow { flex: 1 1 auto; }
.lgrid > .fld.left { display: contents; }
.lgrid > .fld.left > .lbl { grid-column: 1; height: auto; min-height: var(--lbl-h); white-space: normal; }
.tcols .lgrid > .fld.left > .lbl { max-width: 250px; }
.lgrid > .fld.left > .lbl .t { white-space: normal; overflow-wrap: anywhere; }
.lgrid > .fld.left > :not(.lbl) { grid-column: 2; align-self: start; }
.tcols { gap: 10px; }
.tcols > .lgrid, .tcols > .col { flex: 0 1 auto; min-width: 0; }
.tcols > .fld { flex-shrink: 1; }
.titled > .gtitle { min-height: var(--card-lh); }
.col:has(> .gtitle + .tcols) { gap: 5px; }
.tblank { min-height: var(--in-h); }
.page-layout { position: relative; width: fit-content; max-width: calc(100% - 14px);
               display: flex; flex-direction: column; }
.page-layout:has(.tblock) { width: 100%; }
.page-layout > .vgrow { flex: 1 1 auto; }
.page-help { position: absolute; top: 0; left: calc(100% + 5px); width: 10px; text-align: center;
             font: 700 13px/15px var(--font); color: #5498af; }
.tedit { display: grid; grid-template-columns: minmax(0,1fr); min-width: 0; }
.tedit.compact { justify-self: start; width: var(--field-w); max-width: 100%; }
.tedit::before, .tedit > .inp { grid-area: 1 / 1; }
.tedit::before { content: ""; width: var(--field-w); max-width: 100%; height: 0; }
.inp { border: none; box-shadow: inset 0 0 0 1px var(--field-line); color: var(--value); padding: 0 var(--in-px); }
.inp.dis { --field-line: #c6c6c6; background: #fcfcfc; color: var(--value); }
.inp.btns { padding-right: 0; }
.tb { display: inline-flex; align-self: stretch; margin-left: auto; background: var(--field-line); gap: 1px; padding: 1px 1px 1px 1px;
      border-radius: 0 3px 3px 0; flex: 0 0 auto; }
.tb > span { width: 19px; background-color: #ffffff; display: inline-flex; align-items: center; justify-content: center; color: #4d4d4d;
             font-size: 9px; background-repeat: no-repeat; background-position: 50% 50%; background-size: 16px 16px; }
.tb.spin2 { flex-direction: column; }
.tb.spin2 > span { height: 11px; flex: 1 1 auto; font-size: 7px; }
.inp.dis .tb { background: #e7e7e7; }
.inp.dis .tb > span { opacity: .4; }
.tags { display: inline-flex; align-items: center; gap: 4px; min-width: 0; max-width: 100%; overflow: hidden;
        vertical-align: middle; }
.tag-item, .tag-more { border-radius: 12px; padding: 0 10px; height: 20px; line-height: 20px;
                       background: #e6e6e6; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tag-item { min-width: 0; }
.tag-more { flex: 0 0 auto; }
.inp .tag-item::after { content: "×"; color: #777; margin-left: 8px; }
.tag-more::after { content: "⌄"; color: #777; margin-left: 6px; }
.card { border: none; padding: 0; gap: var(--gap-v); }
.card > .ctitle { color: var(--group-title); font-size: var(--card-fs); line-height: var(--card-lh); }
.gtitle { color: var(--group-title); font-size: 16px; line-height: 19.2px; }
.tabs { border-bottom: none; gap: 0; position: relative; z-index: 1; }
.tab { border: 0.8px solid #b3b3b3; border-bottom: none; border-radius: 3px 3px 0 0; padding: 0 12px; height: 28px; background: #f2f2f2;
       margin: 0 -1px 0 0; color: #333333; line-height: 28px; }
.tab.on { background: #ffffff; height: 29px; margin-bottom: -1px; }
.pbody { border: 0.8px solid #b3b3b3; }
.side-tabs { flex-direction: row; }
.side-tabs > .tabs { flex: 0 0 23%; min-width: max-content; flex-direction: column; }
.side-tabs > .tabs > .tab { height: 29px; border-radius: 0; margin: 0; background: #e6e6e6; }
.side-tabs > .tabs > .tab:last-child { border-bottom: 0.8px solid #b3b3b3; }
.side-tabs > .tabs > .tab.on { background: #ffffff; border-right-color: #ffffff; margin-right: -1px; }
.side-tabs > .pbody { min-width: 0; }
.page { padding: var(--page-top); }
.tumbler { background: transparent; padding: 0; border-radius: 3px; box-shadow: none; gap: 0; }
.tumbler .ti { position: relative; border-radius: 0; padding: 0 11px; height: 25px; line-height: 25px; color: #333333;
               background: #ffffff; font-size: 13.333px; text-align: center; box-shadow: none; }
.tumbler .ti::after { content: ""; position: absolute; top: 0; bottom: 0; left: 0; right: -1px; pointer-events: none;
                      border: 0.8px solid var(--field-line); box-shadow: rgba(0,0,0,.25) 0 -26.667px 20px -26.667px inset; }
.tumbler .ti:first-child, .tumbler .ti:first-child::after { border-radius: 3px 0 0 3px; }
.tumbler .ti:last-child, .tumbler .ti:last-child::after { border-radius: 0 3px 3px 0; right: 0; }
.tumbler .ti:only-child, .tumbler .ti:only-child::after { border-radius: 3px; }
.tumbler .ti.on { color: #009646; background: #ffffff; }
.tumbler .ti.on::after { box-shadow: none; }
.tfit.full .tumbler { width: 100%; }
.tfit.full .tumbler .ti { flex: 1 1 0; }
.tfit.full .tumbler.fit .ti { min-width: max-content; }
.help { color: var(--help); font-weight: 700; font-size: 13px; margin: 0 -8px 0 10px; flex: 0 0 auto; }
.hwrap { display: flex; align-items: center; min-width: 0; }
.hwrap > :first-child { flex: 1 1 auto; min-width: 0; }
.hwrap > .tfit { width: auto; }
.hwrap > .tfit:not(.full) { flex: 0 1 auto; }
.pic { border: 0.8px solid var(--field-line); border-radius: 0; }
.cbar { background: transparent; padding: 0; min-height: 27px; gap: 9px; }
.cbtn { height: 26px; padding: 0 12px; border: 0.8px solid rgba(0,0,0,.3); border-radius: 2px; background: #ffffff;
        box-shadow: 0 1px 0 rgba(0,0,0,.2); }
.cbtn.ponly, .tbtn.ponly { width: 30px; padding: 0; justify-content: center; }
.cbtn.ponly + .cbtn.ponly { margin-left: -10px; border-radius: 0 2px 2px 0; }
.cbtn.ponly:has(+ .cbtn.ponly) { border-radius: 2px 0 0 2px; }
.tpic { width: 16px; height: 16px; background: no-repeat 50% 50% / contain; flex: 0 0 auto; }
.cdiv { width: 1px; height: 1px; background: transparent; margin: 0; }
.cbar .right, .tbar .right { gap: 9px; }
.tsearch { width: 200px; height: 27px; flex: 0 0 auto; border-radius: 3px; }
.tsearch .ph { color: #b3b3b3; }
.tsearchmenu { width: 40px; padding: 0; justify-content: center; gap: 4px; }
.tbarhelp { width: 30px; padding: 0; justify-content: center; font-weight: 700; }
table.grid, .tbox { border: 0.8px solid #b3b3b3; }
.tbox { max-width: 100%; overflow-x: auto; }
.tbox::-webkit-scrollbar { height: 15px; }
.tbox::-webkit-scrollbar-track { background: #ccc; border: 4px solid white; border-radius: 8px; }
.tbox::-webkit-scrollbar-thumb { background: #999; border: 4px solid white; border-radius: 8px; }
.tbox > table.grid { border: none; }
.sized-window table.grid { table-layout: fixed; }
table.grid th { background: #f2f2f2; border-right: 0.8px solid #d9d9d9; font-family: var(--font); color: #4d4d4d;
                border-bottom: 0.8px solid #d9d9d9; height: 31px; padding: 0 8px; font-size: 13.333px;
                overflow: hidden; text-overflow: ellipsis; }
table.grid th[rowspan]:not(.picture-heading) { vertical-align: top; padding-top: 7px; }
table.grid th.picture-heading { padding: 0 6px; }
table.grid th .tpic, table.grid th .check-heading { display: inline-flex; vertical-align: middle; }
table.grid th .check-heading { font-size: 18px; font-weight: 700; }
table.grid td { border-top: 0.8px solid #e6e6e6; height: 29px; padding: 0 6px; color: #333; font-size: 13.333px; }
table.grid tr:not(.fill) td:not(:last-child) { border-right: 0.8px solid #e6e6e6; }
table.grid .tcell-members { display: inline-flex; align-items: center; gap: 10px; max-width: 100%; vertical-align: middle; }
.circ { display: none; }
.chk { width: 15px; height: 15px; border: 0.8px solid #a0a0a0; border-radius: 1.875px; background: #ffffff; position: relative;
       overflow: visible; }
.chk.on { border-color: #a0a0a0; }
.chk > svg { position: absolute; left: -1px; top: -1px; }
.chkrow { font-size: 13.333px; line-height: 16px; color: #333333; }
.chkrow.dis .chk { opacity: .38; }
.chkrow .t { min-width: 0; white-space: normal; overflow-wrap: anywhere; }
.switchrow { min-height: 21px; }
.switch { position: relative; display: inline-block; height: 12px; width: 36px;
          border-radius: 6px; background: #b3b3b3; flex: 0 0 auto; }
.switch.on { background: #009646; }
.switch::after { content: ""; position: absolute; top: -4px; left: 0; width: 20px; height: 20px;
                 border: 1px solid #a0a0a0; border-radius: 50%; background: #fff; }
.switch.on::after { left: auto; right: 0; }
.chkrow.dis .switch { opacity: .45; }
.chk.part { border-color: #a0a0a0; }
.chk.part::after { left: 3px; right: 3px; top: 3px; bottom: 3px; height: auto; border-radius: 0.075em; background: #a0a0a0; }
.note { border-radius: 0; padding: 4px 6px 5px 5px; gap: 5px; line-height: 16px; color: #333333; }
.rdot { width: 15px; height: 15px; border: 0.8px solid #a0a0a0; }
.rdot.on { border-color: #a0a0a0; }
.rdot.on::after { width: 5px; height: 5px; background: #009646; }
.radio { font-size: 13.333px; line-height: 16px; color: #333333; gap: 6px; }
.radios { gap: 9px; }
"""


#: Taxi puts the navigation commands that do not fit the window under "Еще...": done after layout.
V85_CSS = """
.v85 .head { flex-shrink: 0; }
.v85 .win:has(.side-tabs) > .body { margin-top: 32px; }
.v85 .foot { flex-shrink: 0; margin-top: 24px; }
.v85.sized-window .win > .body { min-height: 0; overflow: auto; }
.v85.sized-window .body > .col:has(> .side-tabs:only-child) { flex: 1 1 auto; min-height: 0; }
.v85 .tabs { overflow: auto hidden; }
.v85 .side-tabs { flex-direction: row; min-height: 0; }
.v85 .side-tabs > .tabs { flex: 0 0 clamp(9em, 34.3%, 324px); flex-direction: column; gap: 0;
                         position: relative; border: 0; border-inline-end: 0.8px solid var(--line); overflow: hidden auto; }
.v85 .side-tabs > .tabs > .tab { flex: 0 0 auto; min-height: calc(var(--tab-lh) + 20px); margin: 0;
                                padding: 8px 10px 8px 0; border: 0; border-inline-end: 3px solid transparent;
                                white-space: normal; }
.v85 .side-tabs > .tabs > .tab.on { border-inline-end-color: var(--brand); }
.v85 .pbody { min-width: 0; grid-template-columns: minmax(0, 1fr); }
.v85 .side-tabs > .pbody { min-height: 0; padding-inline-start: 24px; overflow: auto; }
.v85 .side-tabs > .pbody > .page { padding-top: 0; }
.v85.sized-window .side-tabs > .pbody > .page.ghost { display: none; }
.v85 .badge { min-width: 22px; height: 22px; }
.v85 .page-layout { display: flex; gap: 8px; min-width: 0; }
.v85 .page-layout > :first-child { flex: 1 1 auto; min-width: 0; }
.v85 .page-help { display: inline-flex; align-self: flex-start; flex: 0 0 20px; }
.v85 .row > .grow, .v85 .row > .stretch { flex-basis: 0; min-width: 0; }
.v85 .row > .fixed:has(.fld, .chkrow), .v85 .row > .chkrow.fixed { flex-shrink: 1; min-width: 0; }
.v85 .chkrow .t { min-width: 0; }
.v85 .fld .lbl, .v85 .chkrow { white-space: normal; }
.v85 .fld > .lbl { min-height: var(--lbl-h); height: auto; }
.v85 .fld > .lbl.reqd { min-height: var(--lbl-hr); height: auto; }
.v85 .gtitle.big { color: var(--text); font-family: var(--font-medium); }
.v85 .chkrow { align-items: flex-start; min-height: var(--chk); line-height: var(--chk); }
.v85 .chk-info { display: inline-flex; flex: 0 0 auto; margin-left: 6px;
                 margin-top: calc((var(--chk) - 16px) / 2); }
.v85 .chk { width: var(--chk); height: var(--chk); border: 0; border-radius: calc(var(--chk) * 0.25);
            background: transparent; color: var(--icon-muted); }
.v85 .chk::before { content: ""; position: absolute; inset: 0; border: 2px solid currentColor;
                    border-radius: inherit; pointer-events: none; }
.v85 .chk.on, .v85 .chk.part { color: var(--check); }
.v85 .chk > svg { position: absolute; inset: 0; width: 100%; height: 100%; fill: currentColor; }
.v85 .chk.part::after { left: calc(var(--chk) * 0.25 + 1px); right: calc(var(--chk) * 0.25 + 1px);
                        top: 50%; transform: translateY(-50%); height: 2px; border-radius: 2px;
                        background: currentColor; }
.v85 table.grid .chk { --chk: var(--chk-tbl); }
.v85 .switch { position: relative; flex: 0 0 calc(var(--chk) * 2 - 6px); width: calc(var(--chk) * 2 - 6px);
               height: var(--chk); border: 2px solid currentColor; border-radius: calc(var(--chk) * 2);
               color: var(--icon-muted); align-self: flex-start; }
.v85 .switch::before { content: ""; position: absolute; width: calc(var(--chk) * 0.5); height: calc(var(--chk) * 0.5);
                       top: calc(var(--chk) * 0.25 - 2px); left: calc(var(--chk) * 0.25 - 2px);
                       background: currentColor; border-radius: 50%; }
.v85 .switch.on, .v85 .switch.part { color: var(--check); }
.v85 .switch.on::before { left: auto; right: calc(var(--chk) * 0.25 - 2px); }
.v85 .switch.part::before { width: 2px; height: calc(var(--chk) * 0.5); border-radius: 0;
                            top: 50%; left: 50%; right: auto; transform: translate(-50%, -50%); }
.v85 .chkrow.dis { color: var(--disabled); }
.v85 .chkrow.dis .chk, .v85 .chkrow.dis .switch, .v85 .switch[aria-disabled="true"] { color: var(--chk-dis); }
.v85 .chkrow.dis .chk.on, .v85 .chkrow.dis .chk.part,
.v85 .chkrow.dis .switch.on, .v85 .chkrow.dis .switch.part,
.v85 .switch.on[aria-disabled="true"], .v85 .switch.part[aria-disabled="true"] { color: var(--chk-dis-on); }
.v85 .inp.dis { color: var(--value); }
.v85 .frow:has(.inp.dis) .spin { opacity: .55; }
.v85 .tags { display: inline-flex; gap: 4px; align-items: center; min-width: 0; max-width: 100%; }
.v85 .tag-item, .v85 .tag-more { padding: 0 5px; border: 0.8px solid var(--line); border-radius: 4px;
                                background: var(--fill); font-size: var(--cell-fs); line-height: 24px; }
.v85 .tag-item { overflow: hidden; text-overflow: ellipsis; }
.v85 .inp .tag-item::after { content: " ×"; }
.v85 .tag-more { flex-shrink: 0; }
.v85 .tbox { overflow-x: auto; }
.v85 .tabs, .v85 .side-tabs > .tabs, .v85 .side-tabs > .pbody,
.v85.sized-window .win > .body, .v85 .tbox { scrollbar-width: none; }
.v85 .tabs::-webkit-scrollbar, .v85 .side-tabs > .tabs::-webkit-scrollbar,
.v85 .side-tabs > .pbody::-webkit-scrollbar, .v85.sized-window .win > .body::-webkit-scrollbar,
.v85 .tbox::-webkit-scrollbar { display: none; }
.v85 table.grid th .check-heading { font-size: 21px; line-height: 1; color: var(--icon); }
.v85 .grid .tcell-members { display: inline-flex; align-items: center; gap: 10px; max-width: 100%; }
.v85 .grid-empty { display: grid; place-items: center; min-height: calc(3 * var(--row-h)); font-size: var(--cell-fs);
                   color: var(--value); border-top: 0.8px solid var(--row-line); }
.v85 .cbtn[aria-disabled="true"], .v85 .pill[aria-disabled="true"], .v85 .circ[aria-disabled="true"],
.v85 .btn[aria-disabled="true"], .v85 .blink[aria-disabled="true"] { opacity: .5; }
.v85 .cbtn img { width: 20px; height: 20px; object-fit: contain; }
"""

FIT_SCRIPT = """<script>
for (const tabs of document.querySelectorAll('.v85 .side-tabs > .tabs')) {
  const selected = tabs.querySelector('.on');
  if (selected && selected.offsetTop + selected.offsetHeight > tabs.scrollTop + tabs.clientHeight) {
    tabs.scrollTop = selected.offsetTop + selected.offsetHeight - tabs.clientHeight;
  }
}
for (const nav of document.querySelectorAll('.tnav')) {
  const more = nav.querySelector('.more');
  const links = [...nav.querySelectorAll('.lnk:not(.more)')];
  const right = () => nav.getBoundingClientRect().right;
  if (links.length && links[links.length - 1].getBoundingClientRect().right > right()) {
    more.style.display = '';
    while (links.length && more.getBoundingClientRect().right > right()) links.pop().style.display = 'none';
  }
}
</script>"""


def esc(value: Any) -> str:
    return html.escape("" if value is None else str(value), quote=True)


@dataclass
class Assets:
    """The client's own resources: the 8.5 icon sprite and font faces (CSS), when found."""

    sprite: str | None = None
    symbols: frozenset = frozenset()
    font_css: str = ""
    source: str | None = None
    taxi_sprite: str | None = None
    taxi_symbols: frozenset = frozenset()
    taxi_pictures: dict = field(default_factory=dict)
    project_picture: Callable[[str], str] | None = None


@dataclass
class Rendered:
    html: str
    title: str
    pages: list = field(default_factory=list)
    page: str | None = None
    density: str = "normal"
    warnings: list = field(default_factory=list)


class SampleData:
    """Example values the caller passes in `data`: field values, table rows, title, visibility."""

    def __init__(self, raw: Any):
        raw = raw if isinstance(raw, dict) else {}
        self.title = raw.get("title")
        self.values = raw.get("values") if isinstance(raw.get("values"), dict) else {}
        self.tables = raw.get("tables") if isinstance(raw.get("tables"), dict) else {}
        self.page_titles = raw.get("pageTitles") if isinstance(raw.get("pageTitles"), dict) else {}
        self.hidden = set(raw.get("hidden") or [])
        self.shown = set(raw.get("shown") or [])
        self.disabled = set(raw.get("disabled") or [])
        self.messages = [m for m in raw.get("messages") or [] if isinstance(m, dict) and m.get("text")]
        self.used: set[str] = set()

    def value(self, keys: list) -> Any:
        for key in keys:
            if key and key in self.values:
                self.used.add(key)
                return self.values[key]
        return None

    def table(self, keys: list) -> Any:
        for key in keys:
            if key and key in self.tables:
                self.used.add(key)
                return self.tables[key]
        return None


def node_class(n: dict) -> str:
    return n.get("_c", "") if isinstance(n, dict) else ""


def node_name(n: dict) -> str:
    return n.get("logFormElementName") or n.get("name") or ""


def model_of(n: dict) -> dict:
    fme = n.get("formModelElement")
    return fme if isinstance(fme, dict) else {}


def item_name(n: dict) -> str:
    return model_of(n).get("name") or node_name(n).split("#")[0]


def lay_name(n: dict) -> str:
    """The layout element's own name: "<item>#title", "<item>#extTooltipBtn" for the parts of an item."""
    return str(n.get("name") or "")


def children(n: dict) -> list:
    kids = n.get("children")
    return [k for k in kids if isinstance(k, dict)] if isinstance(kids, list) else []


def picture_name(n: dict) -> str:
    pic = n.get("picture")
    if not isinstance(pic, str):
        return ""
    return pic.split(":", 1)[1] if ":" in pic else pic


class FormHtml:
    """One drawing: the model, the chosen interface, theme, page and sample data."""

    def __init__(self, view: dict, interface: str, theme: str, page: str | None, data: Any,
                 assets: Assets | None, density: str | None, width: int | None, height: int | None):
        self.view = view
        self.lay = view.get("lay") or {}
        #: Groups with no items (the layout leaves them out): {name: {parent, before}}, where messages go.
        self.empty_groups = view.get("emptyGroups") if isinstance(view.get("emptyGroups"), dict) else {}
        self.form = view.get("form") or {}
        self.interface = interface
        self.v85 = interface == "v85"
        self.theme = theme
        self.data = SampleData(data)
        self.assets = assets or Assets()
        self.wanted_page = page
        self.window_width = width
        self.window_height = height
        self.warnings: list[str] = []
        self.pages: list[dict] = []
        self.selected: dict[str, str] = {}
        self.unknown: set[str] = set()
        self.notes_drawn: set[int] = set()
        scale = str(self.form.get("scaleVariant") or "Auto")
        self.density = density or ("compact" if scale.lower() == "compact" else "normal")

    # ---- helpers -------------------------------------------------------------------------------

    def icon(self, symbol: str, size: int = 20, color: str = "var(--icon)", fallback: str = "") -> str:
        known = self.assets.symbols if self.v85 or not self.assets.taxi_sprite else self.assets.taxi_symbols
        if symbol in known:
            return (f'<svg width="{size}" height="{size}" style="fill:{color};flex:0 0 auto">'
                    f'<use href="#{symbol}"/></svg>')
        if fallback:
            return f'<span style="color:{color};font-size:{size - 4}px;line-height:{size}px">{fallback}</span>'
        return ""

    def visible(self, n: dict) -> bool:
        name = item_name(n)
        if name in self.data.shown:
            return True
        if name in self.data.hidden or node_name(n) in self.data.hidden:
            return False
        return n.get("visible", True) is not False

    def disabled(self, n: dict) -> bool:
        return (n.get("enabled") is False or model_of(n).get("enabled") is False
                or any(name in self.data.disabled for name in (item_name(n), node_name(n), lay_name(n))))

    def value_of(self, n: dict) -> Any:
        model = model_of(n)
        keys = [item_name(n), node_name(n), model.get("dataPath")]
        path = model.get("dataPath") or ""
        if "." in path:
            keys.append(path.rsplit(".", 1)[1])
        return self.data.value(keys)

    def char_px(self, chars: Any) -> int:
        metrics = self.metrics()
        try:
            count = float(chars)
        except (TypeError, ValueError):
            count = 20
        return int(round(count * float(metrics["char"]) + float(metrics["char-pad"])))

    def metrics(self) -> dict:
        return DENSITY[self.density] if self.v85 else TAXI_DENSITY

    # ---- window --------------------------------------------------------------------------------

    def title(self) -> str:
        if self.data.title:
            return str(self.data.title)
        if self.form.get("title"):
            return str(self.form["title"])
        owner = self.form.get("owner") or {}
        kind = owner.get("objectPresentation") or owner.get("synonym") or owner.get("name") or ""
        main_type = str(self.form.get("mainAttributeType") or "")
        if kind and main_type.endswith(("Объект." + str(owner.get("name")), "Object." + str(owner.get("name")))):
            return f"{kind} (создание)" if self.data.values.get("$new") else kind
        return kind or str(self.form.get("name") or "")

    def render(self) -> Rendered:
        self.collect_pages(self.lay)
        bars = {"top": None, "bottom": None}
        body = []
        for kid in children(self.lay):
            if node_class(kid) == "HippoLayCommandBar":
                where = "bottom" if kid.get("location") == "FormBottom" else "top"
                bars[where] = kid
            else:
                body.append(self.node(kid))
        body.insert(0, self.notes(None))
        title = self.title()
        if self.v85:
            window = self.window_v85(title, bars, "".join(body))
        else:
            window = self.window_taxi(title, bars, "".join(body))
        if self.unknown:
            self.warnings.append("not drawn (no look for these EDT element kinds yet): "
                                 + ", ".join(sorted(self.unknown)))
        unused = sorted(set(self.data.values) | set(self.data.tables)) if self.data else []
        unused = [k for k in unused if k not in self.data.used and not k.startswith("$")]
        if unused:
            self.warnings.append("data keys that match no element of the drawn page: " + ", ".join(unused))
        lost = [str(m.get("group")) for i, m in enumerate(self.data.messages) if i not in self.notes_drawn]
        if lost:
            self.warnings.append("messages whose group is not on the drawn page: " + ", ".join(lost))
        page = next(iter(self.selected.values()), None)
        return Rendered(html=self.page_html(window, title), title=title, pages=self.pages, page=page,
                        density=self.density if self.v85 else "taxi", warnings=self.warnings)

    def page_html(self, window: str, title: str) -> str:
        palette = dict(PALETTE[self.theme]) if self.v85 else dict(TAXI_PALETTE)
        metrics = self.metrics()
        props = ";".join(f"--{k}:{v}" for k, v in {**palette, **metrics}.items())
        font = FONT_STACK if self.v85 else TAXI_FONT_STACK
        medium = FONT_STACK_MEDIUM if self.v85 else TAXI_FONT_STACK
        used = set(re.findall(r'class="(?:tpic )?p-([A-Za-z0-9]+)"', window)) if not self.v85 else set()
        css = CSS + (V85_CSS if self.v85 else TAXI_CSS + "".join(
            f'.p-{name} {{ background-image: url("{uri}"); }}\n'
            for name, uri in sorted(self.assets.taxi_pictures.items()) if name in used))
        geometry = []
        if self.window_width is not None:
            geometry.append(f"width:{self.window_width}px; min-width:0;")
        if self.window_height is not None:
            geometry.append(f"height:{self.window_height}px;" if self.v85
                            else f"min-height:{self.window_height}px;")
        if geometry:
            css += f'\n.win {{ {" ".join(geometry)} }}'
        size_class = " sized-window" if geometry else ""
        sprite = (self.assets.sprite if self.v85 or not self.assets.taxi_sprite else self.assets.taxi_sprite) or ""
        return ("<!doctype html><html><head><meta charset=\"utf-8\">"
                f"<title>{esc(title)}</title><style>{self.assets.font_css}\n"
                f":root{{{props};--font:{font};--font-medium:{medium}}}\n{css}</style></head>"
                f"<body class=\"{self.interface} {self.theme}{size_class}\">{sprite}"
                f"<div class=\"stage\">{window}</div>{FIT_SCRIPT}</body></html>")

    def window_v85(self, title: str, bars: dict, body: str) -> str:
        top = bars["top"]
        commands, _ = self.top_commands(top) if top else ([], False)
        create_actions, header_buttons = [], []
        for command in commands:
            item = model_of(command) if isinstance(command, dict) else {}
            bound = item.get("command") or {}
            if item.get("generated") and bound.get("source") == "standard" and bound.get("name") == "Create":
                # EDT promotes the list's standard Create command into the form header.
                icon = self.icon("pic_plus", 24, "currentColor") or self.icon("svg_plus", 24, "currentColor", "+")
                create_actions.append(f'<span class="create-action">{icon}{esc(self.command_title(command))}</span>')
            else:
                header_buttons.append(self.header_pill(command))
        pills = "".join(header_buttons)
        more = '<span class="circ">' + self.icon("svg_moreTop", 20, fallback="⋮") + "</span>"
        close = '<span class="circ">' + self.icon("svg_closeBig", 20, fallback="✕") + "</span>"
        nav = self.navigation_items()
        head = f'<div class="head"><span class="title">{esc(title)}</span>{"".join(create_actions)}<span class="right">'
        if nav:
            head += more + close + "</span></div>"
            head += ('<div class="nav"><span class="ntitle">Основное' + self.icon("svg_cornerBottom", 20)
                     + f'</span><span class="right">{pills}{more}</span></div>')
        else:
            head += pills + more + close + "</span></div>"
        foot = ""
        if bars["bottom"]:
            shown, _ = self.bar_commands(bars["bottom"])
            buttons = [self.footer_button(c) for c in shown]
            if buttons:
                foot = '<div class="foot">' + "".join(buttons) + "</div>"
        return f'<div class="win">{head}<div class="body">{body}</div>{foot}</div>'

    def window_taxi(self, title: str, bars: dict, body: str) -> str:
        grey = "#707070"
        head = (f'<div class="head"><span class="star">{self.icon("svg_favorite", 16, grey, "☆")}</span>'
                f'<span class="title">{esc(title)}</span><span class="right">'
                f'{self.icon("svg_link", 16, grey)}{self.icon("svg_moreTop", 16, grey, "⋮")}'
                f'{self.icon("svg_maximize", 16, grey)}{self.icon("svg_closeBig", 16, grey, "✕")}</span></div>')
        nav = ""
        # The client lists the commands of a navigation group in alphabetical order.
        links = sorted((item.get("title") or item.get("name") for item in self.view.get("navigation") or []
                        if isinstance(item, dict)), key=lambda t: str(t).lower())
        if links:
            nav = ('<div class="tnav"><span class="on">Основное</span>'
                   + "".join(f'<span class="lnk">{esc(t)}</span>' for t in links)
                   + '<span class="lnk more" style="display:none">Еще... ▾</span></div>')
        tbar = ""
        top = bars["top"]
        if top:
            left, right = self.taxi_bar_parts(*self.top_commands(top), style="tbtn")
            tbar = f'<div class="tbar">{left}{right}</div>'
        return f'<div class="win">{head}{nav}{tbar}<div class="body">{body}</div></div>'

    def navigation_items(self) -> int:
        """Commands of the form's navigation panel: the 8.5 client shows "Основное" over them."""
        count = 0

        def walk(o: Any, in_nav: bool) -> None:
            nonlocal count
            if isinstance(o, dict):
                here = in_nav or (o.get("_c") == "Panel" and "avigation" in str(o.get("name", "")))
                if here and o.get("_c") == "AutoGroupItem":
                    count += 1
                for v in o.values():
                    walk(v, here)
            elif isinstance(o, list):
                for x in o:
                    walk(x, in_nav)

        if isinstance(self.view.get("navigation"), list):
            return len(self.view["navigation"])
        walk(self.view.get("commandInterface"), False)
        return count

    # ---- command bars --------------------------------------------------------------------------

    def bar_commands(self, bar: dict) -> tuple[list, bool]:
        """Commands a bar shows, and whether anything went into its "more" menu."""
        shown, more = [], False

        def walk(n: dict) -> None:
            nonlocal more
            for kid in children(n):
                cls = node_class(kid)
                if cls in ("HippoLayCommand", "HippoLaySubMenu"):
                    if not self.visible(kid):
                        continue
                    where = kid.get("locationInCommandBar")
                    important = kid.get("important")
                    generated = (model_of(kid).get("generated") and cls == "HippoLaySubMenu"
                                 and not children(kid) == [] and where in (None, "Auto"))
                    if self.v85 and cls == "HippoLaySubMenu" and self.is_list_search_menu(kid):
                        more = True
                        continue
                    if self.v85 and generated and (
                            model_of(kid).get("name", "").endswith(("РежимПросмотра",))
                            or self.submenu_all_in_additional(kid)):
                        more = True
                        continue
                    if where in ("InCommandBar", "InCommandBarAndInAdditionalSubmenu") or (
                            where in (None, "Auto") and important is not False):
                        if self.v85 and "standard" == (model_of(kid).get("command") or {}).get("source") \
                                and (model_of(kid).get("command") or {}).get("name") == "Help":
                            more = True
                            continue
                        shown.append(kid)
                    else:
                        more = True
                elif cls == "HippoLayMenuSeparator":
                    if shown and shown[-1] != "|":
                        shown.append("|")
                elif cls == "HippoLayEdit":
                    shown.append({"_search": True, "node": kid})
                elif cls in ("HippoLayCommandBar",):
                    walk(kid)

        walk(bar)
        while shown and shown[-1] == "|":
            shown.pop()
        while shown and shown[0] == "|":
            shown.pop(0)
        return shown, more

    def command_title(self, c: dict) -> str:
        return str(c.get("title") or "")

    def top_commands(self, bar: dict) -> tuple[list, bool]:
        shown, more = self.bar_commands(bar)
        # The client places the generated "Create based on" group before user-defined commands.
        first_custom = next((i for i, c in enumerate(shown)
                             if isinstance(c, dict) and not c.get("_search") and not model_of(c).get("generated")),
                            len(shown))
        for i, c in enumerate(shown[first_custom + 1:], first_custom + 1):
            if node_class(c) != "HippoLaySubMenu" or not model_of(c).get("generated"):
                continue
            commands = [model_of(child).get("command") or {} for child in children(c)]
            if any(cmd.get("name") == "CreateBasedOn"
                   and str(cmd.get("source")).lower() in ("standard", "standardcommand") for cmd in commands):
                shown.insert(first_custom, shown.pop(i))
                break
        return shown, more

    def command_icon(self, c: dict, size: int = 20) -> str:
        picture = str(c.get("picture") or "")
        if picture.startswith("CommonPicture:") and self.assets.project_picture:
            uri = self.assets.project_picture(picture.split(":", 1)[1])
            if uri:
                return f'<img src="{esc(uri)}" width="{size}" height="{size}" alt="">'
        std = picture_name(c)
        symbol = COMMAND_ICONS.get(std)
        return self.icon(symbol, size) if symbol else ""

    def command_parts(self, c: dict) -> tuple[str, str]:
        """(icon html, text) as the 8.5 client draws a command button."""
        rep = c.get("representation") or "Auto"
        icon = self.command_icon(c)
        text = self.command_title(c)
        command = model_of(c).get("command") or {}
        name = command.get("name", "")
        if rep == "Picture":
            return (icon, "") if icon else ("", text)
        if rep == "Text":
            return "", text
        if name in ("MoveUp", "MoveDown") and icon:
            return icon, ""
        return icon, text

    def header_pill(self, c: Any) -> str:
        if not isinstance(c, dict) or c.get("_search"):
            return ""
        icon, text = self.command_parts(c)
        state = ' aria-disabled="true"' if self.disabled(c) else ""
        if node_class(c) == "HippoLaySubMenu":
            return f'<span class="pill"{state}>{esc(text)}{self.icon("svg_cornerBottom", 16)}</span>'
        if not text and icon:
            return f'<span class="circ"{state}>{icon}</span>'
        return f'<span class="pill"{state}>{icon}{esc(text)}</span>'

    def footer_button(self, c: Any) -> str:
        if not isinstance(c, dict) or c.get("_search"):
            return ""
        main = c.get("importance") == "Main" or c.get("defaultButton") is True
        state = ' aria-disabled="true"' if self.disabled(c) else ""
        return f'<span class="btn{" main" if main else ""}"{state}>{esc(self.command_title(c))}</span>'

    @staticmethod
    def is_list_search_menu(c: dict) -> bool:
        if node_class(c) != "HippoLaySubMenu":
            return False
        commands = [model_of(child).get("command") or {} for child in children(c)]
        names = {command.get("name") for command in commands if command.get("source") == "standard"}
        return "Find" in names and bool(names & {"FindByCurrentValue", "SearchHistory"})

    def submenu_all_in_additional(self, menu: dict) -> bool:
        """Whether every visible item of a generated submenu belongs in the 'more' menu."""
        items = [k for k in children(menu) if node_class(k) in ("HippoLayCommand", "HippoLaySubMenu") and self.visible(k)]
        return bool(items) and all(
            k.get("locationInCommandBar") == "InAdditionalSubmenu"
            or (k.get("locationInCommandBar") in (None, "Auto") and k.get("important") is False)
            for k in items
        )

    def taxi_bar_parts(self, shown: list, more: bool, *, style: str) -> tuple[str, str]:
        search = any(isinstance(c, dict) and c.get("_search") for c in shown)
        search_menu = None
        help_shown = separator_pending = False
        left = []
        separator = '<span class="cdiv"></span>' if style == "cbtn" else '<span style="width:6px"></span>'
        for c in shown:
            if c == "|":
                separator_pending = True
                continue
            if c.get("_search"):
                continue
            if search and self.is_list_search_menu(c):
                search_menu = c
                continue
            command = model_of(c).get("command") or {}
            if command.get("source") == "standard" and command.get("name") == "Help":
                help_shown = True
                continue
            if separator_pending and left:
                left.append(separator)
            separator_pending = False
            main = style == "tbtn" and (c.get("defaultButton") is True or c.get("importance") == "Main")
            left.append(self.taxi_command(c, f"{style} main" if main else style))

        right = []
        if search:
            right.append('<span class="inp btns tsearch"><span class="v ph">Поиск (Ctrl+F)</span>'
                         f'<span class="tb">{self.taxi_cell("editClear", "×")}</span></span>')
        if search_menu is not None:
            title = esc(self.command_title(search_menu))
            icon = self.icon("svg_search", 16, "#36749d", "⌕")
            right.append(f'<span class="{style} tsearchmenu" title="{title}">{icon}▾</span>')
        if more:
            right.append(f'<span class="{style}">Еще ▾</span>')
        if help_shown:
            right.append(f'<span class="{style} tbarhelp">?</span>')
        return "".join(left), f'<span class="right">{"".join(right)}</span>' if right else ""

    def table_bar(self, bar: dict) -> str:
        shown, more = self.bar_commands(bar)
        if not self.v85:
            left, right = self.taxi_bar_parts(shown, more, style="cbtn")
            return f'<div class="cbar">{left}{right}</div>' if left or right else ""
        left, search = [], False
        for c in shown:
            if c == "|":
                left.append('<span class="cdiv"></span>')
                continue
            if isinstance(c, dict) and c.get("_search"):
                search = True
                continue
            icon, text = self.command_parts(c)
            attrs = 'class="cbtn dis" aria-disabled="true"' if self.disabled(c) else 'class="cbtn"'
            arrow = self.icon("svg_cornerBottom", 16) if node_class(c) == "HippoLaySubMenu" else ""
            picture_only = bool(icon) and str(c.get("picture", "")).startswith("CommonPicture:") \
                and c.get("representation") in (None, "Auto", "Picture")
            title = f' title="{esc(self.command_title(c))}"' if picture_only else ""
            left.append(f'<span {attrs}{title}>{icon}{"" if picture_only else esc(text)}{arrow}</span>')
        divider = '<span class="cdiv"></span>'
        cleaned = []
        for part in left:
            if part == divider and (not cleaned or cleaned[-1] == divider):
                continue
            cleaned.append(part)
        while cleaned and cleaned[-1] == divider:
            cleaned.pop()
        search_label = "Search" if str(self.view.get("languageCode", "ru")).startswith("en") else "Поиск"
        search_button = ('<span class="cbtn search">' + self.icon("svg_search", 20, fallback="⌕")
                         + f"{search_label}</span>") if search else ""
        more_button = '<span class="cbtn more">' + self.icon("svg_context", 20, fallback="⋮") + "</span>"
        return f'<div class="cbar">{"".join(cleaned)}<span class="right">{search_button}{more_button}</span></div>'

    def taxi_command(self, c: dict, style: str = "cbtn") -> str:
        """A command bar button in Taxi, using the icon and text its representation requests."""
        text = self.command_title(c)
        representation = c.get("representation")
        if representation in (None, "Auto"):
            representation = (model_of(c).get("command") or {}).get("representation")
        image = self.taxi_command_picture(c) if representation in ("Picture", "PictureAndText", "TextPicture") else ""
        if node_class(c) == "HippoLaySubMenu":
            return self.taxi_submenu(c, style, image + esc(text))
        state = ' aria-disabled="true"' if self.disabled(c) else ""
        if image and representation == "Picture":
            return f'<span class="{style} ponly"{state}>{image}</span>'
        return f'<span class="{style}"{state}>{image}{esc(text)}</span>'

    def taxi_submenu(self, c: dict, style: str, label: str) -> str:
        button = label + " ▾"
        if self.disabled(c):
            return f'<span class="{style}" aria-disabled="true">{button}</span>'
        items = []
        for child in children(c):
            if not self.visible(child):
                continue
            cls = node_class(child)
            title = esc(self.command_title(child))
            if cls == "HippoLaySubMenu":
                items.append(self.taxi_submenu(child, "tmenuitem", title))
            elif cls == "HippoLayCommand":
                state = ' aria-disabled="true"' if self.disabled(child) else ""
                items.append(f'<span class="tmenuitem"{state}>{title}</span>')
            elif cls == "HippoLayMenuSeparator":
                items.append('<span class="tmenusep"></span>')
        if not items:
            return f'<span class="{style}">{button}</span>'
        return (f'<details class="tsubmenu"><summary class="{style}">{button}</summary>'
                f'<div class="tmenu">{"".join(items)}</div></details>')

    def taxi_command_picture(self, c: dict) -> str:
        name = picture_name(c)
        if str(c.get("picture") or "").startswith("CommonPicture:"):
            source = self.assets.project_picture(name) if self.assets.project_picture else ""
            return f'<img class="tpic" src="{esc(source)}" alt="">' if source else ""
        picture = (COMMAND_ICONS.get(name) or "").removeprefix("pic_")
        return f'<span class="tpic p-{picture}"></span>' if picture in self.assets.taxi_pictures else ""

    # ---- pages ---------------------------------------------------------------------------------

    def collect_pages(self, n: dict) -> None:
        if node_class(n) == "HippoLayPages":
            pages = [p for p in children(n) if node_class(p) == "HippoLayPage"]
            listed = [{"name": item_name(p), "title": p.get("title") or item_name(p), "visible": self.visible(p)}
                      for p in pages]
            self.pages.append({"pages": item_name(n), "items": listed})
            chosen = None
            if self.wanted_page:
                want = str(self.wanted_page).strip().lower()
                for p in listed:
                    if want in (p["name"].lower(), str(p["title"]).lower()):
                        chosen = p["name"]
            if chosen is None:
                chosen = next((p["name"] for p in listed if p["visible"]), None)
            if chosen:
                self.selected[item_name(n)] = chosen
        for kid in children(n):
            self.collect_pages(kid)

    def pages_html(self, n: dict) -> str:
        pages = [p for p in children(n) if node_class(p) == "HippoLayPage" and self.visible(p)]
        chosen = self.selected.get(item_name(n))
        tabs, content = [], []
        for p in pages:
            on = item_name(p) == chosen
            title = self.data.page_titles.get(item_name(p))
            original = str(p.get("title") or item_name(p))
            count = 0 if isinstance(title, str) else self.rows_on_page(p)
            label = title if isinstance(title, str) else original
            if self.v85 and isinstance(title, str):
                observed = re.fullmatch(re.escape(original) + r" \(([0-9]+)\)", title)
                if observed:
                    label, count = original, int(observed[1])
            badge = f'<span class="badge">{count}</span>' if count and self.v85 else ""
            if count and not self.v85:
                label += f" ({count})"
            tabs.append(f'<span class="tab{" on" if on else ""}">{esc(label)}{badge}</span>')
            # The platform sizes a pages group by its widest page: the others are laid out unseen.
            inner = "".join(self.node(k) for k in children(p))
            content.append(f'<div class="page{"" if on else " ghost"}">{inner}</div>')
        # Every page is laid out in one cell: the pages group is as tall and wide as its largest page.
        side = " side-tabs" if n.get("representation") == "TabsOnLeftHorizontal" else ""
        return (f'<div class="pages stretch{side}"><div class="tabs">{"".join(tabs)}</div>'
                f'<div class="pbody">{"".join(content)}</div></div>')

    def rows_on_page(self, page: dict) -> int:
        """Rows of the page's table: the client counts them on the tab of a page that is one table."""
        tables, fields = [], 0

        def walk(n: dict, in_table: bool) -> None:
            nonlocal fields
            cls = node_class(n)
            if cls == "HippoLayTable":
                tables.append(n)
                return
            if cls in ("HippoLayEdit", "HippoLayCheckBox", "HippoLayTumbler", "HippoLayRadioGroup") and not in_table \
                    and self.visible(n) and not lay_name(n).endswith("СтрокаПоиска"):
                fields += 1
            for kid in children(n):
                walk(kid, in_table or cls == "HippoLayCommandBar")

        walk(page, False)
        if len(tables) != 1 or fields:
            return 0
        return len(self.table_rows(tables[0]))

    # ---- elements ------------------------------------------------------------------------------

    def node(self, n: dict) -> str:
        if not self.visible(n):
            return ""
        cls = node_class(n)
        handler = {
            "HippoLayPanel": self.panel, "HippoLayFormRichElement": self.rich, "HippoLayLogicGroup": self.group,
            "HippoLayPages": self.pages_html, "HippoLayEdit": lambda x: self.edit(x, None),
            "HippoLayCheckBox": lambda x: self.checkbox(x, None), "HippoLayTumbler": self.tumbler,
            "HippoLaySwitcher": lambda x: self.checkbox(x, None, switcher=True),
            "HippoLayRadioGroup": self.radios, "HippoLayStaticText": self.static_text, "HippoLayImage": self.image,
            "HippoLayTable": self.table, "HippoLayCommandBar": self.table_bar, "HippoLayButton": self.button,
        }.get(cls)
        if handler:
            return handler(n)
        if cls in ("HippoLaySeparatePanel", "HippoLayViewStatus", "HippoLayHierarchyPanel",
                   "HippoLayVerticalScrollPanel", "HippoLayMenuSeparator", "HippoLayCommand", "HippoLaySubMenu"):
            return ""
        self.unknown.add(cls)
        return ""

    def panel(self, n: dict) -> str:
        grid = "" if self.v85 else " lgrid"
        if lay_name(n) == "$scrl":
            inner = "".join(self.notes_at(None, item_name(k)) + self.node(k) for k in children(n))
            inner += self.notes_at(None, None)
        else:
            inner = "".join(self.node(k) for k in children(n))
        return f'<div class="col{grid}">{inner}</div>'

    def wants_width(self, n: dict) -> bool:
        """Whether an element takes spare width in a row: a stretchable field or group, pages, a table."""
        cls = node_class(n)
        if cls in ("HippoLayPages", "HippoLayTable"):
            return True
        if cls == "HippoLayLogicGroup":
            return n.get("horStretchable") is True or n.get("role") == "TableGroupRole"
        if cls == "HippoLayFormRichElement":
            if any(node_class(k) == "HippoLayStaticText" and not lay_name(k).endswith("#title") for k in children(n)):
                return False
            if n.get("stretchableMode") in ("Hor", "Any"):
                return True
            return any(self.wants_width(k) for k in children(n) if not lay_name(k).endswith(("#title", "#extTooltipBtn", "#extTooltipPnl")))
        return n.get("stretchableMode") in ("Hor", "Any")

    def wants_height(self, n: dict) -> bool:
        """Whether an element takes spare height: stretchable vertically itself or by something inside."""
        if n.get("stretchableMode") in ("Ver", "Any") and node_class(n) != "HippoLayFormRichElement":
            return node_class(n) in ("HippoLayImage", "HippoLayTable")
        return any(self.wants_height(k) for k in children(n)
                   if not lay_name(k).endswith(("#title", "#extTooltipBtn", "#extTooltipPnl")))

    def stretch_class(self, n: dict) -> str:
        mode = n.get("stretchableMode")
        if mode in ("Hor", "Any") or n.get("horStretchable") is True:
            return "stretch"
        return "fixed"

    def rich(self, n: dict) -> str:
        kids = children(n)
        title = next((k for k in kids if node_class(k) == "HippoLayStaticText" and lay_name(k).endswith("#title")), None)
        info = next((k for k in kids if lay_name(k).endswith("#extTooltipBtn")), None)
        main = [k for k in kids if k is not title and k is not info and not lay_name(k).endswith("#extTooltipPnl")]
        placement = n.get("titlePlacement") or "Auto"
        if not main:
            return ""
        element = main[0]
        cls = node_class(element)
        if not self.visible(element):
            return ""
        caption = (title.get("title") if title and placement != "None" else None) or None
        stretch = "fixed" if cls == "HippoLayStaticText" else self.stretch_class(n)
        if cls == "HippoLayEdit":
            return self.edit(element, caption, info, stretch, placement)
        if cls in ("HippoLayCheckBox", "HippoLaySwitcher"):
            return self.checkbox(element, caption, info, switcher=cls == "HippoLaySwitcher")
        if cls in ("HippoLayTumbler", "HippoLayRadioGroup"):
            inner = self.node(element)
            if cls == "HippoLayRadioGroup" and element.get("orientation") != "Horizontal" and placement == "Auto":
                placement = "Top"
            return self.labeled(inner, caption, info, stretch, required=False, placement=placement)
        if cls == "HippoLayLogicGroup" and element.get("role") == "TableGroupRole":
            table = next((k for k in children(element) if node_class(k) == "HippoLayTable"), None)
            if table is not None:
                spec = self.table_sample(table)
                observed_title = spec.get("title") if isinstance(spec, dict) else None
                if isinstance(observed_title, str):
                    caption = observed_title
                elif caption == item_name(table):
                    caption = model_of(table).get("title") or caption
            mark = ""
            if info is not None:
                # The extended tooltip button of a table stands to the right of its command bar.
                mark = ('<span class="help">?</span>' if not self.v85 else
                        '<span class="info">' + self.icon("svg_infoSmall", 20, "var(--icon-muted)", "ⓘ") + "</span>")
            inner = self.table_group(element, mark)
            if caption and self.v85:
                inner = f'<div class="gtitle">{esc(caption)}</div>' + inner
            elif caption:
                text = caption if str(caption).endswith(":") else f"{caption}:"
                inner = f'<div class="lbl" style="margin-bottom:6px"><span class="t">{esc(text)}</span></div>' + inner
            vgrow = " vgrow" if self.wants_height(element) else ""
            return f'<div class="col {stretch}{vgrow}">{inner}</div>'
        inner = "".join(self.node(k) for k in main)
        if caption and cls not in ("HippoLayLogicGroup", "HippoLayPages"):
            return self.labeled(inner, caption, info, stretch, required=False, placement=placement)
        if element.get("role") == "PageContentRole" and info is not None and self.visible(info):
            vgrow = " vgrow" if self.wants_height(element) else ""
            tooltip = f' title="{esc(info["extTooltip"])}"' if info.get("extTooltip") else ""
            mark = self.icon("svg_infoSmall", 20, "var(--icon-muted)", "ⓘ") if self.v85 else "?"
            return f'<div class="page-layout{vgrow}">{inner}<span class="page-help"{tooltip}>{mark}</span></div>'
        if cls in ("HippoLayLogicGroup", "HippoLayPages"):
            return inner
        vgrow = " vgrow" if self.wants_height(n) else ""
        return f'<div class="{stretch}{vgrow}" style="display:flex;flex-direction:column">{inner}</div>'

    def labeled(self, inner: str, caption: str | None, info: dict | None, stretch: str, required: bool,
                placement: str = "Auto") -> str:
        if not caption and not info:
            return f'<div class="fld {stretch}">{inner}</div>'
        req = '<span class="req">*</span>' if required and self.v85 else ""
        info_html = ('<span class="info">' + self.icon("svg_infoSmall", 20, "var(--icon-muted)", "ⓘ") + "</span>"
                     if info is not None else "")
        left = (not self.v85 and placement in ("Auto", "Left")) or placement == "Left"
        if not self.v85 and not left:
            caption = str(caption or "") + (":" if caption and not str(caption).endswith(":") else "")
        tall = " reqd" if (req or info_html) else ""
        if not self.v85:
            help_mark = '<span class="help">?</span>' if info is not None else ""
            text = (caption or "") + (":" if caption and not str(caption).endswith(":") else "")
            label = f'<div class="lbl"><span class="t">{esc(text)}</span></div>'
            if not caption:
                return f'<div class="fld hwrap {stretch}">{inner}{help_mark}</div>'
            body = f'<div class="hwrap">{inner}{help_mark}</div>' if help_mark else inner
            if left:
                return f'<div class="fld left {stretch}">{label}{body}</div>'
            return f'<div class="fld {stretch}">{label}{body}</div>'
        label = f'<div class="lbl{tall}"><span class="t">{req}{esc(caption or "")}</span>{info_html}</div>'
        if left:
            return f'<div class="fld left {stretch}">{label}{inner}</div>'
        return f'<div class="fld {stretch}">{label}{inner}</div>'

    def check_box(self, value: Any) -> str:
        """A check box as the client draws it: checked, partly checked (a three-state box) or empty."""
        text = str(value).lower()
        if value is True or text in ("true", "да", "1", "yes"):
            mark = (self.icon("svg_checkbox", 24, "currentColor", "✓") if self.v85
                    else self.icon("svg_checkbox", 16, "#009646", "✓"))
            return f'<span class="chk on">{mark}</span>'
        if text in ("2", "partial", "частично", "indeterminate", "неопределено"):
            return '<span class="chk part"></span>'
        return '<span class="chk"></span>'

    def notes_at(self, parent: str | None, before: str | None) -> str:
        """The messages of the empty groups that stand in `parent` just before `before` (None: at its end)."""
        return "".join(self.notes(name) for name, place in self.empty_groups.items()
                       if isinstance(place, dict) and place.get("parent") == parent
                       and (place.get("before") or None) == before)

    def notes(self, group: str | None) -> str:
        """The important messages the form code puts in a group (or, with no group, under the header)."""
        parts = []
        for index, message in enumerate(self.data.messages):
            if index in self.notes_drawn or (message.get("group") or None) != group:
                continue
            self.notes_drawn.add(index)
            kind = NOTE_KINDS.get(str(message.get("type") or "info").lower(), "info")
            ground, mark = NOTE_COLORS[kind]["taxi" if not self.v85 else self.theme]
            icon = self.icon(NOTE_ICONS[kind], 20 if self.v85 else 16, mark, "ⓘ")
            parts.append(f'<div class="note stretch" style="background:{ground}"><span class="ni">{icon}</span>'
                         f'<span class="nt">{esc(message.get("text"))}</span></div>')
        return "".join(parts)

    def taxi_cell(self, picture: str, glyph: str) -> str:
        """A field button of "Taxi": the platform picture when the assets have it, a glyph otherwise."""
        if picture in self.assets.taxi_pictures:
            return f'<span class="p-{picture}"></span>'
        return f"<span>{glyph}</span>"

    def tags(self, value: Any) -> str | None:
        items = value.get("items") if isinstance(value, dict) else None
        if not isinstance(items, list) or not all(isinstance(item, str) for item in items):
            return None
        parts = [f'<span class="tag-item">{esc(item)}</span>' for item in items]
        more = value.get("more")
        if type(more) is int and more > 0:
            parts.append(f'<span class="tag-more">+{more}</span>')
        return f'<span class="tags">{"".join(parts)}</span>'

    def edit(self, n: dict, caption: str | None, info: dict | None = None, stretch: str = "fixed",
             placement: str = "Auto") -> str:
        value = self.value_of(n)
        tags = self.tags(value) if not n.get("passwordMode") else None
        text = "" if value is None or tags is not None else str(value)
        lines = max(1, int(n.get("height") or 1))
        classes = ["inp"]
        if n.get("readOnly") or n.get("groupReadOnly"):
            classes.append("ro")
        disabled = self.disabled(n) or n.get("groupEnabled") is False
        if disabled:
            classes.append("dis")
        width = self.char_px(n.get("width") or 20)
        style = f"min-width:min(100%,{width}px)" if self.v85 else ""
        if lines > 1:
            classes.append("multi")
            style += f"{';' if style else ''}height:calc(var(--in-h) + {lines - 1} * var(--in-lh))"
        if n.get("passwordMode") and text:
            text = "●" * min(len(text), 8) if len(text) > 1 else "●"
        if tags is not None:
            inner = f'<span class="v">{tags}</span>'
        elif text or not n.get("inputHint"):
            inner = f'<span class="v">{esc(text)}</span>'
        else:
            inner = f'<span class="v ph">{esc(n["inputHint"])}</span>'
        buttons = ""
        # A read-only field drops its drop-down and calendar buttons; the open button stays.
        editable = not (n.get("readOnly") or n.get("groupReadOnly"))
        dated = editable and model_of(n).get("valueKind") in ("date", "dateTime")
        if not self.v85:
            cells = []
            choice = n.get("choiceButton") and (n.get("openButton") or tags is not None)
            if editable and not disabled and (n.get("dropListButton") or choice):
                cells.append(self.taxi_cell("editDropDown", "▼"))
            elif dated and not disabled:
                cells.append(self.taxi_cell("editCalendar", "▦"))
            if n.get("openButton") and not disabled:
                cells.append(self.taxi_cell("editOpen", "⧉"))
            spin = n.get("spinButton")
            if cells or spin:
                classes.append("btns")
            if spin:
                buttons = ('<span class="tb spin2">' + self.taxi_cell("editRegUp", "▲")
                           + self.taxi_cell("editRegDown", "▼") + "</span>")
            elif cells:
                buttons = '<span class="tb">' + "".join(cells) + "</span>"
            if spin and text:
                inner = f'<span class="v" style="text-align:right;padding-right:4px">{esc(text)}</span>'
            box = f'<div class="{" ".join(classes)}" style="{style}">{inner}{buttons}</div>'
            compact = "" if self.wants_width(n) else " compact"
            box = f'<div class="tedit{compact}" style="--field-w:{width}px">{box}</div>'
            if caption is None and info is None:
                return f'<div class="fld {stretch}">{box}</div>'
            return self.labeled(box, caption, info, stretch, False, placement)
        choice = n.get("choiceButton") and (n.get("openButton") or tags is not None)
        if editable and not disabled and (n.get("dropListButton") or choice):
            buttons += '<span class="ib">' + self.icon("svg_cornerBottom", 20, "var(--icon-muted)", "▾") + "</span>"
        elif dated and not disabled:
            symbol = "svg_calendarClock" if model_of(n).get("valueKind") == "dateTime" else "svg_calendar"
            buttons += '<span class="ib">' + self.icon(symbol, 20, "var(--icon-muted)", "▦") + "</span>"
        outside = ""
        if n.get("openButton") and not disabled:
            outside += '<span class="openb">' + self.icon("svg_open", 24, "var(--icon-muted)", "↗") + "</span>"
        if n.get("spinButton"):
            outside += ('<span class="spins"><span class="spin">' + self.icon("svg_minus", 24, "var(--icon-muted)", "−")
                        + '</span><span class="spin">' + self.icon("svg_plus", 24, "var(--icon-muted)", "+") + "</span></span>")
        if outside:
            # The width the platform gives a field covers its buttons outside the box as well.
            width = style.split(";")[0]
            style = ";".join(["min-width:0"] + style.split(";")[1:])
            box = f'<div class="{" ".join(classes)}" style="{style}">{inner}{buttons}</div>'
            box = f'<div class="frow" style="{width}">{box}{outside}</div>'
        else:
            box = f'<div class="{" ".join(classes)}" style="{style}">{inner}{buttons}</div>'
        required = bool(n.get("markRequiredComplete"))
        if caption is None and info is None:
            return f'<div class="fld {stretch}">{box}</div>'
        return self.labeled(box, caption, info, stretch, required, placement)

    def checkbox(self, n: dict, caption: str | None, info: dict | None = None, *, switcher: bool = False) -> str:
        value = self.value_of(n)
        disabled = self.disabled(n) or n.get("groupEnabled") is False
        if switcher:
            if value is None:
                value = n.get("value", model_of(n).get("value"))
            text_value = str(value).lower()
            part = self.v85 and text_value in ("2", "partial", "частично", "indeterminate", "неопределено")
            on = not part and (value is True or text_value in ("true", "да", "1", "yes"))
            state = "mixed" if part else ("true" if on else "false")
            kind = "switch part" if part else ("switch on" if on else "switch")
            disabled_attr = ' aria-disabled="true"' if disabled else ""
            box = f'<span class="{kind}" role="switch" aria-checked="{state}"{disabled_attr}></span>'
        else:
            box = self.check_box(value)
        if info is None:
            info_html = ""
        elif self.v85:
            info_html = ('<span class="chk-info">'
                         + self.icon("svg_infoSmall", 16, "var(--icon-muted)", "ⓘ") + "</span>")
        else:
            info_html = '<span class="help" style="margin:0 0 0 8px">?</span>'
        margin = 9 if self.v85 else 6 if switcher else 9
        text = f'<span class="t" style="margin-left:{margin}px">{esc(caption)}</span>' if caption else ""
        switch_style = " switchrow" if switcher else ""
        disabled_style = " dis" if disabled else ""
        state = ' aria-disabled="true"' if disabled and self.v85 else ""
        return f'<div class="chkrow fixed{switch_style}{disabled_style}"{state}>{box}{text}{info_html}</div>'

    def tumbler(self, n: dict) -> str:
        items = [i for i in (n.get("items") or []) if isinstance(i, dict)]
        value = self.value_of(n)
        chosen = 0
        for index, item in enumerate(items):
            if value is not None and (str(value) == str(item.get("title")) or str(value) == str(index)):
                chosen = index
        if isinstance(value, bool):
            chosen = 0 if value else min(1, len(items) - 1)
        titles = [str(item.get("title") or "") for item in items]
        fits = sum(len(t) for t in titles) + 2 * len(titles) <= self.field_chars(n) + 4
        if self.v85 and items and not fits:
            # Items that do not fit: the 8.5 client shows the chosen one with a drop-down arrow.
            options = "".join(f'<span class="{"" if i == chosen else "off"}">{esc(t)}</span>' for i, t in enumerate(titles))
            return ('<div class="tdrop"><span class="opts">' + options + "</span>"
                    + self.icon("svg_cornerBottom", 20, "var(--icon)", "▾") + "</div>")
        longest = max(range(len(titles)), key=lambda i: len(titles[i])) if titles else -1
        # When the items do not fit, the client cuts the longest one short and keeps the others whole.
        parts = "".join(f'<span class="ti{" on" if i == chosen else ""}" style="flex-shrink:{1 if i == longest else 0}">'
                        f'{esc(t)}</span>' for i, t in enumerate(titles))
        full = "" if "columnsCount" in n else " full"
        # Items share the width equally, but none gets narrower than its title while they all fit.
        fit = " fit" if fits else ""
        return f'<div class="tfit{full}"><div class="tumbler{fit}">{parts}</div></div>'

    def field_chars(self, n: dict) -> int:
        """Width in characters the column of an element gives it: its widest sibling field, 40 by default."""
        return 40

    def radios(self, n: dict) -> str:
        items = [i for i in (n.get("items") or []) if isinstance(i, dict)]
        if not items and isinstance(n.get("titles"), list):
            items = [{"title": t} for t in n["titles"]]
        value = self.value_of(n)
        horizontal = n.get("orientation") == "Horizontal"
        parts = []
        for index, item in enumerate(items):
            on = (value is None and index == 0) or str(value) == str(item.get("title")) or str(value) == str(index)
            parts.append(f'<span class="radio"><span class="rdot{" on" if on else ""}"></span>{esc(item.get("title"))}</span>')
        return f'<div class="radios{" h" if horizontal else ""}">{"".join(parts)}</div>'

    def button(self, n: dict) -> str:
        """A button placed on the form: 8.5 draws a supplementary one as a link, the others as buttons."""
        text = self.value_of(n)
        text = n.get("title") if text is None else text
        if not text:
            return ""
        state = ' aria-disabled="true"' if self.disabled(n) or n.get("groupEnabled") is False else ""
        if not self.v85:
            return f'<span class="tbtn fixed"{state} style="align-self:flex-start">{esc(text)}</span>'
        if n.get("buttonImportance") in ("Supplementary", None) or n.get("hyperlink"):
            return f'<span class="blink fixed"{state}>{esc(text)}</span>'
        main = " main" if n.get("defaultButton") or n.get("buttonImportance") == "Main" else ""
        return f'<span class="btn{main} fixed"{state} style="align-self:flex-start">{esc(text)}</span>'

    def static_text(self, n: dict) -> str:
        text = self.value_of(n)
        text = n.get("title") if text is None else text
        if not text and not self.v85 and n.get("minWidth"):
            return '<div class="tblank" aria-hidden="true"></div>'
        if not text:
            return ""
        command_link = lay_name(n).endswith("#text") and "command" in model_of(n)
        link = " link" if n.get("hyperlink") or command_link else ""
        style = ""
        font = n.get("font") if isinstance(n.get("font"), dict) else {}
        if font.get("bold"):
            style += "font-family:var(--font-medium);"
        return f'<div class="deco{link}" style="{style}">{esc(text)}</div>'

    def image(self, n: dict) -> str:
        if lay_name(n).endswith(("#extTooltipBtn", "#extTooltip")):
            return ""
        if not n.get("height") and n.get("pictureSizeMode") == "Stretch" and not model_of(n).get("dataPath"):
            # A configuration picture stretched along a row with no height of its own: a separator line.
            return '<div class="sep stretch"></div>' if self.v85 else '<div class="stretch" style="height:6px"></div>'
        if (n.get("width") or 0) <= 8 and (n.get("height") or 0) <= 2:
            return '<div class="avatar fixed"></div>'
        text = n.get("nonselectedPictureText") or ""
        return f'<div class="pic stretch vgrow">{esc(text)}</div>'

    def group(self, n: dict) -> str:
        kids = children(n)
        title = next((k for k in kids if node_class(k) == "HippoLayStaticText" and lay_name(k).endswith("#title")), None)
        rest = [k for k in kids if k is not title]
        if n.get("role") == "TableGroupRole":
            return self.table_group(n)
        horizontal = str(n.get("childrenGrouping") or "Vertical").startswith(("Horizontal", "AlwaysHorizontal"))
        shown = [k for k in rest if self.visible(k)]
        parts = [self.node(k) for k in shown]
        if horizontal and self.v85 and parts and not any(self.wants_width(k) for k in shown):
            # Nothing in the row stretches: the client gives the spare width to the last element.
            parts[-1] = f'<div class="grow col">{parts[-1]}</div>'
        # Messages of the group itself come first, those of an empty group stand where that group is.
        # cells: (html, notes or not, takes height) in drawing order.
        cells = [] if horizontal else [(self.notes(item_name(n)), True, False)]
        for kid, part in zip(shown, parts):
            if not horizontal:
                cells.append((self.notes_at(item_name(n), item_name(kid)), True, False))
            cells.append((part, False, self.wants_height(kid)))
        if not horizontal:
            cells.append((self.notes_at(item_name(n), None), True, False))
        inner = "".join(html for html, _, _ in cells)
        if not inner:
            return ""
        layout = ("row" if self.v85 else "row tcols") if horizontal else ("col" if self.v85 else "col lgrid")
        stretch = self.stretch_class(n) if n.get("horStretchable") is not False else "fixed"
        vgrow = " vgrow" if self.wants_height(n) else ""
        styles = []
        if vgrow and layout == "col lgrid":
            # One grid row per drawn child and message: the first child that takes height gets the spare.
            row = 0
            for html, is_notes, grows in cells:
                if grows and html:
                    row += int(bool(n.get("showTitle") and title is not None and not n.get("showAsCard")))
                    styles.append(f"grid-template-rows:{f'repeat({row},auto) ' if row else ''}1fr")
                    break
                row += html.count('<div class="note ') if is_notes else (1 if html else 0)
        spacing = VERTICAL_SPACING.get(n.get("verticalSpacing") or "")
        if spacing and self.v85 and not horizontal:
            styles.append(f"gap:{spacing}")
        rows = f' style="{";".join(styles)}"' if styles else ""
        if n.get("showAsCard") and not n.get("showTitle"):
            return f'<div class="card {stretch}{vgrow}"><div class="{layout}"{rows}>{inner}</div></div>'
        if n.get("showAsCard") and n.get("showTitle"):
            caption = title.get("title") if title else ""
            if self.v85:
                return (f'<div class="card {stretch}{vgrow}"><div class="ctitle">{esc(caption)}</div>'
                        f'<div class="{layout}"{rows}>{inner}</div></div>')
            return (f'<div class="card {stretch}"><div class="ctitle">{esc(caption)}</div>'
                    f'<div class="{layout}"{rows}>{inner}</div></div>')
        if n.get("showTitle") and title is not None:
            big = " big" if self.v85 and n.get("representation") == "NoneBigTitle" else ""
            heading = f'<div class="gtitle{big}">{esc(title.get("title"))}</div>'
            if not self.v85 and not horizontal:
                return f'<div class="{layout} titled {stretch}{vgrow}"{rows}>{heading}{inner}</div>'
            inner = f'{heading}<div class="{layout}"{rows}>{inner}</div>'
            return f'<div class="col {stretch}">{inner}</div>'
        return f'<div class="{layout} {stretch}{vgrow}"{rows}>{inner}</div>'

    def table_group(self, n: dict, mark: str = "") -> str:
        bar, table = None, None
        for kid in children(n):
            if node_class(kid) == "HippoLayCommandBar" and bar is None:
                bar = kid
            elif node_class(kid) == "HippoLayTable":
                table = kid
        parts = []
        if bar is not None and self.visible(bar):
            parts.append(self.table_bar(bar))
        if table is not None:
            parts.append(self.table(table))
        vgrow = " vgrow" if table is not None and table.get("stretchableMode") in ("Ver", "Any") else ""
        if mark:
            return f'<div class="trow stretch{vgrow}"><div class="tblock{vgrow}">{"".join(parts)}</div>{mark}</div>'
        return f'<div class="tblock stretch{vgrow}">{"".join(parts)}</div>'

    def table_sample(self, n: dict) -> Any:
        return self.data.table([item_name(n), node_name(n), model_of(n).get("dataPath")])

    def table_rows(self, n: dict) -> list:
        rows = self.table_sample(n)
        if isinstance(rows, dict):
            rows = rows.get("rows")
        return rows if isinstance(rows, list) else []

    def table_head(self, n: dict, checks: bool) -> tuple[list, dict, str]:
        def read(parent: dict) -> list:
            headings = []
            for child in children(parent):
                if not self.visible(child) or child.get("invisible") is True:
                    continue
                if node_class(child) == "HippoLayTableColumn":
                    headings.append((child, [child], []))
                elif node_class(child) == "HippoLayTableColumnsGroup":
                    nested = read(child)
                    if not nested:
                        continue
                    if child.get("grouping") == "InCell":
                        members = leaf_columns(nested)
                        caption = next((member for member in members if member.get("showInHeader") is not False
                                        and (member.get("title") or member.get("headerPicture"))), members[0])
                        if child.get("showInHeader") and child.get("title"):
                            caption = child
                        headings.append((caption, members, []))
                    elif child.get("showInHeader") is not False and child.get("title"):
                        headings.append((child, [], nested))
                    else:
                        headings.extend(nested)
            return headings

        def leaf_columns(headings: list) -> list:
            return [column for _, members, nested in headings for column in (members or leaf_columns(nested))]

        def depth(headings: list) -> int:
            return max((1 + depth(nested) for _, _, nested in headings), default=0)

        def write(headings: list, level: int) -> int:
            count = 0
            for heading, members, nested in headings:
                if nested:
                    span = write(nested, level + 1)
                    rows[level].append(f'<th colspan="{span}">{esc(heading.get("title"))}</th>')
                    count += span
                else:
                    columns.append(members[0])
                    if len(members) > 1:
                        together[id(members[0])] = members[1:]
                    rows[level].append(self.table_heading(heading, len(rows) - level))
                    count += 1
            return count

        headings = read(n)
        rows: list[list[str]] = [[] for _ in range(depth(headings))]
        columns, together = [], {}
        write(headings, 0)
        if rows and checks:
            rowspan = f' rowspan="{len(rows)}"' if len(rows) > 1 else ""
            rows[0].insert(0, f'<th class="sel"{rowspan}><span class="chk"></span></th>')
        header = "" if not rows or n.get("showHeader") is False else (
            '<thead>' + ''.join(f'<tr>{"".join(row)}</tr>' for row in rows) + '</thead>')
        return columns, together, header

    def table_heading(self, column: dict, span: int) -> str:
        attrs = f' rowspan="{span}"' if span > 1 else ""
        if column.get("showInHeader") is False:
            return f'<th{attrs}></th>'
        picture = column.get("headerPicture")
        title = column.get("title") or ""
        picture_renderer = self.command_icon if self.v85 else self.taxi_command_picture
        icon = picture_renderer({"picture": picture}) if picture else ""
        if picture and not icon and column.get("columnMode") == "Check":
            icon = '<span class="check-heading" aria-hidden="true">✓</span>'
        if icon and not title:
            label = esc(column.get("invisibleTitle") or column.get("tooltip") or "")
            attrs += f' class="picture-heading" aria-label="{label}"'
        if self.v85 and (model_of(column).get("dataPath") or "").endswith(("LineNumber", "НомерСтроки")):
            attrs += ' style="width:77px"'
        return f'<th{attrs}>{icon}{esc(title)}</th>'

    def taxi_table_widths(self, n: dict, columns: list, together: dict) -> tuple[str, str]:
        def layout_width(chars: Any) -> int:
            # A column's character width excludes the controls and chrome of a standalone editor.
            try:
                return max(0, round(float(chars) * 7.5 + 13))
            except (TypeError, ValueError, OverflowError):
                return 0

        def width(column: dict) -> int:
            title = str(column.get("title") or "")
            declared = layout_width(column.get("width"))
            if declared and column.get("spinButton"):
                declared = max(declared, self.char_px(column["width"]))
            if column.get("columnMode") == "Check":
                # Include the header padding even when the cell itself is only a check box.
                return max(34, declared, layout_width(len(title) + 2) if title else 0)
            if declared and column.get("stretchableMode") in ("Hor", "Any"):
                return declared
            return max(34, declared, layout_width(len(title)))

        widths = []
        stretch, text = None, None
        for index, column in enumerate(columns):
            members = [column, *together.get(id(column), [])]
            widths.append(sum(width(member) for member in members))
            if any(member.get("stretchableMode") in ("Hor", "Any") for member in members):
                stretch = index
            if any(member.get("columnMode") != "Check" for member in members):
                text = index

        minimum = max(sum(widths), layout_width(n.get("width")))
        # The unassigned column takes the remaining width without enlarging the check columns.
        flexible = stretch if stretch is not None else text
        style = f'min-width:{minimum}px'
        if flexible is None and widths:
            style = f'width:{sum(widths)}px'
        cols = ''.join('<col>' if index == flexible else f'<col style="width:{pixels}px">'
                       for index, pixels in enumerate(widths))
        return style, f'<colgroup>{cols}</colgroup>' if cols else ""

    def table(self, n: dict) -> str:
        checks = self.v85 and n.get("rowsSelectionMode") != "SingleRow"
        columns, together, header = self.table_head(n, checks)
        spec = self.table_sample(n)
        current = spec.get("current") if isinstance(spec, dict) else None
        if current is None and not self.v85:
            current = 0  # "Taxi" marks the current row even when the table has no focus, the first by default
        rows = self.table_rows(n)
        tree = n.get("representation") == "Tree"
        tree_col = next((i for i, col in enumerate(columns)
                         if col.get("columnMode") != "Check" or id(col) in together), 0)
        keys_by_col = []
        for c in columns:
            name = item_name(c)
            short = name[len(item_name(n)):] if name.startswith(item_name(n)) else name
            path = model_of(c).get("dataPath") or ""
            keys_by_col.append([name, short, path.rsplit(".", 1)[-1] if path else None])
        body = []
        for index, row in enumerate(rows):
            cells = []
            level, opened = 0, None
            if isinstance(row, dict):
                level = int(row.get("_level") or 0)
                opened = row.get("_open")
            tr_class = "cur" if current == index or (isinstance(row, dict) and row.get("_current")) else ""
            if checks:
                cells.append(f'<td class="sel">{self.check_box(row.get("_check") if isinstance(row, dict) else None)}</td>')
            for ci, (c, keys) in enumerate(zip(columns, keys_by_col)):
                value = self.cell_value(row, keys, ci)
                path = keys[2] or ""
                numeric = path.endswith(("LineNumber", "НомерСтроки"))
                cls = "num" if numeric else ""
                prefix = ""
                if tree and ci == tree_col:
                    pad = level * 28
                    sym = {True: "svg_cornerBottom", False: "svg_cornerRight"}.get(opened)
                    fb = {True: "▾", False: "▸"}.get(opened, "")
                    twist = self.icon(sym, 16, "var(--icon-muted)", fb) if sym else ""
                    prefix = f'<span class="tw" style="margin-left:{pad}px">{twist}</span>'
                if c.get("columnMode") == "Check" or id(c) in together:
                    parts = []
                    for member in [c] + together.get(id(c), []):
                        mname = item_name(member)
                        mpath = model_of(member).get("dataPath") or ""
                        mkeys = [mname, mname[len(item_name(n)):], mpath.rsplit(".", 1)[-1] if mpath else None]
                        mvalue = value if member is c else (row.get(next((k for k in mkeys if k and isinstance(row, dict)
                                                                          and k in row), ""), "")
                                                           if isinstance(row, dict) else "")
                        if member.get("columnMode") == "Check":
                            parts.append(self.check_box(mvalue))
                        else:
                            tags = self.tags(mvalue)
                            parts.append(tags if tags is not None else f'<span>{esc(mvalue)}</span>')
                    cells.append(f'<td><span class="tcell-members">{prefix}{"".join(parts)}</span></td>')
                    continue
                link = " link" if c.get("cellHyperlink") or (isinstance(row, dict) and row.get("_link") == keys[0]) else ""
                tags = self.tags(value)
                cells.append(f'<td class="{cls}{link}">{prefix}{tags if tags is not None else esc(value)}</td>')
            body.append(f'<tr class="{tr_class}">{"".join(cells)}</tr>')
        minimum = max(int(n.get("height") or 3), 1)
        filler = ""
        if len(rows) < minimum:
            filler = (f'<tr class="fill"><td colspan="{len(columns) + (1 if checks else 0)}" '
                      f'style="height:calc({minimum - len(rows)} * var(--row-h))"></td></tr>')
        if self.v85:
            width = self.char_px(n.get("width") or 40)
            sampled_rows = spec.get("rows") if isinstance(spec, dict) else spec
            empty = ""
            if sampled_rows == []:
                label = "No items" if str(self.view.get("languageCode", "ru")).startswith("en") else "Список пуст"
                empty = f'<div class="grid-empty">{label}</div>'
                filler = ""
            return (f'<div class="tbox"><table class="grid" style="min-width:{width}px">{header}'
                    f'<tbody>{"".join(body)}{filler}</tbody></table></div>{empty}')
        style, colgroup = self.taxi_table_widths(n, columns, together)
        return (f'<div class="tbox"><table class="grid" style="{style}">{colgroup}{header}'
                f'<tbody>{"".join(body)}{filler}</tbody></table></div>')

    def cell_value(self, row: Any, keys: list, index: int) -> Any:
        if isinstance(row, (list, tuple)):
            return row[index] if index < len(row) else ""
        if isinstance(row, dict):
            return next((row[k] for k in keys if k and k in row), "")
        return row if index == 0 else ""


def build(view: dict, *, interface: str = "v85", theme: str = "light", page: str | None = None,
          data: Any = None, assets: Assets | None = None, density: str | None = None,
          width: int | None = None, height: int | None = None) -> Rendered:
    """Turn a client view model (edt_form_render engine "model") into an HTML page."""
    if interface not in INTERFACES:
        raise ValueError(f"interface must be one of {', '.join(INTERFACES)}")
    if theme not in THEMES:
        raise ValueError(f"theme must be one of {', '.join(THEMES)}")
    if interface == "taxi" and theme == "dark":
        raise ValueError("the platform has no dark theme for Taxi: use theme light, or interface v85")
    for dimension, value in (("width", width), ("height", height)):
        if value is not None and (type(value) is not int or value < 1):
            raise ValueError(f"{dimension} must be a positive integer")
    drawing = FormHtml(view, interface, theme, page, data, assets, density, width, height)
    return drawing.render()


def normalize_interface(value: Any) -> str:
    """v85 / taxi from the spellings callers use: VERSION8_5, 8.5, Taxi."""
    text = re.sub(r"[^a-z0-9]", "", str(value or "v85").lower())
    if text in ("taxi", "taxi83", "v83", "83"):
        return "taxi"
    if text in ("v85", "85", "version85", "version8_5", "versio85"):
        return "v85"
    if "taxi" in text:
        return "taxi"
    if "85" in text:
        return "v85"
    raise ValueError(f"unknown interface {value!r}: use v85 or taxi")

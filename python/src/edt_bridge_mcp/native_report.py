"""Prepare a fixed managed EPF that opens the selected form of an external object in its test client.

A report has to be opened this way: the client cannot open an .erf on its own. An external data
processor is opened this way when one run plays several named scenarios: the launcher stays open behind
the form, and its command EdtBridgeReopen closes the form and opens it again for the next scenario.
"""

from __future__ import annotations

import json
import re
from pathlib import Path
from uuid import uuid4

#: The data processor that opens a report, and the one that opens a data processor for named scenarios.
REPORT_LAUNCHER = "EdtBridgeReportLoader"
FORM_LAUNCHER = "EdtBridgeFormLauncher"
#: The button of the launcher form that closes the form it opened and opens it again.
REOPEN_BUTTON = "EdtBridgeReopen"


def _bsl_string(value):
    return ('"' + value.replace('"', '""').replace('\r', '" + Chars.CR + "')
            .replace('\n', '" + Chars.LF + "') + '"')


def launcher_form(name):
    """The full name of the launcher form, as the test client names the forms it finds."""
    return f"ExternalDataProcessor.{name}.Form.Main"


def write_launcher(root, external, form_name, format_version, *, kind="ExternalReport"):
    """Write the launcher sources into root; returns the root XML file to build it from.

    kind is ExternalReport or ExternalDataProcessor. A report is connected in safe mode, as before; a
    data processor in unsafe mode, as the client runs an .epf it opens itself."""
    if not re.fullmatch(r"[0-9]+\.[0-9]+", format_version):
        raise ValueError("The platform exported an unsupported metadata format version.")
    if kind not in ("ExternalReport", "ExternalDataProcessor"):
        raise ValueError(f"Unsupported external object kind: {kind}.")
    name = REPORT_LAUNCHER if kind == "ExternalReport" else FORM_LAUNCHER
    request = root / "report-request.json"
    request.write_text(json.dumps({"file": str(external), "formName": form_name, "kind": kind}),
                       encoding="utf-8", newline="")
    directory = root / f"{name}/Forms/Main/Ext"
    (directory / "Form").mkdir(parents=True)
    source = root / f"{name}.xml"
    source.write_text(f'''<?xml version="1.0" encoding="UTF-8"?>
<MetaDataObject xmlns="http://v8.1c.ru/8.3/MDClasses"
 xmlns:xr="http://v8.1c.ru/8.3/xcf/readable" version="{format_version}">
 <ExternalDataProcessor uuid="{uuid4()}">
  <InternalInfo>
   <xr:ContainedObject><xr:ClassId>c3831ec8-d8d5-4f93-8a22-f9bfae07327f</xr:ClassId>
    <xr:ObjectId>{uuid4()}</xr:ObjectId></xr:ContainedObject>
   <xr:GeneratedType name="ExternalDataProcessorObject.{name}" category="Object">
    <xr:TypeId>{uuid4()}</xr:TypeId><xr:ValueId>{uuid4()}</xr:ValueId>
   </xr:GeneratedType>
  </InternalInfo>
  <Properties><Name>{name}</Name><Synonym/><Comment/>
   <DefaultForm>ExternalDataProcessor.{name}.Form.Main</DefaultForm><AuxiliaryForm/>
  </Properties><ChildObjects><Form>Main</Form></ChildObjects>
 </ExternalDataProcessor>
</MetaDataObject>
''', encoding="utf-8", newline="")
    (directory.parent.parent / "Main.xml").write_text(f'''<?xml version="1.0" encoding="UTF-8"?>
<MetaDataObject xmlns="http://v8.1c.ru/8.3/MDClasses" version="{format_version}">
 <Form uuid="{uuid4()}"><Properties><Name>Main</Name><Synonym/><Comment/>
  <FormType>Managed</FormType><IncludeHelpInContents>false</IncludeHelpInContents>
 </Properties></Form>
</MetaDataObject>
''', encoding="utf-8", newline="")
    (directory / "Form.xml").write_text(f'''<?xml version="1.0" encoding="UTF-8"?>
<Form xmlns="http://v8.1c.ru/8.3/xcf/logform"
 xmlns:cfg="http://v8.1c.ru/8.1/data/enterprise/current-config"
 xmlns:v8="http://v8.1c.ru/8.1/data/core" xmlns:xs="http://www.w3.org/2001/XMLSchema" version="{format_version}">
 <Title><v8:item><v8:lang>en</v8:lang><v8:content>Report launcher</v8:content></v8:item>
  <v8:item><v8:lang>ru</v8:lang><v8:content>Загрузчик отчета</v8:content></v8:item></Title>
 <AutoTitle>false</AutoTitle><AutoCommandBar name="FormCommandBar" id="-1"/>
 <Events><Event name="OnCreateAtServer">OnCreateAtServer</Event><Event name="OnOpen">OnOpen</Event></Events>
 <ChildItems>
  <Button name="{REOPEN_BUTTON}" id="1">
   <Type>UsualButton</Type>
   <CommandName>Form.Command.{REOPEN_BUTTON}</CommandName>
   <ExtendedTooltip name="{REOPEN_BUTTON}ExtendedTooltip" id="2"/>
  </Button>
 </ChildItems>
 <Attributes><Attribute name="Object" id="1">
  <Type><v8:Type>cfg:ExternalDataProcessorObject.{name}</v8:Type></Type>
  <MainAttribute>true</MainAttribute>
 </Attribute>
 <Attribute name="ReportName" id="2"><Type><v8:Type>xs:string</v8:Type></Type></Attribute>
 <Attribute name="MainFormName" id="3"><Type><v8:Type>xs:string</v8:Type></Type></Attribute>
 <Attribute name="TargetKind" id="4"><Type><v8:Type>xs:string</v8:Type></Type></Attribute>
 </Attributes>
 <Commands>
  <Command name="{REOPEN_BUTTON}" id="1">
   <Title><v8:item><v8:lang>en</v8:lang><v8:content>Reopen the form</v8:content></v8:item>
    <v8:item><v8:lang>ru</v8:lang><v8:content>Открыть форму заново</v8:content></v8:item></Title>
   <Action>{REOPEN_BUTTON}</Action>
  </Command>
 </Commands>
</Form>
''', encoding="utf-8", newline="")
    template = Path(__file__).with_name("native_report_loader.bsl").read_text(encoding="utf-8-sig")
    module = template.replace('"@REQUEST_PATH@"', _bsl_string(str(request)))
    (directory / "Form/Module.bsl").write_text(module, encoding="utf-8-sig", newline="")
    return source

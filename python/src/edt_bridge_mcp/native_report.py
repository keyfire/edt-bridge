"""Prepare a fixed managed EPF that opens the selected report in its test client."""

from __future__ import annotations

import json
import re
from pathlib import Path
from uuid import uuid4


def _bsl_string(value):
    return ('"' + value.replace('"', '""').replace('\r', '" + Chars.CR + "')
            .replace('\n', '" + Chars.LF + "') + '"')


def write_launcher(root, external, form_name, format_version):
    if not re.fullmatch(r"[0-9]+\.[0-9]+", format_version):
        raise ValueError("The platform exported an unsupported metadata format version.")
    request = root / "report-request.json"
    request.write_text(json.dumps({"file": str(external), "formName": form_name}),
                       encoding="utf-8", newline="")
    directory = root / "EdtBridgeReportLoader/Forms/Main/Ext"
    (directory / "Form").mkdir(parents=True)
    source = root / "EdtBridgeReportLoader.xml"
    source.write_text(f'''<?xml version="1.0" encoding="UTF-8"?>
<MetaDataObject xmlns="http://v8.1c.ru/8.3/MDClasses"
 xmlns:xr="http://v8.1c.ru/8.3/xcf/readable" version="{format_version}">
 <ExternalDataProcessor uuid="{uuid4()}">
  <InternalInfo>
   <xr:ContainedObject><xr:ClassId>c3831ec8-d8d5-4f93-8a22-f9bfae07327f</xr:ClassId>
    <xr:ObjectId>{uuid4()}</xr:ObjectId></xr:ContainedObject>
   <xr:GeneratedType name="ExternalDataProcessorObject.EdtBridgeReportLoader" category="Object">
    <xr:TypeId>{uuid4()}</xr:TypeId><xr:ValueId>{uuid4()}</xr:ValueId>
   </xr:GeneratedType>
  </InternalInfo>
  <Properties><Name>EdtBridgeReportLoader</Name><Synonym/><Comment/>
   <DefaultForm>ExternalDataProcessor.EdtBridgeReportLoader.Form.Main</DefaultForm><AuxiliaryForm/>
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
 <Attributes><Attribute name="Object" id="1">
  <Type><v8:Type>cfg:ExternalDataProcessorObject.EdtBridgeReportLoader</v8:Type></Type>
  <MainAttribute>true</MainAttribute>
 </Attribute>
 <Attribute name="ReportName" id="2"><Type><v8:Type>xs:string</v8:Type></Type></Attribute>
 <Attribute name="MainFormName" id="3"><Type><v8:Type>xs:string</v8:Type></Type></Attribute>
 </Attributes>
</Form>
''', encoding="utf-8", newline="")
    template = Path(__file__).with_name("native_report_loader.bsl").read_text(encoding="utf-8-sig")
    module = template.replace('"@REQUEST_PATH@"', _bsl_string(str(request)))
    (directory / "Form/Module.bsl").write_text(module, encoding="utf-8-sig", newline="")
    return source

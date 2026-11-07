#If Server Or ThickClientManagedApplication Then

#Region FormEventHandlers

&AtServer
Procedure OnCreateAtServer(Cancel, StandardProcessing)
    Reader = New JSONReader;
    Reader.OpenFile("@REQUEST_PATH@");
    Request = ReadJSON(Reader);
    Reader.Close();

    Address = PutToTempStorage(New BinaryData(Request.file), UUID);
    ReportName = ExternalReports.Connect(Address, , True);
    MainFormName = Request.formName;
EndProcedure

&AtClient
Procedure OnOpen(Cancel)
    OpenForm("ExternalReport." + ReportName + ".Form." + MainFormName, , , True);
EndProcedure

#EndRegion

#EndIf

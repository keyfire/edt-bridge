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
    FormPath = "ExternalReport." + ReportName + ".Form";
    If Not IsBlankString(MainFormName) Then
        FormPath = FormPath + "." + MainFormName;
    EndIf;
    OpenForm(FormPath, , , True);
EndProcedure

#EndRegion

#EndIf

#If Server Or ThickClientManagedApplication Then

#Region Variables

// The form this launcher opened last, closed by EdtBridgeReopen before it opens the form again.
&AtClient
Var OpenedForm;

#EndRegion

#Region FormEventHandlers

&AtServer
Procedure OnCreateAtServer(Cancel, StandardProcessing)
    Reader = New JSONReader;
    Reader.OpenFile("@REQUEST_PATH@");
    Request = ReadJSON(Reader);
    Reader.Close();

    Address = PutToTempStorage(New BinaryData(Request.file), UUID);
    TargetKind = Request.kind;
    If TargetKind = "ExternalDataProcessor" Then
        // The client runs an .epf it opens itself in unsafe mode; the check needs the same.
        Protection = New UnsafeOperationProtectionDescription;
        Protection.UnsafeOperationWarnings = False;
        ReportName = ExternalDataProcessors.Connect(Address, , False, Protection);
    Else
        ReportName = ExternalReports.Connect(Address, , True);
    EndIf;
    MainFormName = Request.formName;
EndProcedure

&AtClient
Procedure OnOpen(Cancel)
    OpenTarget();
EndProcedure

#EndRegion

#Region FormCommandsEventHandlers

// The test manager presses it between named scenarios: the next scenario starts on a new form.
&AtClient
Procedure EdtBridgeReopen(Command)
    If OpenedForm <> Undefined And OpenedForm.IsOpen() Then
        OpenedForm.Close();
    EndIf;
    OpenTarget();
EndProcedure

#EndRegion

#Region Private

&AtClient
Procedure OpenTarget()
    FormPath = TargetKind + "." + ReportName + ".Form";
    If Not IsBlankString(MainFormName) Then
        FormPath = FormPath + "." + MainFormName;
    EndIf;
    OpenedForm = OpenForm(FormPath, , , True);
EndProcedure

#EndRegion

#EndIf

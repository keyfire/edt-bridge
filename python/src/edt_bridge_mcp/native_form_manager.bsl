#Область ОбработчикиСобытийПриложения

Procedure OnStart()
    If IsBlankString(LaunchParameter) Then
        Return;
    EndIf;
    Reader = New JSONReader;
    Reader.OpenFile(LaunchParameter);
    Request = ReadJSON(Reader);
    Reader.Close();

    Report = New Structure("status,stage,clickAttempted,clickReturned", "driver_failed", "connect", False, False);
    Try
        RunCheck(Request, Report);
    Except
        Report.Insert("error", ОбработкаОшибок.ПодробноеПредставлениеОшибки(ИнформацияОбОшибке()));
    EndTry;

    SaveReport(Report, Request.result);
EndProcedure

#КонецОбласти

#Область СлужебныеПроцедурыИФункции

Procedure RunCheck(Request, Report)
    Application = New TestedApplication("127.0.0.1", Request.port);
    Application.Connect();

    Report.stage = "form_lookup";
    FormType = Type("TestedForm");
    Application.WaitForObjectDisplayed(FormType, Request.formTitle, , Request.waitSeconds);
    If ReadClientError(Application, Report, "form_open_error") Then
        Return;
    EndIf;

    Forms = Application.FindObjects(FormType, Request.formTitle);
    Target = Undefined;
    For Each Candidate In Forms Do
        If NormalizeFormName(Candidate.FormName) = NormalizeFormName(Request.form) Then
            If Target <> Undefined Then
                Report.status = "ambiguous_form";
                Return;
            EndIf;
            Target = Candidate;
        EndIf;
    EndDo;
    If Target = Undefined Then
        Report.status = "form_not_found";
        Names = New Array;
        For Each OpenForm In Application.FindObjects(FormType) Do
            Names.Add(OpenForm.FormName);
        EndDo;
        Report.Insert("observedForms", Names);
        Return;
    EndIf;
    Report.Insert("observedForm", Target.FormName);
    Target.Activate();

    Report.stage = "button_lookup";
    Buttons = Target.FindObjects(Type("TestedFormButton"), Request.button);
    If Buttons.Count() = 0 Then
        Report.status = "button_not_found";
        Return;
    ElsIf Buttons.Count() > 1 Then
        Report.status = "ambiguous_button";
        Return;
    EndIf;

    Button = Buttons[0];
    If Not Button.CurrentVisible() Or Not Button.CurrentEnable() Then
        Report.status = "button_unavailable";
        Return;
    EndIf;
    BeforeCount = 0;
    MessageWindow = Application.FindObject(Type("TestedClientApplicationWindow"));
    If Request.expectedMessage <> "" Then
        Report.stage = "before_observation";
        BeforeCount = CountMatches(VisibleMessages(MessageWindow, Report, "beforeMessagePanel"), Request.expectedMessage);
    EndIf;
    Report.clickAttempted = True;
    Report.status = "command_running";
    Report.stage = "command";
    SaveReport(Report, Request.progress);
    Try
        Button.Click();
        Report.clickReturned = True;
    Except
        Report.Insert("error", ОбработкаОшибок.ПодробноеПредставлениеОшибки(ИнформацияОбОшибке()));
    EndTry;
    Report.status = "command_error";
    If ReadClientError(Application, Report, "command_error") Or Not Report.clickReturned Then
        Return;
    EndIf;

    Report.stage = "after_observation";
    Messages = VisibleMessages(MessageWindow, Report, "afterMessagePanel");
    Report.Insert("messages", Messages);
    If Request.expectedMessage = "" Then
        Report.status = "command_observed";
    ElsIf CountMatches(Messages, Request.expectedMessage) <= BeforeCount Then
        Report.status = "message_not_found";
    Else
        Report.status = "command_completed";
    EndIf;
EndProcedure

Function ReadClientError(Application, Report, Status)
    CurrentError = Application.GetCurrentErrorInfo();
    If CurrentError = Undefined Then
        Return False;
    EndIf;
    Report.Insert("error", ОбработкаОшибок.ПодробноеПредставлениеОшибки(CurrentError));
    Report.status = Status;
    Return True;
EndFunction

Function NormalizeFormName(Name)
    Parts = StrSplit(Name, ".");
    If Parts.Count() = 4 Then
        If Parts[0] = "ВнешняяОбработка" Then
            Parts[0] = "ExternalDataProcessor";
        EndIf;
        If Parts[2] = "Форма" Then
            Parts[2] = "Form";
        EndIf;
    EndIf;
    Return StrConcat(Parts, ".");
EndFunction

Function CountMatches(Messages, Expected)
    Count = 0;
    For Each Text In Messages Do
        If Text = Expected Then
            Count = Count + 1;
        EndIf;
    EndDo;
    Return Count;
EndFunction

Function VisibleMessages(Window, Report, PresenceKey)
    Try
        Messages = Window.GetUserMessageTexts();
        Report.Insert(PresenceKey, True);
        Return Messages;
    Except
        Detail = ОбработкаОшибок.ПодробноеПредставлениеОшибки(ИнформацияОбОшибке());
        If StrFind(Detail, "Cannot call the method for this object") > 0
            Or StrFind(Detail, "Метод не может быть вызван для данного объекта") > 0 Then
            Report.Insert(PresenceKey, False);
            Return New Array;
        EndIf;
        Raise;
    EndTry;
EndFunction

Procedure SaveReport(Report, Destination)
    Writer = New JSONWriter;
    Writer.SetString();
    WriteJSON(Writer, Report);
    Document = New TextDocument;
    Document.SetText(Writer.Close());
    Pending = Destination + ".tmp";
    Document.Write(Pending, TextEncoding.UTF8);
    MoveFile(Pending, Destination);
EndProcedure

#КонецОбласти

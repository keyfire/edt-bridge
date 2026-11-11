#If ThickClientManagedApplication Then

#Region EventHandlers

Procedure OnStart()
    If IsBlankString(LaunchParameter) Then
        Return;
    EndIf;
    Reader = New JSONReader;
    Reader.OpenFile(LaunchParameter);
    Request = ReadJSON(Reader);
    Reader.Close();

    Report = New Structure("status,stage", "driver_failed", "connect");
    If Not Request.Property("steps") Then
        Report.Insert("clickAttempted", False);
        Report.Insert("clickReturned", False);
    EndIf;
    Try
        RunCheck(Request, Report);
    Except
        Report.Insert("error", ОбработкаОшибок.ПодробноеПредставлениеОшибки(ИнформацияОбОшибке()));
    EndTry;

    SaveReport(Report, Request.result);
EndProcedure

#EndRegion

#Region Private

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
        Titles = New Array;
        For Each OpenForm In Application.FindObjects(FormType) Do
            Names.Add(OpenForm.FormName);
            Titles.Add(OpenForm.TitleText);
        EndDo;
        Report.Insert("observedForms", Names);
        Report.Insert("observedTitles", Titles);
        Return;
    EndIf;
    Report.Insert("observedForm", Target.FormName);
    Report.Insert("observedTitle", Target.TitleText);
    Target.Activate();
    MessageWindow = TargetWindow(Application, Target, Request);
    If Request.Property("steps") Then
        RunScenario(Application, Target, MessageWindow, Request, Report);
        Return;
    EndIf;

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

Function TargetWindow(Application, Target, Request)
    Window = Application.GetActiveWindow();
    If Window <> Undefined Then
        For Each Candidate In Window.FindObjects(Type("TestedForm"), Request.formTitle) Do
            If NormalizeFormName(Candidate.FormName) = NormalizeFormName(Target.FormName) Then
                Return Window;
            EndIf;
        EndDo;
    EndIf;
    Raise NStr("en = 'The active window does not contain the selected form.'; ru = 'Активное окно не содержит выбранную форму.'");
EndFunction

Procedure RunScenario(Application, Target, MessageWindow, Request, Report)
    Observations = New Array;
    Report.Insert("observations", Observations);
    BeforeMessages = New Array;
    AfterMessages = New Array;
    For Each Step In Request.steps Do
        Report.status = "scenario_error";
        Report.stage = Step.action;
        Report.Insert("step", Observations.Count() + 1);
        SaveReport(Report, Request.progress);
        Observation = New Structure("action", Step.action);
        If Step.action = "click" Then
            If Step.Property("button_name") Then
                Buttons = Target.FindObjects(Type("TestedFormButton"), , Step.button_name, Request.waitSeconds);
            Else
                Buttons = Target.FindObjects(Type("TestedFormButton"), Step.button, , Request.waitSeconds);
            EndIf;
            If Buttons.Count() <> 1 Then
                Raise NStr("en = 'The button selector must match exactly one button.'; ru = 'Селектор кнопки должен соответствовать ровно одной кнопке.'");
            EndIf;
            Button = Buttons[0];
            If Not Button.CurrentVisible() Or Not Button.CurrentEnable() Then
                Raise NStr("en = 'The selected button is unavailable.'; ru = 'Выбранная кнопка недоступна.'");
            EndIf;
            ObserveMessages = NeedsMessageObservation(Request.steps, Observations.Count());
            If ObserveMessages Then
                BeforeMessages = VisibleMessages(MessageWindow, Report, "beforeMessagePanel");
            EndIf;
            Button.Click();
            If ReadClientError(Application, Report, "command_error") Then
                Return;
            EndIf;
            If Step.Property("expect_error_dialog") Then
                If Not CloseExpectedErrorDialog(Application, Step.expect_error_dialog, Observation, Request, Report) Then
                    Return;
                EndIf;
                Target.Activate();
            EndIf;
            If ObserveMessages Then
                AfterMessages = VisibleMessages(MessageWindow, Report, "afterMessagePanel");
            EndIf;
            Observation.Insert("completed", True);
        ElsIf Step.action = "assert_messages" Then
            Observation.Insert("before", BeforeMessages);
            Observation.Insert("actual", AfterMessages);
        Else
            Fields = Target.FindObjects(Type("TestedFormField"), , Step.field, Request.waitSeconds);
            If Fields.Count() <> 1 Then
                Raise NStr("en = 'The field selector must match exactly one field.'; ru = 'Селектор поля должен соответствовать ровно одному полю.'");
            EndIf;
            Field = Fields[0];
            If Not Field.CurrentVisible() Then
                Raise NStr("en = 'The selected field is not visible.'; ru = 'Выбранное поле не отображается.'");
            EndIf;
            If Step.action = "input" Then
                Field.Activate();
                If Step.text = "" Then
                    Field.Clear();
                Else
                    Field.InputText(Step.text);
                EndIf;
                Target.GotoNextItem();
                Observation.Insert("completed", True);
            ElsIf Step.action = "assert_field" Then
                Observation.Insert("actual", Field.GetDataPresentation());
            Else
                If Field.Type <> FormFieldType.SpreadsheetDocumentField Then
                    Raise NStr("en = 'The selected field is not a spreadsheet document.'; ru = 'Выбранное поле не является табличным документом.'");
                EndIf;
                If Step.action = "assert_cell" Then
                    Observation.Insert("actual", Field.GetAreaText(Step.cell));
                ElsIf Step.action = "assert_table_size" Then
                    Size = New Structure("rows,columns", Field.GetDocumentDataAreaVerticalSize(),
                        Field.GetDocumentDataAreaHorizontalSize());
                    Observation.Insert("actual", Size);
                ElsIf Step.action = "assert_table" Then
                    Observation.Insert("actual", ReadTable(Field, Request));
                EndIf;
            EndIf;
        EndIf;
        If ReadClientError(Application, Report, "command_error") Then
            Return;
        EndIf;
        Observations.Add(Observation);
    EndDo;
    Report.status = "scenario_observed";
EndProcedure

Function NeedsMessageObservation(Steps, ClickIndex)
    For Index = ClickIndex + 1 To Steps.Count() - 1 Do
        Action = Steps[Index].action;
        If Action = "assert_messages" Then
            Return True;
        ElsIf Action = "click" Or Action = "input" Then
            Return False;
        EndIf;
    EndDo;
    Return False;
EndFunction

Function CloseExpectedErrorDialog(Application, ExpectedText, Observation, Request, Report)
    Report.stage = "expected_error_dialog";
    Window = Application.GetActiveWindow();
    Dialog = Undefined;
    For Each Candidate In Window.FindObjects(Type("TestedForm")) Do
        If Candidate.FormName = "ErrorWindow" Then
            If Dialog <> Undefined Then
                Raise NStr("en = 'The error dialog is ambiguous.'; ru = 'Найдено несколько диалогов ошибки.'");
            EndIf;
            Dialog = Candidate;
        EndIf;
    EndDo;
    If Dialog = Undefined Then
        Raise NStr("en = 'The expected native error dialog was not found.'; ru = 'Ожидаемый штатный диалог ошибки не найден.'");
    EndIf;
    Texts = Dialog.FindObjects(Type("TestedFormDecoration"), , "ErrorInfo", Request.waitSeconds);
    If Texts.Count() <> 1 Then
        Raise NStr("en = 'The error dialog text was not found.'; ru = 'Текст диалога ошибки не найден.'");
    EndIf;
    ActualText = Texts[0].TitleText;
    Report.Insert("errorDialog", ActualText);
    Report.Insert("expectedErrorDialog", ExpectedText);
    If ActualText <> ExpectedText Then
        Report.status = "assertion_failed";
        Return False;
    EndIf;
    Buttons = Dialog.FindObjects(Type("TestedFormButton"), , "OK", Request.waitSeconds);
    If Buttons.Count() <> 1 Then
        Raise NStr("en = 'The error dialog OK button was not found.'; ru = 'Кнопка OK диалога ошибки не найдена.'");
    EndIf;
    Button = Buttons[0];
    If Not Button.CurrentVisible() Or Not Button.CurrentEnable() Then
        Raise NStr("en = 'The error dialog OK button is unavailable.'; ru = 'Кнопка OK диалога ошибки недоступна.'");
    EndIf;
    Button.Click();
    If Not Dialog.WaitForClosing(Request.waitSeconds) Then
        Raise NStr("en = 'The error dialog did not close.'; ru = 'Диалог ошибки не закрылся.'");
    EndIf;
    Observation.Insert("errorDialog", ActualText);
    Observation.Insert("dialogClosed", True);
    Return True;
EndFunction

Function ReadTable(Field, Request)
    RowCount = Field.GetDocumentDataAreaVerticalSize();
    ColumnCount = Field.GetDocumentDataAreaHorizontalSize();
    If RowCount > Request.maxRows Or ColumnCount > Request.maxColumns Then
        Raise NStr("en = 'The spreadsheet exceeds the scenario observation limit.'; ru = 'Табличный документ превышает предел размера для проверки сценария.'");
    EndIf;
    Rows = New Array;
    For Row = 1 To RowCount Do
        Cells = New Array;
        For Column = 1 To ColumnCount Do
            Cells.Add(Field.GetAreaText("R" + String(Row) + "C" + String(Column)));
        EndDo;
        Rows.Add(Cells);
    EndDo;
    Return Rows;
EndFunction

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
    If Parts.Count() = 3 Or Parts.Count() = 4 Then
        If Parts[0] = "ВнешняяОбработка" Then
            Parts[0] = "ExternalDataProcessor";
        ElsIf Parts[0] = "ВнешнийОтчет" Then
            Parts[0] = "ExternalReport";
        ElsIf Parts[0] = "Документ" Then
            Parts[0] = "Document";
        ElsIf Parts[0] = "Справочник" Then
            Parts[0] = "Catalog";
        EndIf;
        If Parts[2] = "Форма" Then
            Parts[2] = "Form";
        EndIf;
    EndIf;
    Return Lower(StrConcat(Parts, "."));
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
            Or StrFind(Detail, "The method cannot be called for this object type") > 0
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

#EndRegion

#EndIf

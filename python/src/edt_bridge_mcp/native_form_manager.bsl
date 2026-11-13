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
    If IsBlankString(Request.formTitle) Then
        WaitForNamedForm(Application, Request);
    Else
        Application.WaitForObjectDisplayed(FormType, Request.formTitle, , Request.waitSeconds);
    EndIf;
    If ReadClientError(Application, Report, "form_open_error") Then
        Return;
    EndIf;

    Forms = FindForms(Application, Request);
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

// The forms of the application or a window: those with the requested title, or all of them when the
// request names the form only. The form is then picked by its name either way.
Function FindForms(Owner, Request)
    If IsBlankString(Request.formTitle) Then
        Return Owner.FindObjects(Type("TestedForm"));
    EndIf;
    Return Owner.FindObjects(Type("TestedForm"), Request.formTitle);
EndFunction

// Without a title the form is awaited by its name: the open forms are looked through until one of that
// name is shown or the wait ends. A title would wait for the window instead, and the window of an object
// form adds the object presentation to the title of the form.
Procedure WaitForNamedForm(Application, Request)
    Deadline = CurrentUniversalDateInMilliseconds() + Request.waitSeconds * 1000;
    While CurrentUniversalDateInMilliseconds() < Deadline Do
        For Each Candidate In Application.FindObjects(Type("TestedForm")) Do
            If NormalizeFormName(Candidate.FormName) = NormalizeFormName(Request.form) Then
                Return;
            EndIf;
        EndDo;
    EndDo;
EndProcedure

Function TargetWindow(Application, Target, Request)
    Window = Application.GetActiveWindow();
    If Window <> Undefined Then
        For Each Candidate In FindForms(Window, Request) Do
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
            ElsIf Step.Property("expect_warning") Then
                If Not CloseExpectedWarning(Application, Target, Step.expect_warning, Observation, Request, Report) Then
                    Return;
                EndIf;
                Target.Activate();
            ElsIf UnexpectedDialog(Application, Target, Report) Then
                // A window over the form stops the next step anyway; its text tells what the solution did.
                Return;
            EndIf;
            If ObserveMessages Then
                AfterMessages = VisibleMessages(MessageWindow, Report, "afterMessagePanel");
            EndIf;
            Observation.Insert("completed", True);
        ElsIf Step.action = "assert_messages" Then
            Observation.Insert("before", BeforeMessages);
            Observation.Insert("actual", AfterMessages);
        ElsIf Step.action = "read_form" Then
            Observation.Insert("actual", ReadForm(Target, Request));
        Else
            Tables = New Array;
            If Step.action = "assert_table" Or Step.action = "read_table" Then
                // A form table is an object of its own type, not a field: both are looked for in turn.
                Fields = FindFieldOrTable(Target, Step.field, Tables, Request);
            Else
                Fields = Target.FindObjects(Type("TestedFormField"), , Step.field, Request.waitSeconds);
            EndIf;
            If Tables.Count() = 1 Then
                // An assertion compares a complete table; a read returns what fits and says so.
                FormTable = ReadFormTable(Tables[0], Request, Step.action = "read_table");
                Observation.Insert("actual", FormTable.rows);
                Observation.Insert("columns", FormTable.columns);
                Observation.Insert("truncated", FormTable.truncated);
            ElsIf Tables.Count() > 1 Then
                Report.Insert("observedFields", ObservedFields(Target, Request));
                Raise NStr("en = 'The table selector must match exactly one field or form table.'; ru = 'Селектор таблицы должен соответствовать ровно одному полю или таблице формы.'");
            Else
                If Fields.Count() <> 1 Then
                    // The names of fields that appear only when the form opens are not in any source.
                    Report.Insert("observedFields", ObservedFields(Target, Request));
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
                    ElsIf Step.action = "read_table" Then
                        Sheet = ReadSheet(Field, Request);
                        Observation.Insert("actual", Sheet.rows);
                        Observation.Insert("truncated", Sheet.truncated);
                    EndIf;
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

Function CloseExpectedWarning(Application, Target, ExpectedText, Observation, Request, Report)
    Report.stage = "expected_warning";
    Dialog = WaitForDialog(Application, Target, Request);
    If Dialog = Undefined Then
        Raise NStr("en = 'The expected warning window was not found.'; ru = 'Ожидаемое окно предупреждения не найдено.'");
    EndIf;
    Described = DescribeDialog(Dialog);
    If Described.kind <> "warning" Then
        Report.status = "unexpected_dialog";
        Report.Insert("dialog", Described);
        Return False;
    EndIf;
    Report.Insert("warningDialog", Described.text);
    Report.Insert("expectedWarning", ExpectedText);
    If Described.text <> ExpectedText Then
        Report.status = "assertion_failed";
        Return False;
    EndIf;
    Buttons = VisibleButtons(Dialog);
    If Buttons.Count() <> 1 Then
        Raise NStr("en = 'The warning window must have exactly one button.'; ru = 'В окне предупреждения должна быть ровно одна кнопка.'");
    EndIf;
    Button = Buttons[0];
    If Not Button.CurrentEnable() Then
        Raise NStr("en = 'The warning window button is unavailable.'; ru = 'Кнопка окна предупреждения недоступна.'");
    EndIf;
    Button.Click();
    If Not Dialog.WaitForClosing(Request.waitSeconds) Then
        Raise NStr("en = 'The warning window did not close.'; ru = 'Окно предупреждения не закрылось.'");
    EndIf;
    Observation.Insert("warningDialog", Described.text);
    Observation.Insert("dialogClosed", True);
    Return True;
EndFunction

Function UnexpectedDialog(Application, Target, Report)
    Dialog = RaisedDialog(Application, Target);
    If Dialog = Undefined Then
        Return False;
    EndIf;
    Described = DescribeDialog(Dialog);
    If Described.kind = "form" Then
        // A regular form the click opened is the result of the command, not a window over the form.
        Return False;
    EndIf;
    Report.status = "unexpected_dialog";
    Report.Insert("dialog", Described);
    Return True;
EndFunction

// The window a click raised over the form: a form of the active window when the selected form is not
// in it. Undefined while the selected form is active.
Function RaisedDialog(Application, Target)
    Window = Application.GetActiveWindow();
    If Window = Undefined Then
        Return Undefined;
    EndIf;
    Dialog = Undefined;
    For Each Candidate In Window.FindObjects(Type("TestedForm")) Do
        If NormalizeFormName(Candidate.FormName) = NormalizeFormName(Target.FormName) Then
            Return Undefined;
        EndIf;
        If Dialog = Undefined Then
            Dialog = Candidate;
        EndIf;
    EndDo;
    Return Dialog;
EndFunction

Function WaitForDialog(Application, Target, Request)
    Deadline = CurrentUniversalDateInMilliseconds() + Request.waitSeconds * 1000;
    While True Do
        Dialog = RaisedDialog(Application, Target);
        If Dialog <> Undefined Or CurrentUniversalDateInMilliseconds() >= Deadline Then
            Return Dialog;
        EndIf;
    EndDo;
EndFunction

// A system window over the form: the error window, or MessageBox for ShowMessageBox and
// ShowQueryBox. MessageBox keeps ten buttons and shows only the ones the call asked for, and a
// decoration without a title answers its own name, so the text is read from its one decoration.
Function DescribeDialog(Dialog)
    Kind = "form";
    TextItem = "";
    If Dialog.FormName = "ErrorWindow" Then
        Kind = "error";
        TextItem = "ErrorInfo";
    ElsIf IsMessageBox(Dialog.FormName) Then
        Kind = "warning";
        TextItem = "Message";
    EndIf;
    Buttons = New Array;
    For Each Button In VisibleButtons(Dialog) Do
        Buttons.Add(Button.TitleText);
    EndDo;
    If Kind = "warning" And Buttons.Count() > 1 Then
        Kind = "question";
    EndIf;
    Text = "";
    If Not IsBlankString(TextItem) Then
        Texts = Dialog.FindObjects(Type("TestedFormDecoration"), , TextItem);
        If Texts.Count() = 1 Then
            Text = Texts[0].TitleText;
        EndIf;
    EndIf;
    Described = New Structure("kind,formName,text,buttons", Kind, Dialog.FormName, Text, Buttons);
    If Kind <> "form" And IsBlankString(Text) Then
        // The text sits elsewhere in this window: its decorations tell the caller where.
        Decorations = New Array;
        For Each Decoration In Dialog.FindObjects(Type("TestedFormDecoration")) Do
            Decorations.Add(New Structure("name,title", TestedObjectName(Decoration), Decoration.TitleText));
        EndDo;
        Described.Insert("decorations", Decorations);
    EndIf;
    Return Described;
EndFunction

// MessageBox, or MessageBox followed by a number: a box raised from an object form is numbered.
Function IsMessageBox(FormName)
    If Not StrStartsWith(FormName, "MessageBox") Then
        Return False;
    EndIf;
    Suffix = Mid(FormName, StrLen("MessageBox") + 1);
    For Position = 1 To StrLen(Suffix) Do
        If StrFind("0123456789", Mid(Suffix, Position, 1)) = 0 Then
            Return False;
        EndIf;
    EndDo;
    Return True;
EndFunction

Function TestedObjectName(TestedObject)
    Try
        Return TestedObject.Name;
    Except
        Return "";
    EndTry;
EndFunction

Function VisibleButtons(Dialog)
    Buttons = New Array;
    For Each Button In Dialog.FindObjects(Type("TestedFormButton")) Do
        If Button.CurrentVisible() Then
            Buttons.Add(Button);
        EndIf;
    EndDo;
    Return Buttons;
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

// The fields and form tables the test client sees on the form, for a selector that matched none or several:
// name, title, kind, visibility and availability. The visible ones come first, then the hidden ones, up to
// the observation limit; total says how many there are.
Function ObservedFields(Target, Request)
    Visible = New Array;
    Hidden = New Array;
    CollectFields(Target.GetChildObjects(), Visible, Hidden);
    Listed = New Array;
    For Each Field In Visible Do
        If Listed.Count() < Request.maxListed Then
            Listed.Add(Field);
        EndIf;
    EndDo;
    For Each Field In Hidden Do
        If Listed.Count() < Request.maxListed Then
            Listed.Add(Field);
        EndIf;
    EndDo;
    Return New Structure("fields,total", Listed, Visible.Count() + Hidden.Count());
EndFunction

Procedure CollectFields(Items, Visible, Hidden)
    For Each Item In Items Do
        ItemType = TypeOf(Item);
        If ItemType = Type("TestedFormField") Or ItemType = Type("TestedFormTable") Then
            Kind = "FormTable";
            If ItemType = Type("TestedFormField") Then
                Kind = String(Item.Type);
            EndIf;
            Field = New Structure("name,title,kind,visible,enabled", Item.Name, Item.TitleText, Kind,
                Item.CurrentVisible(), Item.CurrentEnable());
            If Field.visible Then
                Visible.Add(Field);
            Else
                Hidden.Add(Field);
            EndIf;
        EndIf;
        If ItemType = Type("TestedFormGroup") Or ItemType = Type("TestedFormTable") Then
            CollectFields(Item.GetChildObjects(), Visible, Hidden);
        EndIf;
    EndDo;
EndProcedure

// A field or a form table of this name. A form table is an object of its own type. The field is awaited
// in short waits and the form table is looked for between them, so a form table is taken as soon as it is
// drawn, while a field drawn late is still awaited for the whole wait. The form tables found go to
// Tables, the fields found are returned.
Function FindFieldOrTable(Target, Name, Tables, Request)
    Deadline = CurrentUniversalDateInMilliseconds() + Request.waitSeconds * 1000;
    While True Do
        Fields = Target.FindObjects(Type("TestedFormField"), , Name, 1);
        If Fields.Count() > 0 Then
            Return Fields;
        EndIf;
        For Each Found In Target.FindObjects(Type("TestedFormTable"), , Name) Do
            Tables.Add(Found);
        EndDo;
        If Tables.Count() > 0 Or CurrentUniversalDateInMilliseconds() >= Deadline Then
            Return Fields;
        EndIf;
    EndDo;
EndFunction

// The values of the whole form as the test client sees them: every visible field with its name,
// title and presentation, every form table by rows and columns, every spreadsheet field as a table.
// The test client sees form items only, so an attribute without an item on the form is not read.
// What does not fit the observation limits is left out, and truncated says so.
Function ReadForm(Target, Request)
    Result = New Structure("fields,tables,spreadsheets,truncated", New Array, New Array, New Array, False);
    CollectItems(Target.GetChildObjects(), Result, Request);
    Return Result;
EndFunction

Procedure CollectItems(Items, Result, Request)
    For Each Item In Items Do
        If Result.fields.Count() + Result.tables.Count() + Result.spreadsheets.Count() >= Request.maxItems Then
            Result.truncated = True;
            Return;
        EndIf;
        ItemType = TypeOf(Item);
        If ItemType = Type("TestedFormTable") Then
            If Item.CurrentVisible() Then
                FormTable = ReadFormTable(Item, Request, True);
                FormTable.Insert("name", Item.Name);
                FormTable.Insert("title", Item.TitleText);
                Result.tables.Add(FormTable);
            EndIf;
        ElsIf ItemType = Type("TestedFormField") Then
            If Not Item.CurrentVisible() Then
                Continue;
            EndIf;
            If Item.Type = FormFieldType.SpreadsheetDocumentField Then
                Sheet = ReadSheet(Item, Request);
                Sheet.Insert("name", Item.Name);
                Sheet.Insert("title", Item.TitleText);
                Result.spreadsheets.Add(Sheet);
            Else
                Field = New Structure("name,title", Item.Name, Item.TitleText);
                Try
                    Field.Insert("presentation", Item.GetDataPresentation());
                Except
                    Field.Insert("error", ОбработкаОшибок.КраткоеПредставлениеОшибки(ИнформацияОбОшибке()));
                EndTry;
                Result.fields.Add(Field);
            EndIf;
        ElsIf ItemType = Type("TestedFormGroup") Then
            If Item.CurrentVisible() Then
                CollectItems(Item.GetChildObjects(), Result, Request);
            EndIf;
        EndIf;
    EndDo;
EndProcedure

// A spreadsheet field for a read: the rectangle up to the observation limits, and whether it was cut.
Function ReadSheet(Field, Request)
    RowCount = Field.GetDocumentDataAreaVerticalSize();
    ColumnCount = Field.GetDocumentDataAreaHorizontalSize();
    Rows = New Array;
    For Row = 1 To Min(RowCount, Request.maxRows) Do
        Cells = New Array;
        For Column = 1 To Min(ColumnCount, Request.maxColumns) Do
            Cells.Add(Field.GetAreaText("R" + String(Row) + "C" + String(Column)));
        EndDo;
        Rows.Add(Cells);
    EndDo;
    Return New Structure("rows,truncated", Rows, RowCount > Request.maxRows Or ColumnCount > Request.maxColumns);
EndFunction

// A form table read row by row through the test client. The columns are its visible fields, and a cell is
// read by the column item name. The cursor goes down from the first row until a move fails. The end and an
// empty table are told from a failure by the last row: a failed move counts as the end only when the last
// row, reached with GotoLastRow, carries the cells read last. An empty table accepts GotoFirstRow but has no
// row to read, and its last row cannot be read either. Any other failure is raised, so a broken read never
// looks like a short table. Truncate cuts a table that exceeds the observation limits instead of failing;
// an assertion compares complete tables only.
Function ReadFormTable(FormTable, Request, Truncate)
    State = New Structure("truncated", False);
    Columns = New Array;
    CollectColumns(FormTable.GetChildObjects(), Columns, Request, Truncate, State);
    Rows = New Array;
    Try
        FormTable.GotoFirstRow();
        Cells = RowCells(FormTable, Columns);
    Except
        Failure = ОбработкаОшибок.КраткоеПредставлениеОшибки(ИнформацияОбОшибке());
        If LastRowReadable(FormTable, Columns) Then
            Raise NStr("en = 'The form table has rows, but its first row cannot be read: '; ru = 'В таблице формы есть строки, но первую прочитать не удалось: '") + Failure;
        EndIf;
        Return New Structure("columns,rows,truncated", Columns, Rows, State.truncated);
    EndTry;
    While True Do
        If Rows.Count() >= Request.maxRows Then
            If Not Truncate Then
                Raise NStr("en = 'The form table exceeds the scenario observation limit.'; ru = 'Таблица формы превышает предел размера для проверки сценария.'");
            EndIf;
            State.truncated = True;
            Break;
        EndIf;
        Rows.Add(Cells);
        Try
            FormTable.GotoNextRow();
        Except
            Failure = ОбработкаОшибок.КраткоеПредставлениеОшибки(ИнформацияОбОшибке());
            FormTable.GotoLastRow();
            If Not SameCells(RowCells(FormTable, Columns), Cells) Then
                Raise NStr("en = 'The form table read stopped before its last row: '; ru = 'Чтение таблицы формы прервалось до последней строки: '") + Failure;
            EndIf;
            Break;
        EndTry;
        Cells = RowCells(FormTable, Columns);
    EndDo;
    Return New Structure("columns,rows,truncated", Columns, Rows, State.truncated);
EndFunction

Function LastRowReadable(FormTable, Columns)
    Try
        FormTable.GotoLastRow();
        RowCells(FormTable, Columns);
        Return True;
    Except
        Return False;
    EndTry;
EndFunction

Function RowCells(FormTable, Columns)
    Cells = New Array;
    For Each Column In Columns Do
        Cells.Add(FormTable.GetCellText(Column.name));
    EndDo;
    Return Cells;
EndFunction

Function SameCells(First, Second)
    If First.Count() <> Second.Count() Then
        Return False;
    EndIf;
    For Index = 0 To First.Count() - 1 Do
        If First[Index] <> Second[Index] Then
            Return False;
        EndIf;
    EndDo;
    Return True;
EndFunction

Procedure CollectColumns(Items, Columns, Request, Truncate, State)
    For Each Item In Items Do
        If TypeOf(Item) = Type("TestedFormField") Then
            If Item.CurrentVisible() Then
                If Columns.Count() >= Request.maxColumns Then
                    If Not Truncate Then
                        Raise NStr("en = 'The form table exceeds the scenario observation limit.'; ru = 'Таблица формы превышает предел размера для проверки сценария.'");
                    EndIf;
                    State.truncated = True;
                    Return;
                EndIf;
                Columns.Add(New Structure("name,title", Item.Name, Item.TitleText));
            EndIf;
        ElsIf TypeOf(Item) = Type("TestedFormGroup") Then
            CollectColumns(Item.GetChildObjects(), Columns, Request, Truncate, State);
        EndIf;
    EndDo;
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

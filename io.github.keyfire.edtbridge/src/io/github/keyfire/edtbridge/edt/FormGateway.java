/*
 * edt-bridge - a 1C:EDT bridge that exposes the live EDT model over MCP.
 * Copyright 2026 edt-bridge contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.keyfire.edtbridge.edt;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.common.util.EMap;
import org.eclipse.emf.common.util.Enumerator;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.osgi.framework.Bundle;
import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.form.model.AbstractDataPath;
import com._1c.g5.v8.dt.form.model.Button;
import com._1c.g5.v8.dt.form.model.CommandHandler;
import com._1c.g5.v8.dt.form.model.CommandHandlerContainer;
import com._1c.g5.v8.dt.form.model.DataItem;
import com._1c.g5.v8.dt.form.model.Decoration;
import com._1c.g5.v8.dt.form.model.EventHandler;
import com._1c.g5.v8.dt.form.model.FieldExtInfo;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormAttribute;
import com._1c.g5.v8.dt.form.model.FormCommand;
import com._1c.g5.v8.dt.form.model.FormField;
import com._1c.g5.v8.dt.form.model.FormGroup;
import com._1c.g5.v8.dt.form.model.FormItem;
import com._1c.g5.v8.dt.form.model.FormItemContainer;
import com._1c.g5.v8.dt.form.model.FormParameter;
import com._1c.g5.v8.dt.form.model.InputFieldExtInfo;
import com._1c.g5.v8.dt.form.model.NativeRenderEvent;
import com._1c.g5.v8.dt.form.model.Titled;
import com._1c.g5.v8.dt.form.model.Table;
import com._1c.g5.v8.dt.form.model.Visible;
import com._1c.g5.v8.dt.form.model.EventHandlerContainer;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionConditionalAppearance;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionConditionalAppearanceItem;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionAppearanceField;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionFilter;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionFilterItem;
import com._1c.g5.v8.dt.dcs.model.settings.FilterItem;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionParameterValue;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionField;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionParameter;
import com._1c.g5.v8.dt.form.layout.model.calculation.context.LFTargetPlatform;
import com._1c.g5.v8.dt.form.layout.model.description.ClientInterfaceScale;
import com._1c.g5.v8.dt.form.layout.model.description.ClientInterfaceTheme;
import com._1c.g5.v8.dt.form.layout.model.description.ClientInterfaceVariant;
import com._1c.g5.v8.dt.form.layout.model.platform.IPlatformVisualComputer;
import com._1c.g5.v8.dt.form.layout.service.HippoLayoutService;
import com._1c.g5.v8.dt.form.layout.service.IHippoLayModelSession;
import com._1c.g5.v8.dt.form.layout.service.ILayoutRenderService;
import com._1c.g5.v8.dt.form.layout.service.LayoutTransformationService;
import com._1c.g5.v8.dt.form.layout.service.RenderServiceProvider;
import com._1c.g5.v8.dt.form.layout.service.TransformatorServiceProvider;
import com._1c.g5.v8.dt.form.mapping.cmi.FormCommandInterfaceMapping;
import com._1c.g5.v8.dt.form.mapping.core.MappingController;
import com._1c.g5.v8.dt.form.mapping.model.CommandInterfaceMapping;
import com._1c.g5.v8.dt.form.presentation.theme.IThemeProjection;
import com._1c.g5.v8.dt.mcore.Event;
import com._1c.g5.v8.dt.metadata.mdclass.AbstractForm;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.CompatibilityMode;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.platform.version.Version;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.wiring.ServiceAccess;

/**
 * Managed-form reads and rendering: the form's visual items tree + attributes/commands/parameters/
 * events (getFormStructure), object picture export (exportPicture) and off-screen form rendering to
 * PNG via EDT's Hippo layout services (renderForm). Split out of the original model gateway to keep that
 * file focused; behaviour is unchanged.
 */
public final class FormGateway {
    /**
     * Dedicated single thread that owns the SWT Display used for form rendering – isolated from both
     * the MCP HTTP thread and a GUI workbench's main UI thread, so {@code edt_form_render} works in
     * either a headless CLI or a running GUI EDT without freezing the editor.
     */
    private static final ExecutorService RENDER_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread t = new Thread(runnable, "edt-bridge-render");
        t.setDaemon(true);
        return t;
    });

    // ---- Managed form structure ------------------------------------------------------------

    /** A node in a form's visual items tree (a field, group, table, button, decoration, ...). */
    public static final class FormNode {
        public String name;
        public String kind;       // eClass simple name (FormField, FormGroup, Table, Button, Decoration, ...)
        public String itemType;   // the Managed*Type literal (InputField, UsualGroup, ...) when applicable
        public String dataPath;   // bound data path for data items (e.g. "Объект.Наименование"); null otherwise
        public String title;      // ru title, when present
        // Static (.form) properties – note these are the DESIGN values; BSL (e.g. ПриСозданииНаСервере)
        // may change them at runtime. null = not applicable to this item kind.
        public Boolean visible;
        public Boolean enabled;
        public Boolean readOnly;
        // command -> button: the command this button is wired to + how it is drawn. null otherwise.
        public String command;        // name of the referenced command (matches a form command, best-effort)
        public String representation; // ButtonRepresentation (Text / Picture / ...), when set
        public String placement;      // placement area in a command bar / menu, when set
        // per-item event handlers (columns/fields carry their own – e.g. a cell-click "Выбор"/Selection
        // handler – separate from the form-level handlers). Empty when the item has none.
        public List<FormEvt> handlers = new ArrayList<>();
        public Boolean cellHyperlink; // the cell is rendered as a clickable hyperlink (FormField only)
        // Input-field design props (InputField only) – password masking + the reveal ("eye") choice
        // button, the design-time signal for the "show a stored secret on the client" pattern. null = n/a.
        public Boolean passwordMode;              // the field masks input as ***
        public Boolean choiceButton;              // an in-field choice button is shown
        public Boolean choiceButtonPicture;       // a choice-button picture is set – the button carries a glyph (the eye)
        public String choiceButtonRepresentation; // where the choice button is drawn (non-Auto only)
        public List<FormNode> children = new ArrayList<>();
    }

    /** One declarative conditional-appearance rule (УсловноеОформление) from the .form (DCS settings). */
    public static final class CondAppearance {
        public boolean use = true;
        public List<String> fields = new ArrayList<>();   // formatted fields (which cells get the styling)
        public List<String> filter = new ArrayList<>();   // selection conditions (left op right), best-effort
        public List<String[]> appearance = new ArrayList<>(); // [param, value] set styling, e.g. {ЦветТекста, ...}
    }

    /** A form data attribute. */
    public static final class FormAttr {
        public String name;
        public String valueType;  // rendered TypeDescription
        public boolean main;      // the form's main attribute (Объект / Список / ...)
    }

    /** A form command. */
    public static final class FormCmd {
        public String name;
        public String title;      // ru
        public String handler;    // BSL action procedure, best-effort
    }

    /** A form parameter. */
    public static final class FormParam {
        public String name;
        public String valueType;  // rendered TypeDescription
    }

    /** A form-level event handler (form event -> BSL procedure). */
    public static final class FormEvt {
        public String name;       // BSL handler procedure
        public String event;      // platform event name (e.g. ПриОткрытии / OnOpen)
    }

    /** Result of {@link #getFormStructure}. */
    public static final class FormDetails {
        public boolean found;
        public String fqn;
        public String type;       // eClass of the resolved object
        public String message;    // set when not found / not a form
        public String titleRu;
        public int width;
        public int height;
        public boolean truncated; // item-tree node cap reached
        public List<FormAttr> attributes = new ArrayList<>();
        public List<FormCmd> commands = new ArrayList<>();
        public List<FormParam> parameters = new ArrayList<>();
        public List<FormEvt> handlers = new ArrayList<>();
        public List<CondAppearance> conditionalAppearance = new ArrayList<>(); // declarative .form rules
        public List<FormNode> items = new ArrayList<>();
    }

    /**
     * Read a managed form by FQN (e.g. "Catalog.Контрагенты.Form.ФормаЭлемента", "CommonForm.МояФорма")
     * from the live BM model: the visual items tree (fields/groups/tables/buttons/decorations with
     * data bindings) plus the form's attributes, commands, parameters and event handlers. The
     * forms's content/metadata are one unified {@link Form} top object in EDT. The items tree is
     * capped at {@code itemLimit} nodes (<= 0 means no cap); {@code truncated} flags an overflow.
     */
    public FormDetails getFormStructure(String projectName, String fqn, int itemLimit) {
        FormDetails d = new FormDetails();
        d.fqn = fqn;
        IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!p.exists() || !p.isOpen()) {
            d.message = "project not found or closed: " + projectName;
            return d;
        }
        IBmModelManager mm = ServiceAccess.get(IBmModelManager.class);
        if (mm == null) {
            d.message = "BM model manager unavailable";
            return d;
        }
        IBmModel model = mm.getModel(p);
        if (model == null) {
            d.message = "no BM model for project: " + projectName;
            return d;
        }
        final int cap = itemLimit > 0 ? itemLimit : Integer.MAX_VALUE;
        return model.executeReadonlyTask(new AbstractBmTask<FormDetails>("edt-bridge.formStructure") {
            @Override
            public FormDetails execute(IBmTransaction transaction, IProgressMonitor monitor) {
                FormDetails r = new FormDetails();
                r.fqn = fqn;
                // A common form is a top object (CommonForm); the editable content (items,
                // attributes) is the form.model.Form reached via BasicForm.getForm(). A catalog/
                // document form is NOT a top object – only its owner is – so resolve "Owner.Form.Name"
                // through the owner's "forms" feature.
                EObject mdForm = transaction.getTopObjectByFqn(fqn);
                Form form = asFormContent(mdForm);
                if (form == null) {
                    int idx = fqn.lastIndexOf(".Form.");
                    if (idx > 0) {
                        IBmObject owner = transaction.getTopObjectByFqn(fqn.substring(0, idx));
                        if (owner != null) {
                            BasicForm bf = findForm(owner, fqn.substring(idx + ".Form.".length()));
                            if (bf != null) {
                                mdForm = bf;
                                form = asFormContent(bf);
                            }
                        }
                    }
                }
                if (form == null) {
                    if (mdForm != null) {
                        r.type = mdForm.eClass().getName();
                        r.message = "not a form (resolved " + r.type + "): " + fqn;
                    } else {
                        r.message = "form not found: " + fqn;
                    }
                    return r;
                }
                r.type = mdForm.eClass().getName();
                r.found = true;
                r.titleRu = ruOf(form.getTitle());
                try {
                    r.width = form.getWidth();
                    r.height = form.getHeight();
                } catch (RuntimeException ignored) {
                    // dimensions are optional
                }
                for (FormAttribute a : form.getAttributes()) {
                    FormAttr fa = new FormAttr();
                    fa.name = a.getName();
                    fa.valueType = GatewaySupport.renderType(a.getValueType());
                    fa.main = a.isMain();
                    r.attributes.add(fa);
                }
                for (FormCommand c : form.getFormCommands()) {
                    FormCmd fc = new FormCmd();
                    fc.name = c.getName();
                    fc.title = ruOf(c.getToolTip());
                    fc.handler = handlerOf(c);
                    r.commands.add(fc);
                }
                for (FormParameter pm : form.getParameters()) {
                    FormParam fp = new FormParam();
                    fp.name = pm.getName();
                    fp.valueType = GatewaySupport.renderType(pm.getValueType());
                    r.parameters.add(fp);
                }
                for (EventHandler h : form.getHandlers()) {
                    r.handlers.add(eventOf(h));
                }
                collectConditionalAppearance(form, r);
                int[] counter = {0};
                for (FormItem it : form.getItems()) {
                    FormNode n = nodeOf(it, cap, counter, r);
                    if (n != null) {
                        r.items.add(n);
                    }
                }
                return r;
            }
        });
    }

    /** The editable {@link Form} content of a resolved object: itself, or via its metadata form. */
    private Form asFormContent(EObject o) {
        if (o instanceof Form) {
            return (Form) o;
        }
        if (o instanceof BasicForm) {
            AbstractForm content = ((BasicForm) o).getForm();
            if (content instanceof Form) {
                return (Form) content;
            }
        }
        return null;
    }

    /** Find a named form among an owner object's "forms" (catalog/document/... subordinate forms). */
    private BasicForm findForm(EObject owner, String formName) {
        EStructuralFeature f = owner.eClass().getEStructuralFeature("forms");
        if (f == null || formName == null) {
            return null;
        }
        Object val;
        try {
            val = owner.eGet(f);
        } catch (RuntimeException e) {
            return null;
        }
        if (val instanceof List) {
            for (Object x : (List<?>) val) {
                if (x instanceof BasicForm && formName.equals(((MdObject) x).getName())) {
                    return (BasicForm) x;
                }
            }
        }
        return null;
    }

    /** Recursively build a form node, honouring the {@code cap} on total tree nodes. */
    private FormNode nodeOf(FormItem item, int cap, int[] counter, FormDetails r) {
        if (item == null) {
            return null;
        }
        if (counter[0] >= cap) {
            r.truncated = true;
            return null;
        }
        counter[0]++;
        FormNode n = new FormNode();
        n.name = item.getName();
        n.kind = item.eClass().getName();
        n.itemType = itemTypeOf(item);
        if (item instanceof DataItem) {
            n.dataPath = pathOf(((DataItem) item).getDataPath());
        }
        if (item instanceof Titled) {
            n.title = ruOf(((Titled) item).getTitle());
        }
        // Static .form properties (design-time values; runtime BSL may override).
        try {
            if (item instanceof Visible) {
                n.visible = ((Visible) item).isVisible();
                n.enabled = ((Visible) item).isEnabled();
            }
            if (item instanceof FormField) {
                FormField ff = (FormField) item;
                n.readOnly = ff.isReadOnly();
                n.cellHyperlink = ff.isCellHyperlink() ? Boolean.TRUE : null;
                readInputFieldProps(ff, n); // password / choice-button ("eye") design props
            } else if (item instanceof Table) {
                n.readOnly = ((Table) item).isReadOnly();
            }
            if (item instanceof Button) { // command -> button
                Button b = (Button) item;
                n.command = commandRefName(b.getCommandName());
                n.representation = enumName(b.getRepresentation());
                n.placement = enumName(b.getPlacementArea());
            }
            if (item instanceof EventHandlerContainer) { // per-item (column/field) event handlers
                for (EventHandler h : ((EventHandlerContainer) item).getHandlers()) {
                    n.handlers.add(eventOf(h));
                }
            }
        } catch (RuntimeException ignored) {
            // properties are best-effort
        }
        if (item instanceof FormItemContainer) {
            for (FormItem child : ((FormItemContainer) item).getItems()) {
                FormNode cn = nodeOf(child, cap, counter, r);
                if (cn != null) {
                    n.children.add(cn);
                }
            }
        }
        return n;
    }

    /**
     * Input-field design props from the .form: РежимПароля (the field masks input) and the choice
     * ("eye") button – КнопкаВыбора / КартинкаКнопкиВыбора – the design-time signal for the reveal-password
     * idiom. Only an InputField carries these (its ext-info is InputFieldExtInfo); other field kinds have
     * none. Emits only the meaningful (true / present / non-Auto) value to keep the tree lean. The picture
     * is reported as presence, not a name (the .form ref is an EMF proxy, not a plain CommonPicture name).
     * Best-effort; never throws.
     */
    private void readInputFieldProps(FormField ff, FormNode n) {
        try {
            FieldExtInfo ext = ff.getExtInfo();
            if (!(ext instanceof InputFieldExtInfo)) {
                return;
            }
            InputFieldExtInfo in = (InputFieldExtInfo) ext;
            if (Boolean.TRUE.equals(in.getPasswordMode())) {
                n.passwordMode = Boolean.TRUE;
            }
            if (Boolean.TRUE.equals(in.getChoiceButton())) {
                n.choiceButton = Boolean.TRUE;
            }
            if (in.getChoiceButtonPicture() != null) {
                n.choiceButtonPicture = Boolean.TRUE;
            }
            String rep = enumName(in.getChoiceButtonRepresentation());
            if (rep != null && !"auto".equalsIgnoreCase(rep)) {
                n.choiceButtonRepresentation = rep;
            }
        } catch (RuntimeException ignored) {
            // design props are best-effort
        }
    }

    /** The Managed*Type literal of an item (field/group/button/decoration kind); null otherwise. */
    private String itemTypeOf(FormItem item) {
        Object t = null;
        try {
            if (item instanceof FormField) {
                t = ((FormField) item).getType();
            } else if (item instanceof FormGroup) {
                t = ((FormGroup) item).getType();
            } else if (item instanceof Button) {
                t = ((Button) item).getType();
            } else if (item instanceof Decoration) {
                t = ((Decoration) item).getType();
            }
        } catch (RuntimeException ignored) {
            // type is best-effort
        }
        return (t instanceof Enumerator) ? ((Enumerator) t).getName() : null;
    }

    /** Render a data path as a dotted string (e.g. "Объект.Наименование"); best-effort. */
    private String pathOf(AbstractDataPath dp) {
        if (dp == null) {
            return null;
        }
        try {
            List<String> segs = dp.getSegments();
            if (segs != null && !segs.isEmpty()) {
                return String.join(".", segs);
            }
            String s = dp.toString();
            return (s == null || s.isBlank()) ? null : s;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** BSL action procedure backing a form command; best-effort (never throws). */
    private String handlerOf(FormCommand cmd) {
        try {
            CommandHandlerContainer action = cmd.getAction();
            if (action != null) {
                for (EObject ch : action.eContents()) {
                    if (ch instanceof CommandHandler) {
                        String h = ((CommandHandler) ch).getName();
                        if (h != null && !h.isBlank()) {
                            return h;
                        }
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // handler wiring is best-effort
        }
        return null;
    }

    /** Map a form event handler to {procedure, event}; the event name is best-effort. */
    private FormEvt eventOf(EventHandler h) {
        FormEvt e = new FormEvt();
        e.name = h.getName();
        try {
            Event ev = h.getEvent();
            if (ev != null && !ev.eIsProxy()) {
                e.event = ev.getName();
            }
        } catch (RuntimeException ignored) {
            // event may be an unresolved proxy
        }
        return e;
    }

    /** A command reference's name, best-effort (matches a form command in "commands"); never throws. */
    private String commandRefName(EObject cmd) {
        if (cmd == null) {
            return null;
        }
        try {
            EStructuralFeature nf = cmd.eClass().getEStructuralFeature("name");
            if (nf != null) {
                Object v = cmd.eGet(nf);
                if (v != null && !v.toString().isBlank()) {
                    return v.toString();
                }
            }
        } catch (RuntimeException ignored) {
            // name resolution is best-effort
        }
        return cmd.eClass().getName(); // last resort: the command kind
    }

    /** The literal name of an EMF enum value (Enumerator), or null. */
    private String enumName(Object e) {
        return (e instanceof Enumerator) ? ((Enumerator) e).getName() : null;
    }

    /**
     * Collect the form's declarative conditional appearance (УсловноеОформление) from the .form
     * (DCS settings): for each rule the formatted fields, the selection conditions and the set
     * appearance parameters. Rules built at runtime in BSL (УстановитьУсловноеОформление) are NOT
     * here – only what is stored declaratively in the .form. Best-effort; never throws.
     */
    private void collectConditionalAppearance(Form form, FormDetails r) {
        try {
            DataCompositionConditionalAppearance ca = form.getConditionalAppearance();
            if (ca == null) {
                return;
            }
            for (DataCompositionConditionalAppearanceItem it : ca.getItems()) {
                CondAppearance c = new CondAppearance();
                c.use = it.isUse();
                if (it.getSelection() != null) {
                    for (DataCompositionAppearanceField f : it.getSelection().getItems()) {
                        String fn = dcValueText(f.getField());
                        if (fn != null) {
                            c.fields.add(fn);
                        }
                    }
                }
                DataCompositionFilter filter = it.getFilter();
                if (filter != null) {
                    for (FilterItem fi : filter.getItems()) {
                        String cond = renderFilterItem(fi);
                        if (cond != null) {
                            c.filter.add(cond);
                        }
                    }
                }
                if (it.getAppearance() != null) {
                    for (DataCompositionParameterValue pv : it.getAppearance().getItems()) {
                        if (!pv.isUse()) {
                            continue;
                        }
                        String pname = (pv.getParameter() instanceof DataCompositionParameter)
                                ? ((DataCompositionParameter) pv.getParameter()).getValue()
                                : dcValueText(pv.getParameter());
                        if (pname == null) {
                            continue;
                        }
                        String val = (pv.getValues() != null && !pv.getValues().isEmpty())
                                ? dcValueText(pv.getValues().get(0)) : null;
                        c.appearance.add(new String[] { pname, val });
                    }
                }
                r.conditionalAppearance.add(c);
            }
        } catch (RuntimeException ignored) {
            // declarative conditional appearance is best-effort
        }
    }

    /** Render a DCS filter item (field comparison value) as a readable condition; best-effort. */
    private String renderFilterItem(FilterItem fi) {
        try {
            if (!(fi instanceof DataCompositionFilterItem)) {
                return fi.eClass().getName(); // a filter group (AND/OR) – note the kind, no deep render
            }
            DataCompositionFilterItem f = (DataCompositionFilterItem) fi;
            String left = dcValueText(f.getLeft());
            String op = enumName(f.getComparisonType());
            StringBuilder right = new StringBuilder();
            if (f.getRight() != null) {
                for (EObject rv : f.getRight()) {
                    String s = dcValueText(rv);
                    if (s != null) {
                        right.append(right.length() > 0 ? ", " : "").append(s);
                    }
                }
            }
            StringBuilder out = new StringBuilder(left != null ? left : "?");
            if (op != null) {
                out.append(' ').append(op);
            }
            if (right.length() > 0) {
                out.append(' ').append(right);
            }
            return out.toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Render a DCS / mcore value (a field reference or a literal) as readable text; best-effort.
     * DataCompositionField/Parameter expose getValue() (the dotted field / param name); other value
     * kinds fall back to a "value"/"name"/"content" attribute, else the eClass kind.
     */
    private String dcValueText(EObject v) {
        if (v == null) {
            return null;
        }
        try {
            if (v instanceof DataCompositionField) {
                return ((DataCompositionField) v).getValue();
            }
            if (v instanceof DataCompositionParameter) {
                return ((DataCompositionParameter) v).getValue();
            }
            for (String fname : new String[] { "value", "name", "content" }) {
                EStructuralFeature f = v.eClass().getEStructuralFeature(fname);
                if (f instanceof EAttribute) {
                    Object x = v.eGet(f);
                    if (x != null && !x.toString().isBlank()) {
                        return x.toString();
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // value rendering is best-effort
        }
        return v.eClass().getName();
    }

    // ---- CommonPicture / binary resource export -------------------------------------------

    /** One variant of a CommonPicture (a row of the Picture.zip manifest). */
    public static final class PictureVariant {
        public String name;             // zip entry: "l", "400.png", "<hash>.svg", "Picture.png"
        public String screenDensity;    // ldpi / mdpi / hdpi / ... (DPI bucket)
        public String interfaceVariant; // version8_5 / version8_2 / version8_2_OrdinaryApp
        public String theme;            // theme name ("" = default)
        public boolean isTemplate;      // recolorable template picture
        public int glyphWidth;
        public int glyphHeight;
        public long sizeBytes;          // size of the entry in the zip
        public String contentType;      // image/svg+xml | image/png
    }

    /** Result of {@link #exportPicture}. */
    public static final class PictureResult {
        public boolean found;
        public String fqn;
        public String message;
        public String zipPath;          // project-relative Picture.zip path
        public List<PictureVariant> variants = new ArrayList<>();
        public String recommended;      // suggested variant name (8.5 vector/template first)
        // set only when a specific variant is requested:
        public String selectedName;
        public String selectedContentType;
        public long selectedSize;
        public String base64;
    }

    /**
     * Export a CommonPicture's content: list the variants from the Picture.zip manifest (DPI /
     * interface variant / theme / isTemplate) and, when {@code variant} is given, return that entry's
     * bytes as base64. {@code variant} accepts an exact entry name, or "svg" (the vector master) /
     * "best" (svg else the largest PNG). Resolves CommonPicture.&lt;name&gt; to
     * &lt;project&gt;/src/CommonPictures/&lt;name&gt;/Picture.zip. Read-only; never throws.
     */
    public PictureResult exportPicture(String projectName, String fqn, String variant) {
        PictureResult r = new PictureResult();
        r.fqn = fqn;
        try {
            IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (!p.exists() || !p.isOpen()) {
                r.message = "project not found or closed: " + projectName;
                return r;
            }
            String name = null;
            if (fqn != null) {
                int dot = fqn.indexOf('.');
                if (dot > 0 && fqn.substring(0, dot).equalsIgnoreCase("CommonPicture")) {
                    name = fqn.substring(dot + 1);
                }
            }
            if (name == null || name.isBlank()) {
                r.message = "expected CommonPicture.<name>, got: " + fqn;
                return r;
            }
            String src = p.getFolder("src").exists() ? "src/" : "";
            IFile zip = p.getFile(src + "CommonPictures/" + name + "/Picture.zip");
            if (!zip.exists() || zip.getLocation() == null) {
                r.message = "Picture.zip not found for CommonPicture." + name;
                return r;
            }
            r.zipPath = zip.getProjectRelativePath().toString();
            Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
            String manifestXml = null;
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zip.getLocation().toFile())) {
                java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    java.util.zip.ZipEntry ze = en.nextElement();
                    if (ze.isDirectory()) {
                        continue;
                    }
                    byte[] bytes = zf.getInputStream(ze).readAllBytes();
                    entries.put(ze.getName(), bytes);
                    if (ze.getName().equalsIgnoreCase("manifest.xml")) {
                        manifestXml = new String(bytes, StandardCharsets.UTF_8);
                    }
                }
            }
            parsePictureManifest(manifestXml, entries, r);
            r.recommended = recommendVariant(r.variants);
            r.found = true;
            if (variant != null && !variant.isBlank()) {
                String pick = resolveVariantName(variant, r);
                byte[] bytes = pick != null ? entries.get(pick) : null;
                if (bytes == null) {
                    r.message = "variant not found: " + variant;
                } else {
                    r.selectedName = pick;
                    r.selectedSize = bytes.length;
                    r.selectedContentType = contentTypeOf(pick);
                    r.base64 = java.util.Base64.getEncoder().encodeToString(bytes);
                }
            }
            return r;
        } catch (Exception e) {
            r.message = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
            return r;
        }
    }

    /** Build variants from the manifest's {@code <PictureVariant/>} rows; defensive fallback to raw entries. */
    private void parsePictureManifest(String xml, Map<String, byte[]> entries, PictureResult r) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        if (xml != null) {
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("<PictureVariant\\b([^>]*)/>").matcher(xml);
            while (m.find()) {
                String attrs = m.group(1);
                PictureVariant v = new PictureVariant();
                v.name = attr(attrs, "name");
                v.screenDensity = attr(attrs, "screenDensity");
                v.interfaceVariant = attr(attrs, "interfaceVariant");
                v.theme = attr(attrs, "theme");
                v.isTemplate = "true".equalsIgnoreCase(attr(attrs, "isTemplate"));
                v.glyphWidth = intAttr(attrs, "glyphWidth");
                v.glyphHeight = intAttr(attrs, "glyphHeight");
                if (v.name != null) {
                    byte[] b = entries.get(v.name);
                    v.sizeBytes = b != null ? b.length : 0;
                    v.contentType = contentTypeOf(v.name);
                    seen.add(v.name);
                }
                r.variants.add(v);
            }
        }
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            if (e.getKey().equalsIgnoreCase("manifest.xml") || seen.contains(e.getKey())) {
                continue;
            }
            PictureVariant v = new PictureVariant();
            v.name = e.getKey();
            v.sizeBytes = e.getValue().length;
            v.contentType = contentTypeOf(e.getKey());
            r.variants.add(v);
        }
    }

    private static String attr(String attrs, String key) {
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile(key + "=\"([^\"]*)\"").matcher(attrs);
        return m.find() ? m.group(1) : null;
    }

    private static int intAttr(String attrs, String key) {
        String s = attr(attrs, key);
        try {
            return s != null ? Integer.parseInt(s.trim()) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String contentTypeOf(String name) {
        if (name == null) {
            return null;
        }
        String n = name.toLowerCase();
        if (n.endsWith(".png")) {
            return "image/png";
        }
        if (n.endsWith(".svg") || n.equals("l")) {
            return "image/svg+xml";
        }
        return "application/octet-stream";
    }

    /** Suggested variant: the vector/template master (8.5 SVG) if present, else the largest PNG. */
    private String recommendVariant(List<PictureVariant> vs) {
        PictureVariant svg = null;
        PictureVariant largestPng = null;
        for (PictureVariant v : vs) {
            if ("image/svg+xml".equals(v.contentType)
                    && (svg == null || "version8_5".equals(v.interfaceVariant))) {
                svg = v;
            } else if ("image/png".equals(v.contentType)
                    && (largestPng == null || v.sizeBytes > largestPng.sizeBytes)) {
                largestPng = v;
            }
        }
        PictureVariant pick = svg != null ? svg : largestPng;
        return pick != null ? pick.name : null;
    }

    /** Map a {@code variant} argument ("svg" / "best" / an exact entry name) to a zip entry name. */
    private String resolveVariantName(String variant, PictureResult r) {
        if ("svg".equalsIgnoreCase(variant)) {
            for (PictureVariant v : r.variants) {
                if ("image/svg+xml".equals(v.contentType)) {
                    return v.name;
                }
            }
            return null;
        }
        if ("best".equalsIgnoreCase(variant)) {
            return r.recommended;
        }
        return variant; // exact entry name
    }

    /** Russian entry of a localized string map (falls back to the first value); null-safe. */
    private String ruOf(EMap<String, String> m) {
        if (m == null || m.isEmpty()) {
            return null;
        }
        String ru = m.get("ru");
        if (ru != null && !ru.isBlank()) {
            return ru;
        }
        for (String v : m.values()) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    // ---- Managed form rendering (EDT native offscreen render → PNG) ------------------------------

    /** Result of {@link #renderForm}. */
    public static final class RenderResult {
        public boolean ok;
        public String fqn;
        public String message;
        public String pngPath;
        public Integer width;
        public Integer height;
        public String variant;
        public String theme;
    }

    /**
     * Render a managed form to a PNG via EDT's native offscreen renderer (the engine behind the form
     * editor's WYSIWYG preview): build an offscreen SWT scaffold, a desktop theme projection for the
     * chosen interface variant (TAXI / VERSION8_5) + theme, then drive {@code HippoLayoutService
     * .createHippoSession(...)} inside a read transaction and save {@code session.getFormImageData()}.
     * Must run on the SWT UI thread (the Display thread); the MCP single-thread executor owns it.
     */
    public RenderResult renderForm(String projectName, String fqn, String variantName, String themeName,
            String densityName, int commonRatio, int width, int height, int scale, String outPath) {
        RenderResult r = new RenderResult();
        r.fqn = fqn;
        IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!p.exists() || !p.isOpen()) {
            r.message = "project not found or closed: " + projectName;
            return r;
        }
        IBmModelManager mm = ServiceAccess.get(IBmModelManager.class);
        if (mm == null) {
            r.message = "BM model manager unavailable";
            return r;
        }
        IBmModel model = mm.getModel(p);
        if (model == null) {
            r.message = "no BM model for project: " + projectName;
            return r;
        }
        final ClientInterfaceVariant variant = parseVariant(variantName);
        final ClientInterfaceTheme theme = parseTheme(themeName);
        final ClientInterfaceScale density = parseScale(densityName);
        final int ratio = commonRatio > 0 ? commonRatio : 100;
        final int w = width > 0 ? width : 1280;
        final int h = height > 0 ? height : 800;

        final int zoom = scale;
        // Where to run the render depends on the runtime. NativeRenderService.setWindows() disposes
        // widgets it owns, and in a GUI EDT those belong to the workbench's main-thread Display – so
        // the render MUST run on that thread (a private Display throws "Invalid thread access" inside
        // setWindows). Headless CLI has no workbench Display, so there we own a private one on a
        // dedicated single thread (created once, reused). Pick per runtime:
        try {
            Display workbenchDisplay = workbenchDisplayOrNull();
            if (workbenchDisplay != null && !workbenchDisplay.isDisposed()) {
                // GUI EDT: marshal onto the workbench UI thread. syncExec briefly blocks the editor
                // for the render (~1-2 s) – the same thread EDT's own form preview uses.
                final Display wd = workbenchDisplay;
                wd.syncExec(() -> renderOnUi(r, wd, model, mm, fqn, variant, theme, density, ratio, w, h, zoom, outPath));
            } else {
                // Headless CLI: private Display on the dedicated render thread.
                RENDER_EXECUTOR.submit(() -> {
                    Display d = Display.findDisplay(Thread.currentThread());
                    if (d == null) {
                        d = new Display();
                    }
                    renderOnUi(r, d, model, mm, fqn, variant, theme, density, ratio, w, h, zoom, outPath);
                    return null;
                }).get();
            }
        } catch (Exception e) {
            if (r.message == null) {
                r.message = describeThrowable(e);
            }
        }
        return r;
    }

    /** The workbench's main-thread Display in a GUI EDT, or null when running headless (CLI). */
    private static Display workbenchDisplayOrNull() {
        try {
            if (PlatformUI.isWorkbenchRunning()) {
                return PlatformUI.getWorkbench().getDisplay();
            }
        } catch (Throwable ignore) {
            // No workbench (headless) – fall through to null.
        }
        return null;
    }

    /** The whole render, on the SWT UI thread. Fills {@code r}; never throws. */
    private void renderOnUi(RenderResult r, Display d, IBmModel model, IBmModelManager mm, String fqn,
            ClientInterfaceVariant variant, ClientInterfaceTheme theme, ClientInterfaceScale scale,
            int commonRatio, int w, int h, int zoom, String outPath) {
        final boolean isDark = theme == ClientInterfaceTheme.ECIT_DARK;
        final LFTargetPlatform targetPlatform = LFTargetPlatform.ELFT_THIN_THICK_CLIENT;
        final Version version = Version.LATEST;

        Shell shell = null;
        ScrolledComposite scrolled = null;
        Image scratch = null;
        Image winImg = null;
        GC gc = null;
        IThemeProjection projection = null;
        ILayoutRenderService renderService = null;
        LayoutTransformationService transformator = null;
        try {
            // 1) offscreen SWT scaffold (NativeRenderService casts only to Composite / ScrolledComposite)
            shell = new Shell(d, SWT.NO_TRIM);
            shell.setSize(w, h);
            scrolled = new ScrolledComposite(shell, SWT.H_SCROLL | SWT.V_SCROLL);
            scrolled.setBounds(0, 0, w, h);
            Composite formComposite = new Composite(scrolled, SWT.NONE);
            formComposite.setBounds(0, 0, w, h);
            scrolled.setContent(formComposite);
            // Realize the native window off-screen so the native renderer has a valid device context
            // (it draws into the composite's OS handle; an unrealized/zero-state window has none).
            shell.setLocation(-32000, -32000);
            shell.open();
            shell.setVisible(false);
            formComposite.layout();
            Color bg = d.getSystemColor(SWT.COLOR_WHITE);

            // 2) theme projection for the chosen variant (HippoThemeTaxi lives in a non-exported
            //    package → reflection). Compatibility mode comes from the configuration.
            CompatibilityMode compat = readCompatibilityMode(model);
            scratch = new Image(d, 1, 1);
            gc = new GC(scratch);
            projection = IThemeProjection.createDesktopProjection(gc);
            setHippoTheme(projection, createHippoTheme(isDark, targetPlatform, variant, compat));
            IPlatformVisualComputer pvc = projection.getPlatformVisualComputer();
            pvc.setCommonRatio(commonRatio);

            // 3) services + wire the offscreen windows. EDT's own composite doubles as the image
            //    supplier + mouse listener, so a null supplier/listener trips SWT's null check –
            //    pass a real backing image and a no-op listener.
            renderService = new RenderServiceProvider().get(version);
            transformator = new TransformatorServiceProvider().get();
            winImg = new Image(d, w, h);
            final Image suppliedImg = winImg;
            renderService.setWindows(formComposite, bg, () -> suppliedImg, scrolled, new MouseAdapter() {
            });

            final ILayoutRenderService rs = renderService;
            final LayoutTransformationService ts = transformator;
            final IPlatformVisualComputer fpvc = pvc;
            final ImageData[] out = {null};
            final String[] err = {null};
            // 4) drive the render in a read-WRITE transaction that is ROLLED BACK: the layout/render
            //    pipeline mutates the form model (e.g. setVerticalScroll), so a read-only transaction
            //    fails; executeAndRollback is what EDT's own WYSIWYG uses – changes never persist.
            model.executeAndRollback(new AbstractBmTask<Object>("edt-bridge.renderForm") {
                @Override
                public Object execute(IBmTransaction tx, IProgressMonitor monitor) {
                    EObject mdForm = tx.getTopObjectByFqn(fqn);
                    Form form = asFormContent(mdForm);
                    if (form == null) {
                        int idx = fqn.lastIndexOf(".Form.");
                        if (idx > 0) {
                            EObject owner = tx.getTopObjectByFqn(fqn.substring(0, idx));
                            if (owner != null) {
                                form = asFormContent(findForm(owner, fqn.substring(idx + ".Form.".length())));
                            }
                        }
                    }
                    if (form == null) {
                        err[0] = "form not found / not a form: " + fqn;
                        return null;
                    }
                    CommandInterfaceMapping cmi = buildCmi(form, model, mm);
                    IHippoLayModelSession session = HippoLayoutService.INSTANCE.createHippoSession(
                            tx, form, ts, rs, fpvc, cmi, variant, theme, targetPlatform, scale,
                            w, h, false, 0, 0, 0, 0, false, version,
                            NativeRenderEvent.buildUpdateEvent(), false);
                    try {
                        out[0] = session.getFormImageData();
                    } finally {
                        session.dispose();
                    }
                    return null;
                }
            });
            if (err[0] != null) {
                r.message = err[0];
                return;
            }
            if (out[0] == null) {
                r.message = "render produced no image (getFormImageData == null)";
                return;
            }
            // The native render is fixed at 100% (device zoom crashes the native renderer headless),
            // so scale > 100 raster-upscales the finished PNG to match a Hi-DPI screen.
            ImageData img = out[0];
            if (zoom > 100) {
                img = img.scaledTo(Math.round(img.width * zoom / 100f), Math.round(img.height * zoom / 100f));
            }
            ImageLoader loader = new ImageLoader();
            loader.data = new ImageData[] {img};
            loader.save(outPath, SWT.IMAGE_PNG);
            r.ok = true;
            r.pngPath = outPath;
            r.width = img.width;
            r.height = img.height;
            r.variant = variant.getName();
            r.theme = theme.getName();
        } catch (Throwable t) {
            r.message = describeThrowable(t);
        } finally {
            disposeQuietly(gc);
            disposeQuietly(scratch);
            disposeQuietly(winImg);
            try {
                if (projection != null) {
                    projection.dispose();
                }
            } catch (RuntimeException ignored) {
                // best-effort
            }
            try {
                if (transformator != null) {
                    transformator.dispose();
                }
            } catch (RuntimeException ignored) {
                // best-effort
            }
            try {
                if (renderService != null) {
                    renderService.dispose();
                }
            } catch (RuntimeException ignored) {
                // best-effort
            }
            if (shell != null) {
                shell.dispose();
            }
        }
    }

    /**
     * The form's command-interface mapping model (needed by the layout session). Built the way EDT's
     * form editor does it: register the mapping in a {@link MappingController} and get the completed
     * root synchronously – {@code buildRootModel()} alone leaves the CMI incomplete for forms with a
     * rich command interface (object forms).
     */
    private CommandInterfaceMapping buildCmi(Form form, IBmModel model, IBmModelManager mm) {
        MappingController controller = new MappingController();
        FormCommandInterfaceMapping mapping = new FormCommandInterfaceMapping(form, model, mm);
        controller.register(mapping);
        return controller.getMappingRoot(CommandInterfaceMapping.class);
    }

    /** The configuration's compatibility mode (affects the rendered look); null if unavailable. */
    private CompatibilityMode readCompatibilityMode(IBmModel model) {
        try {
            return model.executeReadonlyTask(new AbstractBmTask<CompatibilityMode>("edt-bridge.compatMode") {
                @Override
                public CompatibilityMode execute(IBmTransaction tx, IProgressMonitor monitor) {
                    EObject o = tx.getTopObjectByFqn("Configuration");
                    return (o instanceof Configuration) ? ((Configuration) o).getCompatibilityMode() : null;
                }
            });
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Build a HippoThemeTaxi via reflection (its package theme.hippo is not exported). */
    private Object createHippoTheme(boolean isDark, LFTargetPlatform target, ClientInterfaceVariant variant,
            CompatibilityMode compat) throws Exception {
        Bundle b = Platform.getBundle("com._1c.g5.v8.dt.form.presentation");
        Class<?> cls = b.loadClass("com._1c.g5.v8.dt.form.presentation.theme.hippo.HippoThemeTaxi");
        Constructor<?> ctor = cls.getConstructor(boolean.class, LFTargetPlatform.class,
                ClientInterfaceVariant.class, CompatibilityMode.class);
        return ctor.newInstance(isDark, target, variant, compat);
    }

    /** Reflectively call {@code IThemeProjection.setHippoTheme(AbstractHippoTheme)} (param non-exported). */
    private void setHippoTheme(IThemeProjection projection, Object hippoTheme) throws Exception {
        Bundle b = Platform.getBundle("com._1c.g5.v8.dt.form.presentation");
        Class<?> abstractTheme = b.loadClass("com._1c.g5.v8.dt.form.presentation.theme.hippo.AbstractHippoTheme");
        Method setter = IThemeProjection.class.getMethod("setHippoTheme", abstractTheme);
        setter.invoke(projection, hippoTheme);
    }

    private ClientInterfaceVariant parseVariant(String name) {
        if (name != null) {
            String n = name.trim().toUpperCase().replace("-", "_").replace(".", "_").replace(" ", "_");
            if (n.equals("TAXI") || n.equals("TAXI8_3") || n.equals("V8_3")) {
                return ClientInterfaceVariant.TAXI;
            }
            if (n.contains("8_5") || n.equals("VERSION8_5") || n.equals("V8_5")) {
                return ClientInterfaceVariant.VERSION8_5;
            }
            if (n.equals("TAXI_COMPACT")) {
                return ClientInterfaceVariant.TAXI_COMPACT;
            }
        }
        return ClientInterfaceVariant.VERSION8_5;
    }

    private ClientInterfaceTheme parseTheme(String name) {
        if (name != null && name.trim().toUpperCase().contains("DARK")) {
            return ClientInterfaceTheme.ECIT_DARK;
        }
        return ClientInterfaceTheme.ECIT_LIGHT;
    }

    /** Interface density (field spacing): COMPACT matches EDT's compact editor mode; default NORMAL. */
    private ClientInterfaceScale parseScale(String name) {
        if (name != null && name.trim().toUpperCase().contains("COMPACT")) {
            return ClientInterfaceScale.ECIS_COMPACT;
        }
        return ClientInterfaceScale.ECIS_NORMAL;
    }

    /** Class + message + top stack frames (and cause) – enough to locate a render-pipeline failure. */
    private String describeThrowable(Throwable t) {
        StringBuilder sb = new StringBuilder(t.getClass().getName());
        if (t.getMessage() != null) {
            sb.append(": ").append(t.getMessage());
        }
        StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < Math.min(10, st.length); i++) {
            sb.append("\n  at ").append(st[i]);
        }
        Throwable c = t.getCause();
        if (c != null && c != t) {
            sb.append("\nCaused by: ").append(c.getClass().getName());
            if (c.getMessage() != null) {
                sb.append(": ").append(c.getMessage());
            }
            StackTraceElement[] cs = c.getStackTrace();
            for (int i = 0; i < Math.min(8, cs.length); i++) {
                sb.append("\n  at ").append(cs[i]);
            }
        }
        return sb.toString();
    }

    private void disposeQuietly(org.eclipse.swt.graphics.Resource res) {
        try {
            if (res != null && !res.isDisposed()) {
                res.dispose();
            }
        } catch (RuntimeException ignored) {
            // best-effort
        }
    }
}

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
package io.github.keyfire.edtbridge.ui;

import io.github.keyfire.edtbridge.EdtBridgePrefs;
import java.util.Locale;
import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.eclipse.core.runtime.preferences.InstanceScope;

/**
 * Preferences page "EDT-Bridge" (Window -> Preferences). Lets the user set the write token, port and
 * the evaluate switch in the UI and have them persist per workspace, so a GUI EDT launched from a
 * plain shortcut (no {@code EDT_BRIDGE_*} environment) can still authenticate write tools. Labels are
 * shown in Russian when EDT runs in a Russian locale, English otherwise. Values are stored under
 * {@link EdtBridgePrefs#NODE}; a launch-time system property / environment variable still wins.
 */
public final class EdtBridgePreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

    private static final boolean RU = Locale.getDefault().getLanguage().equals("ru");

    private Image infoImage;

    private static String tr(String en, String ru) {
        return RU ? ru : en;
    }

    public EdtBridgePreferencePage() {
        super(GRID);
        setPreferenceStore(new ScopedPreferenceStore(InstanceScope.INSTANCE, EdtBridgePrefs.NODE));
    }

    @Override
    protected void createFieldEditors() {
        StringFieldEditor token = new StringFieldEditor(EdtBridgePrefs.KEY_TOKEN,
                tr("Token for write tools:", "Токен для инструментов записи:"),
                getFieldEditorParent());
        addField(token);
        Text tokenText = token.getTextControl(getFieldEditorParent());
        if (tokenText != null) {
            tokenText.setEchoChar('•'); // mask the token
        }

        IntegerFieldEditor port = new IntegerFieldEditor(EdtBridgePrefs.KEY_PORT,
                tr("MCP server port:", "Порт MCP-сервера:"),
                getFieldEditorParent());
        port.setValidRange(1, 65535);
        addField(port);

        addField(new BooleanFieldEditor(EdtBridgePrefs.KEY_ALLOW_EVALUATE,
                tr("Allow running arbitrary BSL during debugging – unsafe, test stands only",
                   "Разрешить выполнение произвольного кода BSL при отладке – небезопасно, только для тестовых стендов"),
                getFieldEditorParent()));
    }

    /** Field editors on top, then an information banner (with an (i) icon) below them. */
    @Override
    protected Control createContents(Composite parent) {
        Composite area = new Composite(parent, SWT.NONE);
        area.setLayout(new GridLayout(1, false));
        area.setLayoutData(new GridData(GridData.FILL_BOTH));

        Control fields = super.createContents(area);
        fields.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        createInfoBanner(area);
        return area;
    }

    private void createInfoBanner(Composite parent) {
        Composite banner = new Composite(parent, SWT.BORDER);
        GridLayout layout = new GridLayout(2, false);
        layout.marginWidth = 8;
        layout.marginHeight = 8;
        layout.horizontalSpacing = 8;
        banner.setLayout(layout);
        // directly under the fields (not pushed to the bottom of the page)
        banner.setLayoutData(new GridData(SWT.FILL, SWT.BEGINNING, true, false));

        Label icon = new Label(banner, SWT.NONE);
        icon.setImage(infoImage(parent.getDisplay()));
        icon.setLayoutData(new GridData(SWT.CENTER, SWT.BEGINNING, false, false));

        Label text = new Label(banner, SWT.WRAP);
        text.setText(tr(
                "The port takes effect after an EDT restart. A -Dedt.bridge.* system property or an "
                        + "EDT_BRIDGE_* environment variable set at launch takes precedence over the "
                        + "values specified here.",
                "Порт вступает в силу после перезапуска EDT. Системное свойство -Dedt.bridge.* или "
                        + "переменная окружения EDT_BRIDGE_*, заданные при запуске, имеют приоритет над "
                        + "указанными значениями."));
        GridData textData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        textData.widthHint = 480;
        text.setLayoutData(textData);
    }

    /** The bundled info icon (icons/info.png), or the system information image as a fallback. */
    private Image infoImage(Display display) {
        if (infoImage != null) {
            return infoImage;
        }
        try {
            java.net.URL url = org.osgi.framework.FrameworkUtil.getBundle(getClass()).getEntry("icons/info.png");
            if (url != null) {
                infoImage = org.eclipse.jface.resource.ImageDescriptor.createFromURL(url).createImage();
                return infoImage;
            }
        } catch (RuntimeException ignore) {
            // fall through to the system image
        }
        return display.getSystemImage(SWT.ICON_INFORMATION);
    }

    @Override
    public void dispose() {
        if (infoImage != null && !infoImage.isDisposed()) {
            infoImage.dispose();
            infoImage = null;
        }
        super.dispose();
    }

    @Override
    public void init(IWorkbench workbench) {
        getPreferenceStore().setDefault(EdtBridgePrefs.KEY_PORT, 8770);
        getPreferenceStore().setDefault(EdtBridgePrefs.KEY_ALLOW_EVALUATE, false);
    }
}

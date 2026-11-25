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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.xtext.naming.QualifiedName;
import org.eclipse.xtext.resource.IEObjectDescription;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.Picture;
import com._1c.g5.v8.dt.mcore.PictureRef;
import com._1c.g5.v8.dt.metadata.mdclass.CommonPicture;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com._1c.g5.v8.dt.platform.version.Version;
import io.github.keyfire.edtbridge.core.PictureNames;

/**
 * The picture a name stands for, as {@code Form.form} keeps it: a {@code PictureRef} to a standard
 * picture of the platform or to a common picture of the configuration. The standard pictures come
 * from the platform's own provider for the project's version, like the platform types do.
 */
final class FormPictures {
    private FormPictures() { }

    /** The reference to store for the named picture; null for an empty name, which clears it. */
    static PictureRef reference(IBmTransaction tx, String raw, Version version) {
        PictureNames.Name name = PictureNames.parse(raw);
        if (name == null) return null;
        Picture target = null;
        if (name.kind() != PictureNames.Kind.COMMON) target = standard(name.name(), version);
        if (target == null && name.kind() != PictureNames.Kind.STANDARD) target = common(tx, name.name());
        if (target == null) throw new IllegalArgumentException(missing(name, version));
        PictureRef reference = McoreFactory.eINSTANCE.createPictureRef();
        reference.setPicture(target);
        return reference;
    }

    /** How a stored picture is named back, the way {@link #reference} takes it. */
    static String name(Picture stored) {
        Picture target = stored instanceof PictureRef reference ? reference.getPicture() : stored;
        if (target == null) return null;
        if (target instanceof CommonPicture common) return "CommonPicture." + common.getName();
        if (target.eIsProxy()) {
            URI uri = ((InternalEObject) target).eProxyURI();
            String last = uri == null ? null : uri.fragment() != null ? uri.fragment() : uri.lastSegment();
            if (last != null) return "StdPicture." + last.substring(last.lastIndexOf('.') + 1);
        }
        String own = stringFeature(target, "name");
        return own == null ? null : "StdPicture." + own;
    }

    private static Picture standard(String name, Version version) {
        IEObjectProvider provider = provider(version);
        for (IEObjectDescription description : provider.getEObjectDescriptions(any -> true)) {
            QualifiedName qualified = description.getName();
            if (qualified != null && name.equalsIgnoreCase(qualified.getLastSegment())) {
                return provider.createProxy(qualified.toString());
            }
        }
        return null;
    }

    private static Picture common(IBmTransaction tx, String name) {
        EObject object = tx.getTopObjectByFqn("CommonPicture." + name);
        return object instanceof CommonPicture picture ? picture : null;
    }

    private static IEObjectProvider provider(Version version) {
        IEObjectProvider provider = IEObjectProvider.Registry.INSTANCE.get(McorePackage.Literals.PICTURE, version);
        if (provider == null) throw new IllegalStateException("EDT gives no standard pictures for version " + version);
        return provider;
    }

    private static String missing(PictureNames.Name name, Version version) {
        if (name.kind() == PictureNames.Kind.COMMON) {
            return "the configuration has no common picture " + name.name() + " (CommonPicture." + name.name() + ")";
        }
        List<String> similar = new ArrayList<>();
        String head = name.name().substring(0, Math.min(3, name.name().length())).toLowerCase(Locale.ROOT);
        for (IEObjectDescription description : provider(version).getEObjectDescriptions(any -> true)) {
            String last = description.getName() == null ? null : description.getName().getLastSegment();
            if (last != null && last.toLowerCase(Locale.ROOT).startsWith(head) && similar.size() < 10) similar.add(last);
        }
        return "no standard picture " + name.name() + (name.kind() == PictureNames.Kind.EITHER
                ? " and no common picture of that name" : "") + " in platform " + version
                + (similar.isEmpty() ? "" : "; similar standard pictures: " + String.join(", ", similar));
    }

    private static String stringFeature(EObject object, String feature) {
        EStructuralFeature structural = object.eClass().getEStructuralFeature(feature);
        Object value = structural == null ? null : object.eGet(structural);
        return value instanceof String text && !text.isBlank() ? text : null;
    }
}

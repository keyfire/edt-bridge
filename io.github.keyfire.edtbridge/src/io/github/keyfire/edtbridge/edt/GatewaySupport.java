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

import org.eclipse.core.resources.IProject;
import com._1c.g5.v8.dt.mcore.NumberQualifiers;
import com._1c.g5.v8.dt.mcore.StringQualifiers;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.platform.version.IRuntimeVersionSupport;
import com._1c.g5.v8.dt.platform.version.Version;
import com._1c.g5.wiring.ServiceAccess;

/**
 * Small helpers shared by more than one gateway. Kept stateless (pure statics) so any gateway in
 * this package can call them without wiring. Split out of the original model gateway during the break-up
 * of that class; behaviour is unchanged.
 */
final class GatewaySupport {

    private GatewaySupport() {
    }

    /** A throwable's cause chain as {@code Type: message <- Type: message} (up to 6 links). */
    static String describeCause(Throwable ex) {
        StringBuilder sb = new StringBuilder();
        Throwable t = ex;
        int depth = 0;
        while (t != null && depth < 6) {
            if (depth > 0) {
                sb.append(" <- ");
            }
            sb.append(t.getClass().getSimpleName());
            if (t.getMessage() != null) {
                sb.append(": ").append(t.getMessage());
            }
            if (t.getCause() == t) {
                break;
            }
            t = t.getCause();
            depth++;
        }
        return sb.toString();
    }

    /** The runtime (platform) version EDT associates with a project, or {@link Version#LATEST}. */
    static Version projectVersion(IProject p) {
        try {
            IRuntimeVersionSupport vs = ServiceAccess.get(IRuntimeVersionSupport.class);
            if (vs != null) {
                Version v = vs.getRuntimeVersionOrDefault(p, Version.LATEST);
                if (v != null) {
                    return v;
                }
            }
        } catch (RuntimeException ignored) {
            // fall through to LATEST
        }
        return Version.LATEST;
    }

    /**
     * Render a {@link TypeDescription} to a compact string: e.g. "Строка(150)", "Число(15, 2)",
     * "СправочникСсылка.Контрагенты", or "A, B" for a composite type. Best-effort; never throws.
     */
    static String renderType(TypeDescription td) {
        if (td == null) {
            return null;
        }
        List<TypeItem> items = td.getTypes();
        if (items == null || items.isEmpty()) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (TypeItem ti : items) {
            String n = null;
            try {
                n = McoreUtil.getTypeNameRu(ti);
                if (n == null || n.isBlank()) {
                    n = McoreUtil.getTypeName(ti);
                }
            } catch (RuntimeException ignored) {
                // unresolved / proxy type item
            }
            names.add((n == null || n.isBlank()) ? "?" : n);
        }
        String base = String.join(", ", names);
        if (items.size() == 1) {
            try {
                StringQualifiers sq = td.getStringQualifiers();
                NumberQualifiers nq = td.getNumberQualifiers();
                if (sq != null && sq.getLength() > 0) {
                    base += "(" + sq.getLength() + ")";
                } else if (nq != null && nq.getPrecision() > 0) {
                    base += "(" + nq.getPrecision()
                            + (nq.getScale() > 0 ? ", " + nq.getScale() : "") + ")";
                }
            } catch (RuntimeException ignored) {
                // qualifiers are optional
            }
        }
        return base;
    }

}

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
package io.github.keyfire.edtbridge.core;

import java.util.Locale;
import java.util.Map;

/** Compare an AST table source with an English metadata FQN, allowing the query's RU/EN prefixes. */
public final class QueryMetadataNames {
    private QueryMetadataNames() { }
    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("справочник", "catalog"), Map.entry("документ", "document"),
            Map.entry("журналдокументов", "documentjournal"), Map.entry("перечисление", "enum"),
            Map.entry("планобмена", "exchangeplan"), Map.entry("плансчетов", "chartofaccounts"),
            Map.entry("планвидовхарактеристик", "chartofcharacteristictypes"),
            Map.entry("планвидоврасчета", "chartofcalculationtypes"),
            Map.entry("регистрсведений", "informationregister"),
            Map.entry("регистрнакопления", "accumulationregister"),
            Map.entry("регистрбухгалтерии", "accountingregister"),
            Map.entry("регистррасчета", "calculationregister"),
            Map.entry("бизнеспроцесс", "businessprocess"), Map.entry("задача", "task"),
            Map.entry("константа", "constant"));

    public static boolean matches(String fqn, String table) {
        if (fqn == null || table == null) return false;
        String[] target = fqn.split("\\.");
        String[] source = table.split("\\.");
        if (target.length != 2 || source.length < 2) return false;
        String prefix = source[0].trim().toLowerCase(Locale.ROOT);
        prefix = TYPES.getOrDefault(prefix, prefix);
        return target[0].equalsIgnoreCase(prefix) && target[1].equalsIgnoreCase(source[1].trim());
    }
}

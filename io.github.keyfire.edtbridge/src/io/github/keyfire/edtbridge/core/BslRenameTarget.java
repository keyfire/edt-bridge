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
/** Resolve the compatible method alias without accessing an EDT workspace. */
public final class BslRenameTarget {
    private BslRenameTarget() {}
    public record Target(String owner, String methodName) {}
    public static Target resolve(String owner, String methodName, String method) {
        if (methodName == null && method == null) return null;
        String name = methodName;
        if (method != null) {
            String alias = method;
            int dot = alias.lastIndexOf('.');
            if (dot >= 0) {
                String aliasOwner = alias.substring(0, dot);
                alias = alias.substring(dot + 1);
                if (aliasOwner.isBlank() || aliasOwner.indexOf('.') < 0) {
                    throw new IllegalArgumentException("method alias must be a name or owner FQN plus method name");
                }
                if (owner != null && !owner.equalsIgnoreCase(aliasOwner)) {
                    throw new IllegalArgumentException("method and targetFqn name different module owners");
                }
                owner = owner == null ? aliasOwner : owner;
            }
            if (alias.isBlank()) throw new IllegalArgumentException("method alias cannot be empty");
            if (name != null && !name.equalsIgnoreCase(alias)) {
                throw new IllegalArgumentException("method and methodName name different methods");
            }
            if (name == null) name = alias;
        }
        if (name == null || name.isBlank()) throw new IllegalArgumentException("methodName cannot be empty");
        return new Target(owner, name);
    }
}

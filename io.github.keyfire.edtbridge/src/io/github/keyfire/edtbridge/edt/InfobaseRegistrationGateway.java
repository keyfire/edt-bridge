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

import java.util.UUID;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseReferences;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.ServerConnectionString;
import com._1c.g5.wiring.ServiceAccess;
import io.github.keyfire.edtbridge.core.PlatformSelection;
import io.github.keyfire.edtbridge.core.ServerInfobaseAddress;

/** Register an existing clustered infobase in EDT without connecting to or changing its database. */
public final class InfobaseRegistrationGateway {
    private static final Object REGISTRATION_LOCK = new Object();

    public static final class Result {
        public boolean ok;
        public boolean applied;
        public boolean verified;
        public String name;
        public String server;
        public String reference;
        public String version;
        public String uuid;
        public String plan;
        public String message;
    }

    public Result register(String name, String connect, String version, String user, boolean apply) {
        Result result = new Result();
        if (name == null || name.isBlank() || name.chars().anyMatch(Character::isISOControl)) {
            result.message = "name is required and must not contain control characters";
            return result;
        }
        result.name = name.trim();
        if (version == null || version.isBlank() || PlatformSelection.problem(version) != null) {
            result.message = "version must name a platform line or build, for example 8.3.27 or 8.3.27.1989";
            return result;
        }
        result.version = version.trim();
        if (user != null && (user.indexOf('"') >= 0 || user.chars().anyMatch(Character::isISOControl))) {
            result.message = "user must not contain quotes or control characters";
            return result;
        }
        ServerInfobaseAddress address;
        try {
            address = ServerInfobaseAddress.parse(connect);
        } catch (IllegalArgumentException invalid) {
            result.message = invalid.getMessage();
            return result;
        }
        result.server = address.server();
        result.reference = address.reference();
        IInfobaseManager manager = ServiceAccess.get(IInfobaseManager.class);
        if (manager == null || !manager.isPersistenceSupported()) {
            result.message = "EDT's persistent infobase manager is unavailable";
            return result;
        }
        synchronized (REGISTRATION_LOCK) {
            for (InfobaseReference existing : InfobaseReferences.asPlainList(manager.getAll())) {
                boolean sameAddress = existing.getConnectionString() instanceof ServerConnectionString server
                        && address.server().equalsIgnoreCase(server.getServer())
                        && address.reference().equalsIgnoreCase(server.getReference());
                if (result.name.equalsIgnoreCase(existing.getName()) || sameAddress) {
                    result.message = "an infobase with this name or server address is already registered";
                    return result;
                }
            }
            result.ok = true;
            result.plan = "Register existing server infobase " + result.name + " in EDT [" + result.version + "]";
            if (!apply) {
                result.message = "dry-run: the infobase list is unchanged; no connection to the server was made";
                return result;
            }
            InfobaseReference created = InfobaseReferences.newServerInfobaseReference(address.server(), address.reference());
            created.setName(result.name);
            created.setUuid(UUID.randomUUID());
            created.setVersion(result.version);
            created.setShowInList(true);
            if (user != null && !user.isBlank()) created.setAdditionalParameters("/N\"" + user.trim() + "\"");
            try {
                manager.add(created, null);
                InfobaseReference actual = manager.findInfobaseByUuid(created.getUuid()).orElse(null);
                result.verified = actual != null && result.name.equals(actual.getName())
                        && actual.getConnectionString() instanceof ServerConnectionString server
                        && address.server().equals(server.getServer()) && address.reference().equals(server.getReference())
                        && result.version.equals(actual.getVersion());
                if (!result.verified) throw new IllegalStateException("registration readback did not match the request");
                result.uuid = actual.getUuid().toString();
                result.applied = true;
                result.message = "registered in EDT's live infobase list and verified by UUID; the server database was not changed";
            } catch (Exception failure) {
                result.ok = false;
                result.message = "registration failed: " + GatewaySupport.describeCause(failure);
                try {
                    var own = manager.findInfobaseByUuid(created.getUuid());
                    if (own.isPresent()) manager.delete(own.get());
                } catch (Exception rollback) {
                    result.message += "; rollback failed: " + GatewaySupport.describeCause(rollback);
                }
            }
        }
        return result;
    }
}

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

/**
 * What stopping a configurator agent is worth spending on being polite about.
 *
 * <p>A stop asks the agent to shut itself down before killing it, and that politeness is not for its
 * own sake: a killed agent leaves its Designer session in the cluster, where it holds the infobase's
 * configuration lock. But the request needs a live SSH session, and opening one costs the reconnect
 * loop - fifteen attempts, a second apart - which an agent whose session is already dead spends in
 * full and gains nothing by.
 *
 * <p>Nothing, because in that state there is nothing to protect: an agent that never got its infobase
 * connection opened no session in the cluster, so killing it leaves no orphan. The bill for finding
 * that out the expensive way was a minute and a half per stop, every second of it the loop and the
 * wait that follows it.
 */
public final class AgentStop {

    /**
     * Connect attempts a stop may spend on OPENING a session for the shutdown request.
     *
     * <p>Two, not the fifteen a first connection is given: those fifteen are there for an agent that
     * has just been started and is still coming up, where every retry is a second well spent. A stop
     * is the opposite case - the agent has lived, its session is gone, and the reason it is gone is
     * almost never one more second of patience.
     *
     * <p>Two rather than one because of how the connect works: an agent that already holds the
     * infobase refuses a second {@code connect-ib}, and the loop reads that refusal for what it is and
     * retries WITHOUT asking again. One attempt would end on the refusal it was meant to learn from.
     */
    public static final int REOPEN_ATTEMPTS = 2;

    private AgentStop() {
    }

    /**
     * How many connect attempts the polite shutdown of this agent deserves.
     *
     * @param sessionOpen       the agent's SSH session is still in hand - the request costs nothing
     * @param infobaseConnected the agent's process holds the infobase, so it may own a cluster session
     *                          that only a polite exit takes with it
     * @return 0 to skip the polite round entirely, otherwise the attempts it may make
     */
    public static int connectAttempts(boolean sessionOpen, boolean infobaseConnected) {
        if (sessionOpen) {
            return 1;
        }
        return infobaseConnected ? REOPEN_ATTEMPTS : 0;
    }
}

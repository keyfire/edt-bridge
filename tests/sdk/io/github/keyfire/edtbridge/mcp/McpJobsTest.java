/*
 * Copyright 2026 edt-bridge contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.keyfire.edtbridge.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.keyfire.edtbridge.core.JobTools;
import io.github.keyfire.edtbridge.core.Jobs;

/** Long calls as jobs on the server: the async argument, edt_job_status and edt_cancel_job. */
class McpJobsTest {

    @AfterEach
    void clear() {
        System.clearProperty("edt.bridge.token");
        System.clearProperty("edt.bridge.pii");
    }

    private static Object invoke(String method, Object... args) throws Exception {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = args[i].getClass();
        }
        Method m = McpServer.class.getDeclaredMethod(method, types);
        m.setAccessible(true);
        return m.invoke(McpServer.getInstance(), args);
    }

    private static JsonObject call(String tool, JsonObject arguments) throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("name", tool);
        params.add("arguments", arguments);
        return (JsonObject) invoke("toolsCall", params);
    }

    private static String text(JsonObject result) {
        return result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
    }

    private static JsonObject body(JsonObject result) {
        return JsonParser.parseString(text(result)).getAsJsonObject();
    }

    @SuppressWarnings("unchecked")
    private static Jobs<JsonObject> jobs() throws Exception {
        Field field = McpServer.class.getDeclaredField("jobs");
        field.setAccessible(true);
        return (Jobs<JsonObject>) field.get(McpServer.getInstance());
    }

    private static JsonObject args(String... pairs) {
        JsonObject o = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            o.addProperty(pairs[i], pairs[i + 1]);
        }
        return o;
    }

    @Test
    void theLongToolsTakeAsyncAndTheOthersDoNot() throws Exception {
        JsonObject list = (JsonObject) invoke("toolsList");
        int long_ = 0;
        boolean status = false;
        boolean cancel = false;
        for (JsonElement element : list.getAsJsonArray("tools")) {
            JsonObject tool = element.getAsJsonObject();
            String name = tool.get("name").getAsString();
            boolean async = tool.getAsJsonObject("inputSchema").getAsJsonObject("properties").has(JobTools.ASYNC);
            assertEquals(JobTools.runsAsJob(name), async, name);
            long_ += async ? 1 : 0;
            status |= name.equals(JobTools.STATUS);
            cancel |= name.equals(JobTools.CANCEL);
        }
        assertEquals(JobTools.longTools().size(), long_, "a long tool the server does not register");
        assertTrue(status && cancel);
    }

    @Test
    void aCallRefusedAtOnceAnswersAsItselfWithoutAJob() throws Exception {
        JsonObject arguments = new JsonObject();
        arguments.addProperty(JobTools.ASYNC, true);
        JsonObject result = call("edt_clean_project", arguments);
        // Refused by the token gate or for the missing project, whichever comes first: either way at once.
        assertTrue(result.has("isError"), text(result));
        assertFalse(text(result).contains("jobId"), text(result));
        // Its job does not stay among the kept results: the caller never saw its id.
        for (var kept : jobs().list()) {
            assertFalse("edt_clean_project".equals(kept.tool()), kept.id());
        }
    }

    @Test
    void aRunningJobAnswersItsStateAndAFinishedOneItsResultUnderTheFilterOfItsTool() throws Exception {
        System.setProperty("edt.bridge.pii", "redact");
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var job = jobs().submit("edt_evaluate", () -> {
            Jobs.stage("evaluating");
            started.countDown();
            release.await(30, TimeUnit.SECONDS);
            return McpServer.textResult("{\"value\": \"ivanov@example.com\"}");
        });
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS));
            JsonObject running = body(call(JobTools.STATUS, args("jobId", job.id(), "waitSeconds", "0")));
            assertEquals("running", running.get("status").getAsString());
            assertEquals("evaluating", running.get("stage").getAsString());
            assertTrue(running.has("next"));
        } finally {
            release.countDown();
        }
        JsonObject done = body(call(JobTools.STATUS, args("jobId", job.id(), "waitSeconds", "10")));
        assertEquals("succeeded", done.get("status").getAsString());
        assertEquals("[email]", done.getAsJsonObject("result").get("value").getAsString());

        // Without an id: the jobs of this run, the newest first.
        JsonObject listing = body(call(JobTools.STATUS, new JsonObject()));
        assertEquals(jobs().run(), listing.get("run").getAsString());
        assertEquals(job.id(), listing.getAsJsonArray("jobs").get(0).getAsJsonObject().get("jobId").getAsString());
    }

    @Test
    void aFailedJobIsAnErrorResult() throws Exception {
        var job = jobs().submit("edt_build_extension", () -> McpServer.toolError("ibcmd config save exit 1"));
        JsonObject result = call(JobTools.STATUS, args("jobId", job.id(), "waitSeconds", "10"));
        assertTrue(result.has("isError"));
        JsonObject failed = body(result);
        assertEquals("failed", failed.get("status").getAsString());
        assertEquals("ibcmd config save exit 1", failed.get("result").getAsString());
    }

    @Test
    void cancellingTakesTheTokenAndListsWhatStaysApplied() throws Exception {
        var loaded = new CountDownLatch(1);
        long deadline = System.currentTimeMillis() + 30_000;
        var job = jobs().submit("edt_update_infobase", () -> {
            Jobs.applied("the configuration is loaded into the infobase");
            loaded.countDown();
            while (!Jobs.cancelRequested() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            return McpServer.toolError("stopped before the database update");
        });
        assertTrue(loaded.await(10, TimeUnit.SECONDS));

        // A machine whose environment configures a token has no tokenless server to ask.
        if (invoke("token") == null) {
            JsonObject refused = call(JobTools.CANCEL, args("jobId", job.id()));
            assertTrue(refused.has("isError"));
            assertTrue(text(refused).contains("requires a configured token"), text(refused));
            System.setProperty("edt.bridge.token", "sample-token");
        }
        JsonObject cancelled = body(call(JobTools.CANCEL, args("jobId", job.id())));
        assertEquals("cancelled", cancelled.get("status").getAsString());
        assertEquals("the configuration is loaded into the infobase",
                cancelled.getAsJsonArray("applied").get(0).getAsString());
        assertTrue(cancelled.get("message").getAsString().contains("stay done"));
    }

    @Test
    void aWaitThatIsNoNumberIsRefusedAndOnlyOneStatusCallWaits() throws Exception {
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var job = jobs().submit("edt_infobase_dump", () -> {
            started.countDown();
            release.await(30, TimeUnit.SECONDS);
            return McpServer.textResult("{}");
        });
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS));
            JsonObject bad = call(JobTools.STATUS, args("jobId", job.id(), "waitSeconds", "soon"));
            assertTrue(text(bad).contains("waitSeconds must be a whole number"), text(bad));

            Thread first = new Thread(() -> {
                try {
                    call(JobTools.STATUS, args("jobId", job.id(), "waitSeconds", "20"));
                } catch (Exception ignored) {
                    // the first waiter only has to hold the wait
                }
            });
            first.start();
            Thread.sleep(500);
            long before = System.nanoTime();
            JsonObject second = body(call(JobTools.STATUS, args("jobId", job.id(), "waitSeconds", "20")));
            assertTrue(System.nanoTime() - before < TimeUnit.SECONDS.toNanos(5));
            assertTrue(second.get("message").getAsString().contains("already waiting"), second.toString());
            release.countDown();
            first.join(10_000);
        } finally {
            release.countDown();
        }
    }

    @Test
    void anIdOfAnotherRunAnUnknownOneAndANonIdAreRefusedWithTheirReason() throws Exception {
        String run = jobs().run();
        String other = run.equals("aaaaaa") ? "bbbbbb" : "aaaaaa";

        JsonObject earlier = call(JobTools.STATUS, args("jobId", "job-" + other + "-1"));
        assertTrue(earlier.has("isError"));
        assertTrue(text(earlier).contains("earlier run of EDT"), text(earlier));

        JsonObject unknown = call(JobTools.STATUS, args("jobId", "job-" + run + "-999999"));
        assertTrue(text(unknown).contains("no job"), text(unknown));

        JsonObject malformed = call(JobTools.CANCEL, args("jobId", "42"));
        assertTrue(text(malformed).contains("is not a job id"), text(malformed));
    }
}

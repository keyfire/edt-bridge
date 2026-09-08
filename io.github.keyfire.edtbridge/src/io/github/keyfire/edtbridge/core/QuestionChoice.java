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

import java.util.List;

/**
 * Answering a question the platform asks in the middle of an operation.
 *
 * <p>The platform stops and asks - "the database is locked: Cancel / Retry", and for a change that
 * touches no table structure it offers a third way, updating dynamically while sessions keep
 * running. Nobody answered, so the operation died with "question delegate has not been specified":
 * dynamic update was unreachable and every code-only change needed a maintenance window.
 *
 * <p>What must NOT happen is the bridge picking for the caller. One answer ends other people's
 * sessions, another does not; a default here would be a decision taken by whoever wrote this file
 * for someone who never saw the question. So the rule is: an unanswered question is REPORTED with
 * its options, and the caller re-calls naming the one it wants.
 *
 * <p>The choice travels as the answer's own value, not as its label: the label is the platform's
 * localized prose ("Повторить", "Retry") and moves with language and version, while the value is
 * what the protocol carries. A label still matches when given, for the caller reading a report by
 * eye - but it is the value that a script should pass.
 */
public final class QuestionChoice {

    private QuestionChoice() {
    }

    /**
     * Whether this offered answer is the one the caller asked for. Empty request matches nothing -
     * that is what makes "report and stop" the default rather than a guess.
     */
    public static boolean matches(String value, String label, String requested) {
        if (requested == null || requested.isBlank()) {
            return false;
        }
        String want = requested.trim();
        return want.equalsIgnoreCase(value == null ? "" : value.trim())
                || want.equalsIgnoreCase(label == null ? "" : label.trim());
    }

    /**
     * The question as the caller has to see it to answer: the platform's own text, then each option
     * with the value to pass back. Without the values a reader can only guess what to send.
     */
    public static String report(String message, List<String> options) {
        String asked = (message == null || message.isBlank()) ? "(no text)" : message.trim();
        String choices = (options == null || options.isEmpty())
                ? "(no options offered)"
                : String.join("; ", options);
        return "the platform asked: \"" + asked + "\". Options: " + choices
                + ". Nothing was answered and the operation stopped - re-call with answer=<value> to "
                + "pick one. Read them before choosing: some end other users' sessions.";
    }

    /** One option as the report shows it, e.g. {@code 2 = "Повторить" (default)}. */
    public static String option(String value, String label, boolean isDefault) {
        return (value == null ? "?" : value.trim()) + " = \"" + (label == null ? "" : label.trim())
                + "\"" + (isDefault ? " (default)" : "");
    }

    /**
     * The platform asked, the answer was given, and the operation failed anyway. Reported apart
     * from {@link #notOffered} because the two look identical from the outside and mean opposite
     * things: there the choice never reached the platform, here it did and did not help. Saying
     * "not among the offered" for this case sends the reader hunting for a typo in an answer that
     * was accepted.
     */
    public static String answeredAndFailed(String answer, String failure) {
        return "answered \"" + (answer == null ? "" : answer.trim()) + "\" and the operation still "
                + "failed: " + failure + ". On a lively infobase \"Повторить\" cannot win - background "
                + "jobs reconnect faster than the update takes the lock; raise a maintenance window "
                + "with edt_infobase_maintenance (it denies scheduled jobs and lets them drain) "
                + "instead of retrying or ending sessions.";
    }

    /** Why an answer the caller named could not be used: it was not among the offered ones. */
    public static String notOffered(String requested, List<String> options) {
        return "answer \"" + (requested == null ? "" : requested.trim()) + "\" is not among the ones "
                + "the platform offered here: " + String.join("; ", options)
                + ". The question is asked per situation, so an answer from an earlier call does not "
                + "carry over.";
    }
}

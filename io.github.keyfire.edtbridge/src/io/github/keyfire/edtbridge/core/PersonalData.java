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

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Personal data in the answers that carry infobase data (EDT-PII-01): the values of variables and
 * expressions of a live infobase, the users and computers of its sessions.
 *
 * <p>With {@code EDT_BRIDGE_PII} set, such an answer is filtered before it leaves: personal data found
 * by its shape (e-mail, phone, SNILS and INN with their check digits, a card number by Luhn, a passport)
 * and every value of a field named like personal data (ФИО, Фамилия...) is replaced with its kind,
 * {@code [inn]}, or with a pseudonym, {@code [inn#icbbphoh]}: an HMAC of the value under a salt, the
 * same for the same value, with no table that leads back. Module texts, schemas and templates are never
 * filtered: a model writes them back.
 *
 * <p>The wrapper applies the same rules in Python (edt_bridge_mcp/pii.py); tests/pii/cases.tsv holds
 * the cases both must answer alike. Plain Java: the core compiles without EDT and without a JSON library.
 */
public final class PersonalData {

    public static final String MODE_ENV = "EDT_BRIDGE_PII";
    public static final String SALT_ENV = "EDT_BRIDGE_PII_SALT";
    public static final String RULES_ENV = "EDT_BRIDGE_PII_RULES";

    /** Whether and how the answers are filtered. */
    public enum Mode { OFF, REDACT, PSEUDONYM }

    /** One rule: the kind of data and its pattern (searched in a value, or matched by a whole field name). */
    public record Rule(String kind, Pattern pattern) {
    }

    /** The letters a pseudonym is written in: no digit, so no rule finds a number inside one. */
    private static final String LETTERS = "abcdefghijklmnop";
    private static final Set<String> NUMERIC = Set.of("card", "snils", "passport", "inn", "phone");

    /** Personal data by its shape, applied in this order; a replacement holds no digit for the next rule. */
    private static final List<Rule> VALUES = List.of(
            new Rule("email", Pattern.compile("(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+"
                    + "(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}(?![A-Za-z0-9-])")),
            new Rule("card", Pattern.compile("(?<![0-9])[0-9](?:[ -]?[0-9]){12,18}(?![0-9])")),
            new Rule("snils", Pattern.compile("(?<![0-9])[0-9]{3}-?[0-9]{3}-?[0-9]{3}[ -]?[0-9]{2}(?![0-9])")),
            new Rule("passport", Pattern.compile("(?<![0-9])[0-9]{2} ?[0-9]{2} (?:№ ?)?[0-9]{6}(?![0-9])")),
            new Rule("inn", Pattern.compile("(?<![0-9.,])(?:[0-9]{12}|[0-9]{10})(?![0-9]|[.,][0-9])")),
            new Rule("phone", Pattern.compile(
                    "(?<![0-9+])(?:\\+7|8)[ (-]*[0-9]{3}[ )-]*[0-9]{3}[ -]*[0-9]{2}[ -]*[0-9]{2}(?![0-9])")));

    /** Fields whose every value is personal data, by the last segment of their name. */
    private static final List<Rule> NAMES = List.of(
            name("person", "ФИО.*|Фамилия.*|Имя|Отчество.*|FullName.*|LastName.*|FirstName.*|MiddleName.*|Surname.*"),
            name("birthdate", "ДатаРождения.*|BirthDate.*|DateOfBirth.*"),
            name("passport", "Паспорт.*|Passport.*"),
            name("phone", "Телефон.*|Phone.*"),
            name("address", "Адрес.*|Address.*"),
            name("email", "ЭлектроннаяПочта.*|ЭлПочта.*|E-?mail.*"));

    /** Keys whose values are paths, statuses, code and names, never data. */
    public static final Set<String> TECHNICAL = Set.of(
            "screenshot", "screenshotError", "junit", "allure", "testInfobase", "epf", "erf", "navigationLink",
            "form", "status", "stage", "phase", "scope", "scopeSource", "action", "kind", "formName", "module",
            "extension", "context", "name", "field", "button", "button_name", "file", "path", "key", "source",
            "event", "application", "severity", "time", "type", "framework", "piiFilter", "expression",
            "signature", "session", "infobase", "appId", "startedAt");
    /** Keys whose values belong to the field a sibling {@code field}, {@code name} or {@code expression} names. */
    public static final Set<String> VALUE_KEYS = Set.of("value", "presentation", "text", "actual", "expected", "equals");
    /** The sibling keys that name the field of a value, in the order they are asked. */
    public static final List<String> OWNER_KEYS = List.of("field", "name", "expression");
    /** Keys that are personal data by themselves: the user and the computer of a session or an event. */
    public static final Map<String, String> WHOLE = Map.of(
            "user", "user", "userName", "user", "computer", "computer", "host", "computer");

    private final Mode mode;
    private final byte[] salt;
    private final List<Rule> values;
    private final List<Rule> names;
    private int count;

    public PersonalData(Mode mode, String salt, List<Rule> extraValues, List<Rule> extraNames) {
        this.mode = mode;
        String key = salt == null || salt.isBlank() ? randomSalt() : salt;
        this.salt = key.getBytes(StandardCharsets.UTF_8);
        this.values = new ArrayList<>(VALUES);
        this.values.addAll(extraValues);
        this.names = new ArrayList<>(NAMES);
        this.names.addAll(extraNames);
    }

    /** The mode a setting names; empty is off. */
    public static Mode mode(String text) {
        String value = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        switch (value) {
            case "", "off":
                return Mode.OFF;
            case "redact":
                return Mode.REDACT;
            case "pseudonym":
                return Mode.PSEUDONYM;
            default:
                throw new IllegalArgumentException(MODE_ENV + " must be one of off, redact, pseudonym, not '" + text + "'");
        }
    }

    /** A rule for a field name, matched whole and without regard to case. */
    public static Rule name(String kind, String pattern) {
        return new Rule(kind, Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
    }

    public boolean active() {
        return mode != Mode.OFF;
    }

    public Mode modeValue() {
        return mode;
    }

    /** How many values this filter replaced. */
    public int count() {
        return count;
    }

    /** A value with the personal data found by its shape replaced. */
    public String text(String value) {
        if (!active() || value == null || value.isEmpty()) {
            return value;
        }
        String result = value;
        for (Rule rule : values) {
            Matcher matcher = rule.pattern().matcher(result);
            StringBuilder out = new StringBuilder();
            while (matcher.find()) {
                String found = matcher.group();
                String replacement = valid(rule.kind(), digits(found)) ? token(rule.kind(), found) : found;
                matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(out);
            result = out.toString();
        }
        return result;
    }

    /** The kind of personal data a field of this name holds, or null. */
    public String kindOf(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String trimmed = name.strip();
        String last = trimmed.substring(trimmed.lastIndexOf('.') + 1);
        for (Rule rule : names) {
            if (rule.pattern().matcher(last).matches()) {
                return rule.kind();
            }
        }
        return null;
    }

    /** A value of the field {@code name}: whole when the name says it is personal data, else by its shape. */
    public String named(String name, String value) {
        if (!active() || value == null || value.isEmpty()) {
            return value;
        }
        String kind = kindOf(name);
        return kind != null ? token(kind, value) : text(value);
    }

    /** The replacement of one value of the given kind. */
    public String token(String kind, String value) {
        count++;
        if (mode == Mode.REDACT) {
            return "[" + kind + "]";
        }
        String normal;
        if (NUMERIC.contains(kind)) {
            normal = digitsText(value);
            if ("phone".equals(kind) && normal.length() == 11 && normal.charAt(0) == '8') {
                normal = "7" + normal.substring(1);
            }
        } else {
            normal = String.join(" ", value.toLowerCase(Locale.ROOT).strip().split("(?U)\\s+"));
        }
        String hex = HexFormat.of().formatHex(hmac(kind + ":" + normal)).substring(0, 8);
        StringBuilder letters = new StringBuilder();
        for (char c : hex.toCharArray()) {
            letters.append(LETTERS.charAt(Character.digit(c, 16)));
        }
        return "[" + kind + "#" + letters + "]";
    }

    private byte[] hmac(String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt, "HmacSHA256"));
            return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    private static String randomSalt() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String digitsText(String text) {
        StringBuilder out = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c >= '0' && c <= '9') {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static int[] digits(String text) {
        return digitsText(text).chars().map(c -> c - '0').toArray();
    }

    private static boolean valid(String kind, int[] d) {
        switch (kind) {
            case "card":
                return d.length >= 13 && d.length <= 19 && luhn(d);
            case "snils":
                return snils(d);
            case "inn":
                return inn(d) && !dateLike(d);
            default:
                return true;
        }
    }

    private static int check(int[] weights, int[] d) {
        int sum = 0;
        for (int i = 0; i < weights.length; i++) {
            sum += weights[i] * d[i];
        }
        return sum % 11 % 10;
    }

    static boolean inn(int[] d) {
        if (d.length == 10) {
            return check(new int[] {2, 4, 10, 3, 5, 9, 4, 6, 8}, d) == d[9];
        }
        return d.length == 12
                && check(new int[] {7, 2, 4, 10, 3, 5, 9, 4, 6, 8}, d) == d[10]
                && check(new int[] {3, 7, 2, 4, 10, 3, 5, 9, 4, 6, 8}, d) == d[11];
    }

    /** A stamp YYYYMMDDhh or YYYYMMDDhhmm: a date that looks like an INN is not one. */
    static boolean dateLike(int[] d) {
        int year = d[0] * 1000 + d[1] * 100 + d[2] * 10 + d[3];
        int month = d[4] * 10 + d[5];
        int day = d[6] * 10 + d[7];
        int hour = d[8] * 10 + d[9];
        boolean valid = year >= 1900 && year <= 2099 && month >= 1 && month <= 12 && day >= 1 && day <= 31 && hour <= 23;
        return valid && (d.length == 10 || d[10] * 10 + d[11] <= 59);
    }

    static boolean snils(int[] d) {
        int total = 0;
        for (int i = 0; i < 9; i++) {
            total += d[i] * (9 - i);
        }
        int check = total < 100 ? total : (total == 100 || total == 101) ? 0 : total % 101;
        return (check == 100 ? 0 : check) == d[9] * 10 + d[10];
    }

    static boolean luhn(int[] d) {
        int total = 0;
        for (int i = 0; i < d.length; i++) {
            int value = d[d.length - 1 - i];
            if (i % 2 == 1) {
                value *= 2;
                if (value > 9) {
                    value -= 9;
                }
            }
            total += value;
        }
        return total % 10 == 0;
    }
}

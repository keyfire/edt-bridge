/*
 * Copyright 2026 edt-bridge contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.keyfire.edtbridge.core;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Exact whole-source UTF-8 replacement without whitespace or HTML normalization. */
public final class ExactTextReplacement {
    private ExactTextReplacement() {}

    public record Plan(String oldText, String newText, String sourceHash, String resultHash, boolean unchanged) {}

    public static Plan plan(String source, String replacement, String expectedHash) {
        String sourceHash = hash(source);
        if (replacement == null) throw new IllegalArgumentException("text is required; an empty string is allowed");
        String resultHash = hash(replacement);
        if (expectedHash != null && !sourceHash.equalsIgnoreCase(expectedHash)) {
            throw new IllegalArgumentException("source hash mismatch; read the template and repeat the dry-run");
        }
        return new Plan(source, replacement, sourceHash, resultHash, source.equals(replacement));
    }

    public static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(text)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static byte[] encode(String text) {
        if (text == null || !StandardCharsets.UTF_8.newEncoder().canEncode(text)) {
            throw new IllegalArgumentException("text must contain valid Unicode");
        }
        return text.getBytes(StandardCharsets.UTF_8);
    }

    public static String decode(byte[] bytes) {
        if (bytes == null) throw new IllegalArgumentException("source bytes are required");
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("template source is not valid UTF-8", exception);
        }
    }

    public static void requireUtf8(String charset) {
        try {
            if (charset != null && Charset.forName(charset).equals(StandardCharsets.UTF_8)) return;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unsupported template encoding: " + charset, exception);
        }
        throw new IllegalArgumentException("template encoding must be UTF-8: " + charset);
    }
}

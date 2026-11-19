package com.jinloes.prpilot.sidecar;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class StdioFrameCodec {
    static final int MAX_HEADER_BYTES = 8 * 1024;
    static final int MAX_MESSAGE_BYTES = 8 * 1024 * 1024;

    static final class FrameTooLargeException extends IOException {
        private final int payloadLength;

        FrameTooLargeException(int payloadLength) {
            super("JSON-RPC payload exceeds the maximum size");
            this.payloadLength = payloadLength;
        }

        int payloadLength() {
            return payloadLength;
        }
    }

    byte[] readFrame(InputStream input) throws IOException {
        byte[] header = readHeader(input);
        if (header == null) {
            return null;
        }

        int contentLength = parseContentLength(header);
        byte[] payload = input.readNBytes(contentLength);
        if (payload.length != contentLength) {
            throw new IOException("Unexpected end of input while reading JSON-RPC payload");
        }
        return payload;
    }

    void writeFrame(OutputStream output, byte[] payload) throws IOException {
        if (payload.length > MAX_MESSAGE_BYTES) {
            throw new FrameTooLargeException(payload.length);
        }

        String header = "Content-Length: " + payload.length + "\r\n\r\n";
        output.write(header.getBytes(StandardCharsets.US_ASCII));
        output.write(payload);
        output.flush();
    }

    private byte[] readHeader(InputStream input) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int matchedTerminatorBytes = 0;

        while (header.size() < MAX_HEADER_BYTES) {
            int next = input.read();
            if (next == -1) {
                if (header.size() == 0) {
                    return null;
                }
                throw new IOException("Unexpected end of input while reading JSON-RPC header");
            }

            header.write(next);
            matchedTerminatorBytes = updateTerminatorMatch(matchedTerminatorBytes, next);
            if (matchedTerminatorBytes == 4) {
                byte[] bytes = header.toByteArray();
                byte[] result = new byte[bytes.length - 4];
                System.arraycopy(bytes, 0, result, 0, result.length);
                return result;
            }
        }

        throw new IOException("JSON-RPC header exceeds the maximum size");
    }

    private int updateTerminatorMatch(int previousMatch, int next) {
        byte[] terminator = {'\r', '\n', '\r', '\n'};
        if (next == terminator[previousMatch]) {
            return previousMatch + 1;
        }
        return next == terminator[0] ? 1 : 0;
    }

    private int parseContentLength(byte[] headerBytes) throws IOException {
        String header = new String(headerBytes, StandardCharsets.US_ASCII);
        Integer contentLength = null;

        for (String line : header.split("\\r\\n")) {
            int separator = line.indexOf(':');
            if (separator <= 0) {
                throw new IOException("Invalid JSON-RPC header line");
            }

            String name = line.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            if (!"content-length".equals(name)) {
                continue;
            }
            if (contentLength != null) {
                throw new IOException("JSON-RPC header has multiple Content-Length values");
            }

            String value = line.substring(separator + 1).trim();
            try {
                contentLength = Integer.parseInt(value);
            } catch (NumberFormatException exception) {
                throw new IOException("Invalid JSON-RPC Content-Length", exception);
            }
        }

        if (contentLength == null) {
            throw new IOException("JSON-RPC header is missing Content-Length");
        }
        if (contentLength < 0 || contentLength > MAX_MESSAGE_BYTES) {
            throw new IOException("JSON-RPC Content-Length is outside the allowed range");
        }
        return contentLength;
    }
}

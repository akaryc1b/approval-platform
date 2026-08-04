package io.github.akaryc1b.approval.ai.openai;

import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesNetworkSupport.Deadline;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesNetworkSupport.ExchangeResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Strict one-attempt HTTP/1.1 request and bounded response framing for P6-D. */
final class OpenAiResponsesHttpCodec {

    private static final String CONTENT_ENCODING_HEADER = "Content-Encoding";
    private static final int MAXIMUM_HEADER_LINE_BYTES = 8_192;
    private static final int MAXIMUM_HEADER_BYTES = 32_768;
    private static final int MAXIMUM_HEADER_COUNT = 64;

    private OpenAiResponsesHttpCodec() {
    }

    static void requireApiKey(byte[] secret) {
        Objects.requireNonNull(secret, "secret must not be null");
        if (secret.length == 0 || secret.length > 4_096) {
            throw failure(OpenAiResponsesTransportException.Failure.SECRET_UNAVAILABLE);
        }
        for (byte value : secret) {
            int character = Byte.toUnsignedInt(value);
            if (character < 0x21 || character > 0x7e) {
                throw failure(OpenAiResponsesTransportException.Failure.SECRET_UNAVAILABLE);
            }
        }
    }

    static void writeRequest(
        OutputStream output,
        OpenAiResponsesEndpointPolicy endpoint,
        OpenAiResponsesTransportPort.Request request,
        byte[] secret,
        String clientRequestId
    ) throws IOException {
        requireApiKey(secret);
        writeAscii(output, "POST " + endpoint.path() + " HTTP/1.1\r\n");
        writeAscii(output, "Host: " + endpoint.host() + "\r\n");
        writeAscii(output, "Authorization: Bearer ");
        output.write(secret);
        writeAscii(output, "\r\n");
        writeAscii(output, "Content-Type: application/json\r\n");
        writeAscii(output, "Accept: application/json\r\n");
        writeAscii(output, "Accept-Encoding: identity\r\n");
        writeAscii(output, "X-Client-Request-Id: " + clientRequestId + "\r\n");
        writeAscii(output, "Content-Length: " + request.bodyLength() + "\r\n");
        writeAscii(output, "Connection: close\r\n\r\n");
        output.write(request.bodyCopy());
        output.flush();
    }

    static ExchangeResult readResponse(
        InputStream input,
        OpenAiResponsesTransportPort.Request request,
        String clientRequestId,
        Deadline deadline
    ) throws IOException {
        String statusLine = readLine(input, request, deadline, 512);
        String[] statusParts = statusLine.split(" ", 3);
        if (statusParts.length < 2
            || !("HTTP/1.1".equals(statusParts[0])
                || "HTTP/1.0".equals(statusParts[0]))
            || !statusParts[1].matches("[1-5][0-9]{2}")) {
            throw failure(OpenAiResponsesTransportException.Failure.HTTP_PROTOCOL_INVALID);
        }
        int status = Integer.parseInt(statusParts[1]);
        if (status >= 300 && status <= 399) {
            throw failure(OpenAiResponsesTransportException.Failure.REDIRECT_REJECTED);
        }

        Map<String, String> headers = new HashMap<>();
        int headerBytes = statusLine.length() + 2;
        for (int count = 0; count < MAXIMUM_HEADER_COUNT; count++) {
            String line = readLine(
                input,
                request,
                deadline,
                MAXIMUM_HEADER_LINE_BYTES
            );
            headerBytes += line.length() + 2;
            if (headerBytes > MAXIMUM_HEADER_BYTES) {
                throw failure(
                    OpenAiResponsesTransportException.Failure.HTTP_PROTOCOL_INVALID
                );
            }
            if (line.isEmpty()) {
                byte[] body = readBody(input, headers, request, deadline);
                return new ExchangeResult(
                    status,
                    headers.get("x-request-id"),
                    body,
                    OpenAiResponsesProtocol.sha256Utf8(clientRequestId)
                );
            }
            if (line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                throw failure(
                    OpenAiResponsesTransportException.Failure.HTTP_PROTOCOL_INVALID
                );
            }
            int colon = line.indexOf(':');
            if (colon < 1) {
                throw failure(
                    OpenAiResponsesTransportException.Failure.HTTP_PROTOCOL_INVALID
                );
            }
            String rawName = line.substring(0, colon);
            String rawValue = line.substring(colon + 1);
            if (!validHeaderName(rawName)
                || !validHeaderValue(rawValue)
                || rawValue.length() > MAXIMUM_HEADER_LINE_BYTES) {
                throw failure(
                    OpenAiResponsesTransportException.Failure.HTTP_PROTOCOL_INVALID
                );
            }
            String name = rawName.toLowerCase(Locale.ROOT);
            String value = rawValue.trim();
            if (headers.putIfAbsent(name, value) != null) {
                throw failure(
                    OpenAiResponsesTransportException.Failure.HTTP_PROTOCOL_INVALID
                );
            }
        }
        throw failure(OpenAiResponsesTransportException.Failure.HTTP_PROTOCOL_INVALID);
    }

    private static byte[] readBody(
        InputStream input,
        Map<String, String> headers,
        OpenAiResponsesTransportPort.Request request,
        Deadline deadline
    ) throws IOException {
        String encoding = headers.get(
            CONTENT_ENCODING_HEADER.toLowerCase(Locale.ROOT)
        );
        if (encoding != null && !"identity".equalsIgnoreCase(encoding)) {
            throw failure(OpenAiResponsesTransportException.Failure.HTTP_PROTOCOL_INVALID);
        }
        String transferEncoding = headers.get("transfer-encoding");
        String contentLength = headers.get("content-length");
        if (transferEncoding != null && contentLength != null) {
            throw failure(OpenAiResponsesTransportException.Failure.HTTP_PROTOCOL_INVALID);
        }
        if (transferEncoding != null) {
            if (!"chunked".equalsIgnoreCase(transferEncoding)) {
                throw failure(
                    OpenAiResponsesTransportException.Failure.HTTP_PROTOCOL_INVALID
                );
            }
            return readChunked(input, request, deadline);
        }
        if (contentLength == null || !contentLength.matches("[0-9]{1,10}")) {
            throw failure(OpenAiResponsesTransportException.Failure.HTTP_PROTOCOL_INVALID);
        }
        long length = Long.parseLong(contentLength);
        if (length > OpenAiResponsesProtocol.MAXIMUM_TRANSPORT_RESPONSE_BYTES) {
            throw failure(OpenAiResponsesTransportException.Failure.RESPONSE_TOO_LARGE);
        }
        byte[] body = new byte[(int) length];
        readExact(input, body, request, deadline);
        return body;
    }

    private static byte[] readChunked(
        InputStream input,
        OpenAiResponsesTransportPort.Request request,
        Deadline deadline
    ) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String line = readLine(input, request, deadline, 128);
            String sizeText = line.split(";", 2)[0].trim();
            if (!sizeText.matches("[0-9A-Fa-f]{1,8}")) {
                throw failure(
                    OpenAiResponsesTransportException.Failure.HTTP_PROTOCOL_INVALID
                );
            }
            long sizeValue = Long.parseLong(sizeText, 16);
            if (sizeValue == 0) {
                String trailer = readLine(input, request, deadline, 2);
                if (!trailer.isEmpty()) {
                    throw failure(
                        OpenAiResponsesTransportException.Failure.HTTP_PROTOCOL_INVALID
                    );
                }
                return body.toByteArray();
            }
            if (sizeValue > OpenAiResponsesProtocol.MAXIMUM_TRANSPORT_RESPONSE_BYTES
                || (long) body.size() + sizeValue
                    > OpenAiResponsesProtocol.MAXIMUM_TRANSPORT_RESPONSE_BYTES) {
                throw failure(OpenAiResponsesTransportException.Failure.RESPONSE_TOO_LARGE);
            }
            byte[] chunk = new byte[(int) sizeValue];
            readExact(input, chunk, request, deadline);
            body.write(chunk);
            String terminator = readLine(input, request, deadline, 2);
            if (!terminator.isEmpty()) {
                throw failure(
                    OpenAiResponsesTransportException.Failure.HTTP_PROTOCOL_INVALID
                );
            }
        }
    }

    private static void readExact(
        InputStream input,
        byte[] target,
        OpenAiResponsesTransportPort.Request request,
        Deadline deadline
    ) throws IOException {
        int offset = 0;
        while (offset < target.length) {
            OpenAiResponsesNetworkSupport.requireNotCancelled(request);
            deadline.requireRemaining();
            int count = input.read(target, offset, target.length - offset);
            if (count < 0) {
                throw failure(
                    OpenAiResponsesTransportException.Failure.HTTP_PROTOCOL_INVALID
                );
            }
            offset += count;
        }
    }

    private static String readLine(
        InputStream input,
        OpenAiResponsesTransportPort.Request request,
        Deadline deadline,
        int maximumBytes
    ) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        boolean carriageReturn = false;
        while (line.size() <= maximumBytes) {
            OpenAiResponsesNetworkSupport.requireNotCancelled(request);
            deadline.requireRemaining();
            int value = input.read();
            if (value < 0) {
                throw failure(
                    OpenAiResponsesTransportException.Failure.HTTP_PROTOCOL_INVALID
                );
            }
            if (carriageReturn) {
                if (value != '\n') {
                    throw failure(
                        OpenAiResponsesTransportException.Failure.HTTP_PROTOCOL_INVALID
                    );
                }
                return line.toString(StandardCharsets.US_ASCII);
            }
            if (value == '\r') {
                carriageReturn = true;
            } else if (value == '\n' || value > 0x7e
                || (value < 0x20 && value != '\t')) {
                throw failure(
                    OpenAiResponsesTransportException.Failure.HTTP_PROTOCOL_INVALID
                );
            } else {
                line.write(value);
            }
        }
        throw failure(OpenAiResponsesTransportException.Failure.HTTP_PROTOCOL_INVALID);
    }

    private static boolean validHeaderName(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean token = Character.isLetterOrDigit(character)
                || "!#$%&'*+-.^_`|~".indexOf(character) >= 0;
            if (!token) {
                return false;
            }
        }
        return true;
    }

    private static boolean validHeaderValue(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != '\t' && (character < 0x20 || character > 0x7e)) {
                return false;
            }
        }
        return true;
    }

    private static void writeAscii(OutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static OpenAiResponsesTransportException failure(
        OpenAiResponsesTransportException.Failure failure
    ) {
        return new OpenAiResponsesTransportException(failure);
    }
}

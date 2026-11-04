package io.github.rururunu.mqttmacchiatto.internal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Client and server WebSocket framing for one MQTT byte stream. The client masks every
 * outbound data frame. Control frames received here are answered with a queued pong or close.
 */
public final class WebSocketCodec {
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int OP_CONTINUATION = 0x0;
    private static final int OP_TEXT = 0x1;
    private static final int OP_BINARY = 0x2;
    private static final int OP_CLOSE = 0x8;
    private static final int OP_PING = 0x9;
    private static final int OP_PONG = 0xA;

    private final boolean maskOutbound;
    private final byte[] maskKey = new byte[4];
    private final byte[] decodeMask = new byte[4];
    private final ByteBuffer pendingControl = ByteBuffer.allocate(128);
    private byte[] fragment;
    private int fragmentLength;
    private int fragmentOpcode = -1;

    public WebSocketCodec(boolean maskOutbound) {
        this.maskOutbound = maskOutbound;
        this.pendingControl.limit(0);
    }

    public boolean hasControl() {
        return pendingControl.hasRemaining();
    }

    public ByteBuffer control() {
        return pendingControl;
    }

    public static ByteBuffer clientRequest(String host, int port, String path, byte[] key) {
        return clientRequest(host, port, path, key, Map.of());
    }

    public static ByteBuffer clientRequest(String host, int port, String path, byte[] key, Map<String, String> headers) {
        String encoded = Base64.getEncoder().encodeToString(key);
        StringBuilder text = new StringBuilder();
        text.append("GET ").append(path).append(" HTTP/1.1\r\n");
        text.append("Host: ").append(host).append(':').append(port).append("\r\n");
        text.append("Upgrade: websocket\r\n");
        text.append("Connection: Upgrade\r\n");
        text.append("Sec-WebSocket-Key: ").append(encoded).append("\r\n");
        text.append("Sec-WebSocket-Version: 13\r\n");
        text.append("Sec-WebSocket-Protocol: mqtt\r\n");
        if (headers != null) {
            for (Map.Entry<String, String> header : headers.entrySet()) {
                text.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
            }
        }
        text.append("\r\n");
        return ByteBuffer.wrap(text.toString().getBytes(StandardCharsets.US_ASCII));
    }

    public static void verifyResponse(String headers, byte[] key) {
        String lower = headers.toLowerCase();
        if (!lower.startsWith("http/1.1 101") && !headers.startsWith("HTTP/1.1 101")) {
            throw new IllegalArgumentException("websocket upgrade was refused");
        }
        String accept = header(headers, "Sec-WebSocket-Accept");
        if (accept == null || !accept.equals(acceptValue(Base64.getEncoder().encodeToString(key)))) {
            throw new IllegalArgumentException("websocket accept key does not match");
        }
    }

    public static String serverResponse(String key) {
        return "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + acceptValue(key) + "\r\n"
                + "Sec-WebSocket-Protocol: mqtt\r\n"
                + "\r\n";
    }

    public static String acceptValue(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hashed = digest.digest((key.trim() + GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is required", e);
        }
    }

    public static String header(String headers, String name) {
        String[] lines = headers.split("\r\n");
        String prefix = name.toLowerCase();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) {
                continue;
            }
            if (lines[i].substring(0, colon).trim().toLowerCase().equals(prefix)) {
                return lines[i].substring(colon + 1).trim();
            }
        }
        return null;
    }

    /** Frames {@code mqtt} as one binary message and flips {@code out} for reading. */
    public void encodeBinary(ByteBuffer mqtt, ByteBuffer out) {
        int length = mqtt.remaining();
        out.clear();
        out.put((byte) (0x80 | OP_BINARY));
        int maskBit = maskOutbound ? 0x80 : 0;
        if (length < 126) {
            out.put((byte) (maskBit | length));
        } else if (length <= 65_535) {
            out.put((byte) (maskBit | 126));
            out.putShort((short) length);
        } else {
            out.put((byte) (maskBit | 127));
            out.putLong(length);
        }
        byte[] mask = null;
        if (maskOutbound) {
            ThreadLocalRandom.current().nextBytes(maskKey);
            out.put(maskKey);
            mask = maskKey;
        }
        if (mask == null) {
            out.put(mqtt);
        } else {
            int i = 0;
            while (mqtt.hasRemaining()) {
                out.put((byte) (mqtt.get() ^ mask[i & 3]));
                i++;
            }
        }
        out.flip();
    }

    /**
     * Pulls complete frames from {@code raw}. MQTT payload is appended to {@code mqttOut}.
     * {@code raw} is compacted to the unparsed tail. Returns false when a close frame arrives.
     */
    public boolean pull(ByteBuffer raw, ByteBuffer mqttOut) {
        raw.flip();
        boolean open = true;
        while (open) {
            int start = raw.position();
            if (raw.remaining() < 2) {
                break;
            }
            int b0 = raw.get() & 0xFF;
            int b1 = raw.get() & 0xFF;
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long length = b1 & 0x7F;
            int extra = (length == 126 ? 2 : length == 127 ? 8 : 0) + (masked ? 4 : 0);
            if (raw.remaining() < extra) {
                raw.position(start);
                break;
            }
            if (length == 126) {
                length = Short.toUnsignedInt(raw.getShort());
            } else if (length == 127) {
                length = raw.getLong();
                if (length < 0) {
                    throw new IllegalArgumentException("websocket frame is too large");
                }
            }
            byte[] mask = null;
            if (masked) {
                raw.get(decodeMask);
                mask = decodeMask;
            }
            if (length > raw.remaining()) {
                raw.position(start);
                break;
            }
            int payloadStart = raw.position();
            if (mask != null) {
                for (int i = 0; i < length; i++) {
                    int index = payloadStart + i;
                    raw.put(index, (byte) (raw.get(index) ^ mask[i & 3]));
                }
            }
            if (opcode == OP_CLOSE) {
                open = false;
                raw.position(payloadStart + (int) length);
                break;
            }
            if (opcode == OP_PING) {
                queueControl(OP_PONG, raw, payloadStart, (int) length);
                raw.position(payloadStart + (int) length);
                continue;
            }
            if (opcode == OP_PONG) {
                raw.position(payloadStart + (int) length);
                continue;
            }
            if (opcode == OP_TEXT) {
                throw new IllegalArgumentException("websocket text frames are not MQTT");
            }
            if (opcode == OP_CONTINUATION || opcode == OP_BINARY) {
                if (opcode == OP_BINARY) {
                    fragmentOpcode = OP_BINARY;
                    fragmentLength = 0;
                } else if (fragmentOpcode != OP_BINARY) {
                    throw new IllegalArgumentException("websocket continuation without a binary frame");
                }
                if (fin && mqttOut.remaining() < fragmentLength + length) {
                    raw.position(start);
                    break;
                }
                appendFragment(raw, payloadStart, (int) length);
                raw.position(payloadStart + (int) length);
                if (fin) {
                    mqttOut.put(fragment, 0, fragmentLength);
                    fragmentLength = 0;
                    fragmentOpcode = -1;
                }
                continue;
            }
            throw new IllegalArgumentException("unsupported websocket opcode " + opcode);
        }
        raw.compact();
        return open;
    }

    private void appendFragment(ByteBuffer raw, int offset, int length) {
        int needed = fragmentLength + length;
        if (fragment == null || fragment.length < needed) {
            byte[] larger = new byte[Math.max(needed, fragment == null ? 256 : fragment.length * 2)];
            if (fragmentLength > 0) {
                System.arraycopy(fragment, 0, larger, 0, fragmentLength);
            }
            fragment = larger;
        }
        for (int i = 0; i < length; i++) {
            fragment[fragmentLength + i] = raw.get(offset + i);
        }
        fragmentLength += length;
    }

    private void queueControl(int opcode, ByteBuffer raw, int offset, int length) {
        int size = 2 + length;
        if (pendingControl.capacity() < pendingControl.limit() + size) {
            return;
        }
        int savedPos = pendingControl.position();
        int savedLim = pendingControl.limit();
        pendingControl.limit(pendingControl.capacity());
        pendingControl.position(savedLim);
        pendingControl.put((byte) (0x80 | opcode));
        pendingControl.put((byte) length);
        for (int i = 0; i < length; i++) {
            pendingControl.put(raw.get(offset + i));
        }
        pendingControl.limit(pendingControl.position());
        pendingControl.position(savedPos);
    }
}

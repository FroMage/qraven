package io.github.fromage.qraven.hardcoded;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A minimal CBOR (RFC 8949) encoder and decoder, covering what the generation state needs: unsigned integers,
 * text and byte strings, arrays, maps, booleans and null. Lengths are always definite and integers use their
 * shortest encoding, as in RFC 8949 section 4.2.1.
 */
public final class Cbor {

    private static final int UINT = 0;
    private static final int BYTES = 2;
    private static final int TEXT = 3;
    private static final int ARRAY = 4;
    private static final int MAP = 5;

    private static final int FALSE = 0xf4;
    private static final int TRUE = 0xf5;
    private static final int NULL = 0xf6;

    private Cbor() {
    }

    public static final class Writer {
        private byte[] buf = new byte[1024];
        private int len;

        private void ensure(int n) {
            if (len + n > buf.length) {
                buf = Arrays.copyOf(buf, Math.max(buf.length * 2, len + n));
            }
        }

        private void put(int b) {
            ensure(1);
            buf[len++] = (byte) b;
        }

        private void head(int major, long v) {
            if (v < 0) {
                throw new IllegalArgumentException("negative value: " + v);
            }
            int m = major << 5;
            if (v < 24) {
                put(m | (int) v);
            } else if (v <= 0xff) {
                put(m | 24);
                put((int) v);
            } else if (v <= 0xffff) {
                put(m | 25);
                put((int) (v >> 8));
                put((int) v);
            } else if (v <= 0xffffffffL) {
                put(m | 26);
                for (int shift = 24; shift >= 0; shift -= 8) {
                    put((int) (v >> shift));
                }
            } else {
                put(m | 27);
                for (int shift = 56; shift >= 0; shift -= 8) {
                    put((int) (v >> shift));
                }
            }
        }

        public Writer uint(long v) {
            head(UINT, v);
            return this;
        }

        public Writer text(String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            head(TEXT, b.length);
            ensure(b.length);
            System.arraycopy(b, 0, buf, len, b.length);
            len += b.length;
            return this;
        }

        public Writer bytes(byte[] b) {
            return bytes(b, 0, b.length);
        }

        public Writer bytes(byte[] b, int offset, int length) {
            head(BYTES, length);
            ensure(length);
            System.arraycopy(b, offset, buf, len, length);
            len += length;
            return this;
        }

        /** Starts an array of {@code n} items, which the caller writes next. */
        public Writer array(int n) {
            head(ARRAY, n);
            return this;
        }

        /** Starts a map of {@code n} key/value pairs, which the caller writes next. */
        public Writer map(int n) {
            head(MAP, n);
            return this;
        }

        public Writer bool(boolean v) {
            put(v ? TRUE : FALSE);
            return this;
        }

        public Writer nul() {
            put(NULL);
            return this;
        }

        public int size() {
            return len;
        }

        public byte[] toByteArray() {
            return Arrays.copyOf(buf, len);
        }
    }

    /** A range of the reader's backing array. */
    public record Range(int offset, int length) {
    }

    public static final class Reader {
        private final byte[] data;
        private int pos;
        private final int end;

        public Reader(byte[] data) {
            this(data, 0, data.length);
        }

        public Reader(byte[] data, int offset, int length) {
            if (offset < 0 || length < 0 || offset + length > data.length) {
                throw new IllegalArgumentException("range outside of the data");
            }
            this.data = data;
            this.pos = offset;
            this.end = offset + length;
        }

        private int next() {
            if (pos >= end) {
                throw new IllegalStateException("truncated CBOR");
            }
            return data[pos++] & 0xff;
        }

        private long head(int expectedMajor) {
            int initial = next();
            if (initial >> 5 != expectedMajor) {
                throw new IllegalStateException("expected CBOR major type " + expectedMajor + " but found "
                        + (initial >> 5) + " at offset " + (pos - 1));
            }
            int info = initial & 31;
            if (info < 24) {
                return info;
            }
            int bytes = switch (info) {
                case 24 -> 1;
                case 25 -> 2;
                case 26 -> 4;
                case 27 -> 8;
                default -> throw new IllegalStateException("unsupported CBOR additional information " + info);
            };
            long v = 0;
            for (int i = 0; i < bytes; i++) {
                v = (v << 8) | next();
            }
            if (v < 0) {
                throw new IllegalStateException("integer too large");
            }
            return v;
        }

        /** A length that must still fit in the remaining data, so a corrupt length can't cause a huge allocation. */
        private int length(int major) {
            long n = head(major);
            if (n > end - pos) {
                throw new IllegalStateException("CBOR length " + n + " exceeds the remaining data");
            }
            return (int) n;
        }

        public long uint() {
            return head(UINT);
        }

        public String text() {
            int n = length(TEXT);
            String s = new String(data, pos, n, StandardCharsets.UTF_8);
            pos += n;
            return s;
        }

        /** Reads a byte string, returning where its content is in the backing array instead of copying it. */
        public Range bytes() {
            int n = length(BYTES);
            Range r = new Range(pos, n);
            pos += n;
            return r;
        }

        public int array() {
            return length(ARRAY);
        }

        public int map() {
            return length(MAP);
        }

        public boolean bool() {
            int b = next();
            if (b == TRUE) {
                return true;
            }
            if (b == FALSE) {
                return false;
            }
            throw new IllegalStateException("expected a boolean at offset " + (pos - 1));
        }

        public boolean peekNull() {
            return pos < end && (data[pos] & 0xff) == NULL;
        }

        public void nul() {
            if (next() != NULL) {
                throw new IllegalStateException("expected null at offset " + (pos - 1));
            }
        }

        public boolean atEnd() {
            return pos == end;
        }

        public byte[] data() {
            return data;
        }
    }
}

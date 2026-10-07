package io.github.fromage.qraven.tests;

import io.github.fromage.qraven.hardcoded.Cbor;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CborTest {

    private static String hex(Consumer<Cbor.Writer> encoder) {
        Cbor.Writer w = new Cbor.Writer();
        encoder.accept(w);
        return HexFormat.of().formatHex(w.toByteArray());
    }

    // The expected encodings are the examples of RFC 8949, appendix A.

    @Test
    void unsignedIntegers_useTheShortestEncoding() {
        assertThat(hex(w -> w.uint(0))).isEqualTo("00");
        assertThat(hex(w -> w.uint(1))).isEqualTo("01");
        assertThat(hex(w -> w.uint(10))).isEqualTo("0a");
        assertThat(hex(w -> w.uint(23))).isEqualTo("17");
        assertThat(hex(w -> w.uint(24))).isEqualTo("1818");
        assertThat(hex(w -> w.uint(25))).isEqualTo("1819");
        assertThat(hex(w -> w.uint(100))).isEqualTo("1864");
        assertThat(hex(w -> w.uint(255))).isEqualTo("18ff");
        assertThat(hex(w -> w.uint(256))).isEqualTo("190100");
        assertThat(hex(w -> w.uint(1000))).isEqualTo("1903e8");
        assertThat(hex(w -> w.uint(65535))).isEqualTo("19ffff");
        assertThat(hex(w -> w.uint(65536))).isEqualTo("1a00010000");
        assertThat(hex(w -> w.uint(1_000_000))).isEqualTo("1a000f4240");
        assertThat(hex(w -> w.uint(4_294_967_295L))).isEqualTo("1affffffff");
        assertThat(hex(w -> w.uint(4_294_967_296L))).isEqualTo("1b0000000100000000");
        assertThat(hex(w -> w.uint(1_000_000_000_000L))).isEqualTo("1b000000e8d4a51000");
    }

    @Test
    void strings_booleansAndNull() {
        assertThat(hex(w -> w.text(""))).isEqualTo("60");
        assertThat(hex(w -> w.text("a"))).isEqualTo("6161");
        assertThat(hex(w -> w.text("IETF"))).isEqualTo("6449455446");
        assertThat(hex(w -> w.text("ü"))).isEqualTo("62c3bc");
        assertThat(hex(w -> w.text("水"))).isEqualTo("63e6b0b4");
        assertThat(hex(w -> w.bool(false))).isEqualTo("f4");
        assertThat(hex(w -> w.bool(true))).isEqualTo("f5");
        assertThat(hex(Cbor.Writer::nul)).isEqualTo("f6");
        assertThat(hex(w -> w.bytes(new byte[] { 1, 2, 3, 4 }))).isEqualTo("4401020304");
    }

    @Test
    void arraysAndMaps() {
        assertThat(hex(w -> w.array(0))).isEqualTo("80");
        assertThat(hex(w -> w.array(3).uint(1).uint(2).uint(3))).isEqualTo("83010203");
        assertThat(hex(w -> w.map(0))).isEqualTo("a0");
        assertThat(hex(w -> w.map(2).text("a").uint(1).text("b").array(2).uint(2).uint(3)))
                .isEqualTo("a26161016162820203");
    }

    @Test
    void decodesWhatItEncodes() {
        Cbor.Writer w = new Cbor.Writer();
        w.array(5).uint(Long.MAX_VALUE).text("héllo 水").bool(true).nul()
                .map(1).text("k").bytes(new byte[] { 9, 8 });
        Cbor.Reader r = new Cbor.Reader(w.toByteArray());

        assertThat(r.array()).isEqualTo(5);
        assertThat(r.uint()).isEqualTo(Long.MAX_VALUE);
        assertThat(r.text()).isEqualTo("héllo 水");
        assertThat(r.bool()).isTrue();
        assertThat(r.peekNull()).isTrue();
        r.nul();
        assertThat(r.map()).isEqualTo(1);
        assertThat(r.text()).isEqualTo("k");
        Cbor.Range bytes = r.bytes();
        assertThat(bytes.length()).isEqualTo(2);
        assertThat(r.data()[bytes.offset()]).isEqualTo((byte) 9);
        assertThat(r.atEnd()).isTrue();
    }

    @Test
    void largeValuesGrowTheBuffer() {
        Cbor.Writer w = new Cbor.Writer();
        w.array(1000);
        for (int i = 0; i < 1000; i++) {
            w.text("string number " + i);
        }
        Cbor.Reader r = new Cbor.Reader(w.toByteArray());
        assertThat(r.array()).isEqualTo(1000);
        for (int i = 0; i < 1000; i++) {
            assertThat(r.text()).isEqualTo("string number " + i);
        }
    }

    @Test
    void malformedInput_failsInsteadOfMisreading() {
        // truncated
        assertThatThrownBy(() -> new Cbor.Reader(HexFormat.of().parseHex("1903")).uint())
                .isInstanceOf(IllegalStateException.class);
        // text where an integer is expected
        assertThatThrownBy(() -> new Cbor.Reader(HexFormat.of().parseHex("6161")).uint())
                .isInstanceOf(IllegalStateException.class);
        // a length larger than the data left must not allocate or read past the end
        assertThatThrownBy(() -> new Cbor.Reader(HexFormat.of().parseHex("5affffffff00")).bytes())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new Cbor.Reader(HexFormat.of().parseHex("9a7fffffff")).array())
                .isInstanceOf(IllegalStateException.class);
        // indefinite lengths are not supported
        assertThatThrownBy(() -> new Cbor.Reader(HexFormat.of().parseHex("9f")).array())
                .isInstanceOf(IllegalStateException.class);
        // an integer that does not fit in a long
        assertThatThrownBy(() -> new Cbor.Reader(HexFormat.of().parseHex("1bffffffffffffffff")).uint())
                .isInstanceOf(IllegalStateException.class);
        // a reader must not leave its range
        assertThatThrownBy(() -> new Cbor.Reader(new byte[2], 1, 5)).isInstanceOf(IllegalArgumentException.class);
    }
}

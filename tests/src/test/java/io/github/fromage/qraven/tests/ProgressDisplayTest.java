package io.github.fromage.qraven.tests;

import io.github.fromage.qraven.hardcoded.runtime.ProgressDisplay;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProgressDisplayTest {

    /** The bar line of the last frame the display drew while running the given steps. */
    private static String lastBar(int rebuildCount, java.util.function.Consumer<ProgressDisplay> steps) {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true));
        try {
            ProgressDisplay display = new ProgressDisplay(rebuildCount, 2);
            steps.accept(display);
        } finally {
            System.setErr(original);
        }
        List<String> bars = captured.toString().replaceAll("\u001b\\[[0-9;]*[A-Za-z]", "").lines()
                .map(String::strip).filter(l -> l.startsWith("[")).toList();
        return bars.get(bars.size() - 1);
    }

    @Test
    void theBarCountsTheModulesBeingBuiltNotTheModulesInTheProject() {
        String bar = lastBar(3, d -> {
            for (String m : List.of("a", "b", "c")) {
                d.moduleStarted(0, m, "compile", 1);
                d.moduleCompleted(0, m, true);
            }
        });

        assertThat(bar).contains("] 3/3");
    }

    @Test
    void aModuleThatReportsTwiceCountsOnce() {
        String bar = lastBar(2, d -> {
            d.moduleCompleted(0, "a", true);
            d.moduleCompleted(0, "a", true);
            d.moduleCompleted(1, "b", true);
        });

        assertThat(bar).contains("] 2/2");
    }

    @Test
    void aModuleThatFailsAfterSucceedingIsCountedAsFailed() {
        String bar = lastBar(1, d -> {
            d.moduleCompleted(0, "a", true);
            d.moduleCompleted(0, "a", false);
        });

        assertThat(bar).contains("] 1/1").contains("1 failed");
    }
}

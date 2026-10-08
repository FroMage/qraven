package io.github.fromage.qraven.hardcoded;

/**
 * Output printed while generation runs. Generation shows its progress as a single line on stderr that is rewritten in
 * place and has no newline, so anything else printed meanwhile would end up glued to the end of it. These methods clear
 * that line first, so each message starts on a line of its own and the progress line comes back on the next update.
 */
public final class Status {

    /** Returns to the start of the line and erases it. */
    public static final String ERASE_LINE = "\r\u001b[2K";

    private Status() {
    }

    /** An informational line, on stdout. */
    public static synchronized void line(String message) {
        System.err.print(ERASE_LINE);
        System.err.flush();
        System.out.println(message);
        System.out.flush();
    }

    /** A warning, on stderr. */
    public static synchronized void warning(String message) {
        System.err.print(ERASE_LINE);
        System.err.println(message);
        System.err.flush();
    }
}

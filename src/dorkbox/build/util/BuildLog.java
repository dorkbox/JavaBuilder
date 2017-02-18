/*
 * Copyright 2012 dorkbox, llc
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
package dorkbox.build.util;

import java.io.PrintStream;

import dorkbox.util.OS;

// UNICODE is from: https://en.wikipedia.org/wiki/List_of_Unicode_characters#Box_Drawing

public
class BuildLog {
    private static final int TITLE_ADJUSTMENT = 2;

    private static final int STOCK_TITLE_WIDTH = 17;
    private static int TITLE_WIDTH = STOCK_TITLE_WIDTH;

    private static final String TITLE_MESSAGE_DELIMITER = "│";
    private static final String TITLE_SEPERATOR = "─";

    public static BuildLog LOG = new BuildLog();
    private static int nestedCount = 0;
    private static int suppressCount = 0;

    private static PrintStream printer = System.err;

    private static StringBuilder titleBuilder = null;

    private static StringBuilder cachedSpacer;
    private static int cachedSpacerWidth = -1;

    private static boolean lastActionWasPrintln = true;

    /**
     * Starts a new section in the log
     * @return
     */
    public static synchronized
    BuildLog start() {
        if (suppressCount == 0) {
            nestedCount++;
            titleStart();
        }
        return LOG;
    }

    /**
     * Closes this section of the log
     */
    public static synchronized
    BuildLog finish() {
        if (suppressCount == 0) {
            nestedCount--;
            titleEnd();
        }
        titleBuilder = null;
        return LOG;
    }


    /**
     * Resets all of the log state
     */
    public static synchronized
    BuildLog reset() {
        nestedCount = 0;
        suppressCount = 0;

        TITLE_WIDTH = STOCK_TITLE_WIDTH;

        cachedSpacer = null;
        titleBuilder = null;

        lastActionWasPrintln = true;
        return LOG;
    }

    /**
     * Forces the "end" of this, so that there is no trailing "opening" for further log elements
     */
    public static synchronized
    BuildLog finish_force() {
        if (suppressCount == 0) {
            nestedCount--;

            String sep = TITLE_SEPERATOR;
            StringBuilder spacerTitle = new StringBuilder(TITLE_WIDTH);
            for (int i = 2; i < TITLE_WIDTH; i++) {
                spacerTitle.append(sep);
            }

            spacerTitle.append(sep);
            spacerTitle.append(sep);
            spacerTitle.append('╯');

            printer.println(spacerTitle.toString());
            TITLE_WIDTH -= 2;
        }

        reset();
        return LOG;
    }

    public static synchronized
    BuildLog enable() {
        if (suppressCount > 0) {
            // don't let us go <0. Enable has to match disable counts to build, but if there are too many "enable", who cares
            suppressCount--;
        }
        return LOG;
    }

    public static synchronized
    BuildLog disable() {
        suppressCount++;
        return LOG;
    }

    public static synchronized
    PrintStream getOutput() {
        return printer;
    }

    public static synchronized
    BuildLog setOutput(PrintStream printer) {
        BuildLog.printer = printer;
        return LOG;
    }

    public static synchronized
    int getNestedCount() {
        return nestedCount;
    }

    public static synchronized
    BuildLog title(String title) {
        // always set the title
        titleBuilder = null;

        if (!lastActionWasPrintln) {
            printer.println();
            lastActionWasPrintln = true;
        }

        prepTitle(title, true);
        return LOG;
    }

    private static synchronized
    void titleStart() {
        String sep = TITLE_SEPERATOR;

        boolean atBeginning = TITLE_WIDTH <= STOCK_TITLE_WIDTH;
        TITLE_WIDTH += TITLE_ADJUSTMENT;

        StringBuilder spacerTitle = new StringBuilder(TITLE_WIDTH);
        for (int i = 2; i < TITLE_WIDTH; i++) {
            spacerTitle.append(sep);
        }

        if (atBeginning) {
            spacerTitle.append(sep);
        }
        else {
            spacerTitle.append('┴');
        }

        spacerTitle.append(sep);
        spacerTitle.append('╮');

        printer.println(spacerTitle.toString());
    }

    private static
    void titleEnd() {
        String sep = TITLE_SEPERATOR;

        StringBuilder spacerTitle = new StringBuilder(TITLE_WIDTH);
        for (int i = 2; i < TITLE_WIDTH; i++) {
            spacerTitle.append(sep);
        }

        TITLE_WIDTH -= TITLE_ADJUSTMENT;
        boolean atBeginning = TITLE_WIDTH <= STOCK_TITLE_WIDTH;

        if (atBeginning) {
            spacerTitle.append(sep);
        }
        else {
            spacerTitle.append('┬');
        }

        spacerTitle.append(sep);
        spacerTitle.append('╯');

        printer.println(spacerTitle.toString());


    }

    public static synchronized
    BuildLog println() {
        println((String) null);
        return LOG;
    }

    /**
     * Creates everything in front of the message section, so that our "message" can be appended to each log entry if desired
     */
    private static
    StringBuilder prepTitle(String title, boolean newLine) {
        char spacer1 = ' ';

        if (cachedSpacerWidth != TITLE_WIDTH || cachedSpacer == null) {
            cachedSpacerWidth = TITLE_WIDTH;
            StringBuilder spacerTitle = new StringBuilder(TITLE_WIDTH);
            for (int i = 0; i < TITLE_WIDTH; i++) {
                spacerTitle.append(spacer1);
            }
            cachedSpacer = spacerTitle;
        }

        if (title == null) {
            if (titleBuilder == null) {
                titleBuilder = new StringBuilder(1024);
            }

            if (newLine) {
                titleBuilder.append(cachedSpacer)
                            .append(TITLE_MESSAGE_DELIMITER)
                            .append(spacer1);
            }

            return titleBuilder;
        }

        if (titleBuilder == null) {
            int length = title.length();
            int padding = TITLE_WIDTH - length - 1;
            StringBuilder msg = new StringBuilder(TITLE_WIDTH);
            boolean addFollowingSpacer = true;

            String adjustedTitle = title;
            if (length == TITLE_WIDTH) {
                // just BARELY too long!
                addFollowingSpacer = false;
            }
            else if (length > TITLE_WIDTH) {
                // too long!
                adjustedTitle = title.substring(0, TITLE_WIDTH - 2) + "..";
                length = adjustedTitle.length();
                addFollowingSpacer = false;
            }

            if (padding > 0) {
                for (int i = 0; i < padding; i++) {
                    msg.append(spacer1);
                }
            }
            msg.append(adjustedTitle);
            if (addFollowingSpacer) {
                msg.append(spacer1);
            }

            titleBuilder = new StringBuilder(1024);
            titleBuilder.append(msg)
                        .append(TITLE_MESSAGE_DELIMITER)
                        .append(spacer1);
        }

        return titleBuilder;
    }

    public static synchronized
    BuildLog print(Object... message) {
        print(false, message);
        // the first print will print the title, the following will NOT.
        titleBuilder = null;
        return LOG;
    }

    public static synchronized
    BuildLog println(Object... message) {
        print(true, message);
        return LOG;
    }

    private static
    void print(boolean newLine, Object... message) {
        if (suppressCount != 0) {
            if (newLine) {
                titleBuilder = null;
            }
            return;
        }

        // only makes it if necessary
        StringBuilder msg;
        if (titleBuilder == null) {
            msg = prepTitle(null, newLine);
        }
        else {
            msg = titleBuilder;
        }

        if (message != null && message.length > 0 && message[0] != null) {
            String titleMessageDelimiter = TITLE_MESSAGE_DELIMITER;
            String newLineToken = OS.LINE_SEPARATOR;
            int start = 0;

            char spacer1 = ' ';

            // if we have more than one message, the messages AFTER the first one are INDENTED
            msg.append(message[start++]);

            if (message.length > 1) {
                for (int i = start; i < message.length; i++) {
                    String m = message[i].toString();
                    if (msg.length() > 0) {
                        msg.append(newLineToken);
                    }
                    // next line
                    msg.append(cachedSpacer)
                       .append(titleMessageDelimiter)
                       .append(spacer1)
                       .append(spacer1)
                       .append(spacer1)
                       .append(m);
                }
            }
        }

        if (newLine) {
            printer.println(msg.toString());
            titleBuilder = null;
            lastActionWasPrintln = true;
        }
        else {
            printer.print(msg.toString());
            lastActionWasPrintln = false;
        }
    }
}

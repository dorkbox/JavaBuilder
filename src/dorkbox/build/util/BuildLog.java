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

import dorkbox.util.OS;

import java.io.PrintStream;

public
class BuildLog {
    public static final int STOCK_TITLE_WIDTH = 16;

    private static final String TITLE_MESSAGE_DELIMITER = "│";
    private static final String TITLE_SEPERATOR = "─";

    public static volatile int TITLE_WIDTH = 14; // so the first one goes to 16
    public static boolean wasNested = false;
    private static volatile boolean suppressOutput = false;

    public static
    BuildLog start() {
        wasNested = true;
        BuildLog buildLog = new BuildLog();
        buildLog.titleStart();
        return buildLog;
    }

    public static
    BuildLog finish() {
        BuildLog buildLog = new BuildLog();
        buildLog.titleEnd();
        return buildLog;
    }

    public static
    void enable() {
        suppressOutput = false;
    }

    public static
    void disable() {
        suppressOutput = true;
    }

    private final PrintStream printer;
    private StringBuilder titleBuilder = null;

    private int cachedSize = -1;
    private StringBuilder cachedSpacer;



    public
    BuildLog() {
        this(System.err);
    }

    public
    BuildLog(PrintStream printer) {
        this.printer = printer;
    }

    public
    BuildLog title(String title) {
        prepTitle(title, true);
        return this;
    }

    private
    void titleStart() {
        TITLE_WIDTH += 2;

        this.cachedSize = TITLE_WIDTH;
        String sep = TITLE_SEPERATOR;

        StringBuilder spacerTitle = new StringBuilder(this.cachedSize);
        for (int i = 2; i < this.cachedSize; i++) {
            spacerTitle.append(sep);
        }

        if (this.cachedSize == STOCK_TITLE_WIDTH) {
            spacerTitle.append(sep);
            spacerTitle.append(sep);
            spacerTitle.append('╮');
        }
        else {
            spacerTitle.append('┴');
            spacerTitle.append(sep);
            spacerTitle.append('╮');
        }
        this.printer.println(spacerTitle.toString());
    }

    private
    void titleEnd() {
        this.cachedSize = TITLE_WIDTH;
        String sep = TITLE_SEPERATOR;

        StringBuilder spacerTitle = new StringBuilder(this.cachedSize);
        for (int i = 2; i < this.cachedSize; i++) {
            spacerTitle.append(sep);
        }

        if (this.cachedSize == STOCK_TITLE_WIDTH) {
            spacerTitle.append(sep);
            spacerTitle.append(sep);
            spacerTitle.append('╯');
        }
        else {
            spacerTitle.append('┬');
            spacerTitle.append(sep);
            spacerTitle.append('╯');
        }
        this.printer.println(spacerTitle.toString());

        TITLE_WIDTH -= 2;
    }

    public
    void println() {
        println((String) null);
    }

    /**
     * Creates everything in front of the message section, so that our "message" can be appended to each log entry if desired
     *
     * @return
     */
    private
    StringBuilder prepTitle(String title, boolean newLine) {
        char spacer1 = ' ';

        if (this.cachedSize != TITLE_WIDTH || this.cachedSpacer == null) {
            this.cachedSize = TITLE_WIDTH;
            StringBuilder spacerTitle = new StringBuilder(this.cachedSize);
            for (int i = 0; i < this.cachedSize; i++) {
                spacerTitle.append(spacer1);
            }
            this.cachedSpacer = spacerTitle;
        }

        if (title == null) {
            if (this.titleBuilder == null) {
                this.titleBuilder = new StringBuilder(1024);
                this.titleBuilder.append(this.cachedSpacer).append(TITLE_MESSAGE_DELIMITER).append(spacer1);
            }
            else if (!newLine) {
                this.titleBuilder = new StringBuilder(1024);
            }

            return this.titleBuilder;
        }

        if (this.titleBuilder == null) {
            int length = title.length();
            int padding = this.cachedSize - length - 1;
            StringBuilder msg = new StringBuilder(this.cachedSize);
            boolean addFollowingSpacer = true;

            String adjustedTitle = title;
            if (length == this.cachedSize) {
                // just BARELY too long!
                addFollowingSpacer = false;
            }
            else if (length > this.cachedSize) {
                // too long!
                adjustedTitle = title.substring(0, this.cachedSize - 2) + "..";
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

            this.titleBuilder = new StringBuilder(1024);
            this.titleBuilder.append(msg).append(TITLE_MESSAGE_DELIMITER).append(spacer1);
        }

        return this.titleBuilder;
    }

    public
    void print(Object... message) {
        print(false, message);
    }

    public
    void println(Object... message) {
        print(true, message);
    }

    private final
    void print(boolean newLine, Object... message) {
        if (suppressOutput) {
            return;
        }

        // only makes it if necessary
        StringBuilder msg = prepTitle(null, newLine);

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
                    msg.append(this.cachedSpacer).append(titleMessageDelimiter).append(spacer1).append(spacer1).append(spacer1).append(m);
                }
            }
        }

        if (newLine) {
            this.printer.println(msg.toString());
            this.titleBuilder = null;
        }
        else {
            this.printer.print(msg.toString());
        }
    }
}

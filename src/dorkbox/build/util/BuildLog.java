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

public class BuildLog {
    private static boolean suppressOutput = false;

    public static void start() {
        suppressOutput = false;
    }

    public static void stop() {
        suppressOutput = true;
    }

    private PrintStream printer;
    private String title;

    public BuildLog() {
        this.printer = System.err;
    }

    public BuildLog(PrintStream printer) {
        this.printer = printer;
    }

    public BuildLog title(String title) {
        this.title = title;
        return this;
    }

    public void message() {
        message((String) null);
    }

    public void message(String... message) {
        if (suppressOutput) {
            return;
        }

        String spacer = "                ";

        if (this.title == null) {
            this.title = spacer;
        } else {
            int length = this.title.length();
            int padding = 16 - length;
            if (padding > 0) {
                StringBuilder msg = new StringBuilder(16);
                msg.append(this.title);
                for (int i = 0; i < padding; i++) {
                    msg.append(" ");
                }
                this.title = msg.toString();
            }
        }

        if (message != null && message.length > 0 && message[0] != null) {
            StringBuilder msg = new StringBuilder(1024);
            int start = 0;
            if (message.length > 1) {
                msg.append(this.title).append(": ").append(message[start++]);
            }

            String newLineToken = OS.LINE_SEPARATOR;
            for (int i = start; i < message.length; i++) {
                String m = message[i];
                if (msg.length() > 0) {
                    msg.append(newLineToken);
                }
                msg.append(spacer).append(": ").append("  ").append(m);
            }

            this.printer.println(msg.toString());
        } else {
            StringBuilder msg = new StringBuilder(1024);
            msg.append(this.title).append(":");
            this.printer.println(msg.toString());
        }
    }

    public void print(String message) {
        if (suppressOutput) {
            return;
        }
        this.printer.print(message);
    }

    public void println(String message) {
        if (suppressOutput) {
            return;
        }
        this.printer.println(message);
    }

    public void write(int b) {
        if (suppressOutput) {
            return;
        }
        this.printer.write(b);
    }
}

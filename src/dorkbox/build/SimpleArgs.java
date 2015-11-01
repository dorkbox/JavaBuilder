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
package dorkbox.build;

import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class SimpleArgs {

    private final Set<String> argsAsSet;
    private final String[] args;
    private int lastIndex = 0;
    private Date buildDate = null;

    public SimpleArgs(final String[] args) {
        if (args == null) {
            throw new NullPointerException("args cannot be null");
        }
        this.args = args;

        this.argsAsSet = new HashSet<String>(args.length);
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            String lowerCase = arg.toLowerCase(Locale.US);
            this.argsAsSet.add(lowerCase);

            if (lowerCase.startsWith("date")) {
                String string = arg.split("=")[1];
                this.buildDate = new Date(Long.parseLong(string));
            }
        }
    }

    /**
     * contains check with lower case.
     */
    public boolean has(String argToCheck) {
        String argToCheck2 = argToCheck.toLowerCase(Locale.US);

        return this.argsAsSet.contains(argToCheck2);
    }

    /**
     * slower than 'has', since this returns the arg name, AND prepares {@link SimpleArgs#getNext()} for the arg value
     */
    public String get(String argToCheck) {
        String argToCheck2 = argToCheck.toLowerCase(Locale.US);

        String[] args2 = this.args;
        for (int i = 0; i < args2.length; i++) {
            String arg = args2[i];
            String lowerCase = arg.toLowerCase(Locale.US);
            if (lowerCase.equals(argToCheck2)) {
                this.lastIndex = i;
                return arg;
            }
        }
        return null;
    }

    public String getNext() {
        return this.args[this.lastIndex++];
    }

    @Override
    public String toString() {
        return Arrays.toString(this.args);
    }

    public String getMode() {
        return this.args[0];
    }

    public Date getBuildDate() {
        return this.buildDate;
    }

    public boolean isBuild() {
        return "build".equals(this.args[0]);
    }

    public String get(int i) {
        if (i >= this.args.length) {
            return null;
        }

        return this.args[i];
    }

    public String getParameters() {
        if (this.args.length > 0) {
            String[] copyOfRange = Arrays.copyOfRange(this.args, 1, this.args.length);
            return Arrays.toString(copyOfRange);
        } else {
            return "No args specified.";
        }
    }

    public
    String[] getArgs() {
        return args;
    }
}

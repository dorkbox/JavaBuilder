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

import java.io.IOException;

import com.esotericsoftware.wildcard.Paths;

import dorkbox.Builder;

public
class ShutdownHook implements Runnable {
    private final Paths paths;

    public
    ShutdownHook(final Paths paths) {
        this.paths = paths;
    }

    @Override
    public
    void run() {
        try {
            BuildLog.start();
            BuildLog.println("Saving build file checksums.");
            String hashedContents = Hash.generateChecksums(paths);
            Builder.settings.save("BUILD", hashedContents);
            BuildLog.finish();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

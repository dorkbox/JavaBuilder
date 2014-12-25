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
package dorkbox.build.util.jar;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

public class PackTask {

    InputStream inputStream;
    public Repack pack;
    long time;

    int length;
    boolean debug = false;
    public byte[] extraData;

    // info on signing the jar, if it's going to be encrypted.
    EncryptInterface encryption;

    public PackTask(Repack pack, byte[] entryAsBytes) {
        this.pack = pack;
        this.inputStream = new ByteArrayInputStream(entryAsBytes);
        this.length = entryAsBytes.length;
    }

    public PackTask(Pack pack, InputStream inputStream) {
        this.pack = pack;
        this.inputStream = inputStream;
        // length = inputStream.available(); // set in the calling method
    }

    @Override
    public String toString() {
        return "PackTask [" + this.pack.getName() + "]";
    }
}

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

public enum PackAction {
    // NOTE: these are packed into a TWO BYTES (for a total of 16 bitfields)
    // if you add/change this, MAKE SURE you also check in Launcher and Bootstrap!!
    // search for '// From PackAction' in all files to find the instances used!
    //

    // By default, everything will be pack200+compressed
    Store     (1 << 31), // MAX_INT  just store this file. Nothing will be done to it.
    Extract   (1 << 30), // MAX_INT/2  extract the contents of this compressed file to the root of the 'box' file (this is at compile time)



    // The following affect the file load action
    Pack      (1 << 0), // 1  pack200  - everything (jar, etc) that can be pack200, IS pack200.
    Lzma      (1 << 1), // 2  we only use LZMA, since it offers better compression than gzip
    Encrypt   (1 << 2), // 4  aes encryption

    /**
     * means we want to load our classloader DIRECTLY via JNI, so we can set our classloader up before anything else
     * WARNING. The files load in the order they are put in the jar - currently lexically, in alphabetical order. This matters a lot.
     */
    ClassLoader (1 << 3, Pack.getValue() | Lzma.getValue()), // 8


    /**
     * means we want to load this into our classloader before our launcher is started.
     */
    Load(1 << 4, Pack.getValue() | Lzma.getValue()), // 16


    /**
     * Load native libraries, or load jar's that are incompatible with our box file.
     * <p>
     * The extra data is the header + hash
     * <p>
     * It's not always possible to load our OWN libraries dll's, since some Java libraries have their
     * own method to loading dll's.
     * <p>
     * we RELY on the the jar ALREADY being NORMALIZED (PACK+UNPACK) - if it's not, hashes won't match
     */
    LoadLibray(1 << 5, Pack.getValue() | Lzma.getValue()),  // 32

    /**
     * This is necessary for LGPL content. It will NOT be a part of the signature hash, and will NOT BE ENCRYPTED when using this.
     * Resources/Javascript can also be LGPL (or variants).
     * <p>
     * This DOES NOT MEAN that the entire jar file/resource that is tagged LGPL is LGPL, just that part of it is.
     * <p>
     * Even if encrypt is "enabled" on an LGPL resource, the package process will IGNORE IT, since encrypt is not compatible with the
     * LGPL license. It also means that this file can be REPLACED in the box container, since that is also part of the LGPL requirement
     * so it is not hashed when ultra-signing the box containers!
     */
    LGPL (1 << 6, Pack.getValue() | Lzma.getValue()), // 64

    /**
     * This means that files will be loaded by the bootstrap launcher. IF THIS ISN'T THERE, THEY WILL NOT BE LOADED!!
     */
    Package(1 << 7, Pack.getValue() | Lzma.getValue() | Encrypt.getValue()), // 128
    ;


    private final int value;
    private final int baseValue;

    private PackAction(int baseValue) {
        this.value = baseValue;
        this.baseValue = baseValue;
    }

    /**
     * @param baseValue this is what the base value is for this pack action
     * @param extraActions these are extra actions to perform in addition to whatever the "base value" action is.
     */
    private PackAction(int baseValue, int extraActions) {
        this.baseValue = baseValue;
        this.value = baseValue | extraActions;
    }

    public int getBaseValue() {
        return this.baseValue;
    }

    int getValue() {
        return this.value;
    }
}

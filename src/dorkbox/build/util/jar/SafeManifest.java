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

import java.io.IOException;
import java.io.OutputStream;
import java.util.jar.Manifest;

/**
 * Necessary for preventing the manifest from mangling the order of it's contents.
 */
public class SafeManifest extends Manifest {
    private final byte[] manifestBytes;

    SafeManifest(byte[] manifestBytes) {
        this.manifestBytes = manifestBytes;
    }

    @Override
    public void write(OutputStream out) throws IOException {
        if (this.manifestBytes != null) {
            out.write(this.manifestBytes, 0, this.manifestBytes.length);
        }
    }

    public byte[] getBytes() {
        return this.manifestBytes;
    }
}

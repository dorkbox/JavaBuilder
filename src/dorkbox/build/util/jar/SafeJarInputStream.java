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
import java.io.IOException;
import java.io.InputStream;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import dorkbox.util.Sys;

/**
 * Necessary for preventing the manifest from mangling the order of it's contents.
 */
public class SafeJarInputStream extends JarInputStream {
    private final byte[] manifestBytes;

    public static SafeJarInputStream create(ByteArrayInputStream inputStream) throws IOException {
        // we KNOW that the manifest is the FIRST zip entry!
        // This is stupid, but the only way i know how to get the first entry
        // ALSO.. might not have a manifest!
        final byte[] manifestBytes;

        ZipInputStream zipInputStream = new ZipInputStream(inputStream);

        // this is the SAME as what a JarInputStream does, however the difference is
        // that here we SAVE OUT the bytes, instead of parse them.
        ZipEntry e = zipInputStream.getNextEntry();

        if (e != null && e.getName().equalsIgnoreCase("META-INF/")) {
            e = zipInputStream.getNextEntry();
        }

        if (e != null && JarFile.MANIFEST_NAME.equalsIgnoreCase(e.getName())) {
            manifestBytes = Sys.getBytesFromStream(zipInputStream);
        } else {
            manifestBytes = null;
        }
        Sys.close(zipInputStream);
        inputStream.reset();

        return new SafeJarInputStream(inputStream, true, manifestBytes);
    }


    private SafeJarInputStream(InputStream in, boolean verify, byte[] manifestBytes) throws IOException {
        super(in, verify);
        this.manifestBytes = manifestBytes;
    }

    @Override
    public Manifest getManifest() {
        if (this.manifestBytes == null) {
            return null;
        }

        return new SafeManifest(this.manifestBytes);
    }
}

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

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Map.Entry;

import dorkbox.util.annotation.ClassFileIterator;

/**
 * {@code ClassBytesIterator} is used to iterate over all Java ClassFile files available within
 * a specific context.
 * <p>
 * For every Java ClassFile ({@code .class}) an {@link InputStream} is returned.
 */
public class ClassByteIterator extends ClassFileIterator {


    private final Iterator<Entry<String, byte[]>> iterator;
    private String fullPath;

    /**
     * Create a new {@code ClassFileIterator} returning all Java ClassFile files available
     * from the specified files and/or directories, including sub directories.
     * <p>
     * If the (optional) package filter is defined, only class files staring with one of the
     * defined package names are returned.
     * NOTE: package names must be defined in the native format (using '/' instead of '.').
     */
    public ClassByteIterator(final ByteClassloader classloader, final String[] pkgNameFilter) {
        super(pkgNameFilter);
        this.iterator = classloader.getBytesIterator();
    }

    /**
     * Return the name of the Java ClassFile returned from the last call to {@link #next()}.
     * The name is the PACKAGE name of a file
     */
    @Override
    public String getName() {
        return this.fullPath;
    }

    /**
     * Return {@code true} if the current {@link InputStream} is reading from a plain
     * {@link File}.
     * Return {@code false} if the current {@link InputStream} is reading from a
     * ZIP File Entry.
     */
    @Override
    public boolean isFile() {
        return true;
    }

    /**
     * Return the next Java ClassFile as an {@code InputStream}.
     * <p>
     * NOTICE: Client code MUST close the returned {@code InputStream}!
     */
    @Override
    public InputStream next(final FilenameFilter filter) throws IOException {
        while (this.iterator.hasNext()) {
            Entry<String, byte[]> next = this.iterator.next();
            this.fullPath = next.getKey();

            File dir = null;
            String name = null;

                int lastIndexOf = this.fullPath.lastIndexOf(".");
                if (filter != null) {

                if (lastIndexOf > 0) {
                    dir = new File(this.fullPath.substring(0, lastIndexOf));
                    name = this.fullPath.substring(lastIndexOf+1);
                }
            } else {
                name = this.fullPath;
            }

            if (filter == null || filter.accept(dir, name)) {
                return new ByteArrayInputStream(next.getValue());
            }
            // else just ignore
        }

        return null;
    }
}

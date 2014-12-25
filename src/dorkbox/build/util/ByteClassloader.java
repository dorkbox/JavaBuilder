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

import java.security.ProtectionDomain;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

public class ByteClassloader extends java.lang.ClassLoader {

    private Map<String, byte[]> bytes = new ConcurrentHashMap<String, byte[]>();
    private Map<String, Class<?>> classes = new ConcurrentHashMap<String, Class<?>>();

    private final ProtectionDomain domain;

    public ByteClassloader(ClassLoader classloader) {
        super(classloader);

        this.domain = this.getClass().getProtectionDomain();
    }

    public final void saveBytes(String name, byte[] bytes) {
        // this defines our class, and saves it in our cache -- so that findClass() will work
        if (this.bytes != null) {
            this.bytes.put(name, bytes);
        }
    }

    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {
        return loadClass(name, false);
    }

    // check OURSELVES first, then check our parent.
    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (this.bytes != null) {
            byte[] classBytes = this.bytes.get(name);

            if (classBytes != null) {
                // have to make sure that the package is properly setup.
                int i = -1;
                String packageName = name;
                while ((i = name.indexOf('.', i)) > 0) {
                    packageName = name.substring(0, i++);
                    if (getPackage(packageName) == null) {
                        definePackage(packageName, null, null, null, null, null, null, null);
                    }
                }

                Class<?> clazz = defineClass(name, classBytes, 0, classBytes.length, this.domain);

                if (resolve) {
                    resolveClass(clazz);
                }

                // cache our classes that we create
                this.classes.put(name, clazz);

                return clazz;
            }
        }

        Class<?> c = this.classes.get(name);
        if (c != null) {
            return c;
        }

        return getParent().loadClass(name);
    }

    Iterator<Entry<String, byte[]>> getBytesIterator() {
        return this.bytes.entrySet().iterator();
    }
}
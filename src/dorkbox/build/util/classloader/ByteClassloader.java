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
package dorkbox.build.util.classloader;

import java.io.File;
import java.net.MalformedURLException;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

public class ByteClassloader extends ClassLoader {

    private Map<String, ClassInfo> info = new ConcurrentHashMap<String, ClassInfo>();


    public ByteClassloader(ClassLoader classloader) {
        super(classloader);
    }

    public final synchronized void saveBytes(String className, File locationSourceWasFrom, byte[] bytes) {
        // this defines our class, and saves it in our cache -- so that findClass() will work
        if (this.info != null && !this.info.containsKey(className)) {
            ClassInfo info = new ClassInfo();
            info.bytes = bytes;
            info.sourceRootLocation = locationSourceWasFrom;
            this.info.put(className, info);
        }
    }

    // this will check PARENT first, then check us.
    @Override
    public Class<?> findClass(String name) throws ClassNotFoundException {
        if (this.info != null) {
            ClassInfo info = this.info.get(name);

            if (info != null) {
                if (info.clazz != null) {
                    return info.clazz;
                }

                // have to make sure that the package is properly setup.
                int i = -1;
                String packageName = name;
                while ((i = name.indexOf('.', i)) > 0) {
                    packageName = name.substring(0, i++);
                    if (getPackage(packageName) == null) {
                        definePackage(packageName, null, null, null, null, null, null, null);
                    }
                }

                // keep the bytes around for the annotation finder
                byte[] classBytes = info.bytes;

                ProtectionDomain domain = null;
                try {
                    domain = new ProtectionDomain(
                                    new CodeSource(info.sourceRootLocation.toURI().toURL(), (Certificate[]) null),
                                    null);
                } catch (MalformedURLException e) {
                    e.printStackTrace();
                }


                Class<?> clazz = defineClass(name, classBytes, 0, classBytes.length, domain);

//                if (resolve) {
//                    resolveClass(clazz);
//                }

                // cache our classes that we create
                info.clazz = clazz;

                return clazz;
            }
        }

        throw new ClassNotFoundException(name);
//        return getParent().loadClass(name);
    }

    Iterator<Entry<String, ClassInfo>> getIterator() {
        return this.info.entrySet().iterator();
    }
}

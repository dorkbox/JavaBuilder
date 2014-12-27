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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.Map.Entry;

import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;

import com.esotericsoftware.wildcard.Paths;

public class JavaMemFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {

    static class ClassMemFileObject extends SimpleJavaFileObject {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        private String location;

        ClassMemFileObject(String className, String location) {
            super(URI.create("mem:///" + className + Kind.CLASS.extension), Kind.CLASS);

            // have to trim off subclasses/anon-class info from name.
            int length = className.indexOf('$');
            if (length < 0) {
                length = className.length();
            }

            // the location WILL have an extension (.java), since we are compiling that. usually.
            int locLength = location.lastIndexOf('.');
            if (locLength < 0) {
                locLength = location.length();
            }

            String loc = location.substring(0, locLength - length);
            this.location = loc;
        }

        byte[] getBytes() {
            return this.os.toByteArray();
        }

        String getLocation() {
            return this.location;
        }

        @Override
        public OutputStream openOutputStream() throws IOException {
            return this.os;
        }
    }



    private HashMap<String, ClassMemFileObject> classes = new HashMap<String, ClassMemFileObject>();
    private Paths source;
    private ByteClassloader bytesClassloader;

    public JavaMemFileManager(StandardJavaFileManager standardFileManager, ByteClassloader bytesClassloader) {
        super(standardFileManager);
        this.bytesClassloader = bytesClassloader;
    }

    @Override
    public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind, FileObject sibling)
                    throws IOException {

        if (StandardLocation.CLASS_OUTPUT == location && JavaFileObject.Kind.CLASS == kind) {
            ClassMemFileObject clazz = new ClassMemFileObject(className, sibling.getName());
            this.classes.put(className, clazz);
            return clazz;
        } else {
            return super.getJavaFileForOutput(location, className, kind, sibling);
        }
    }

    public void setSource(Paths source) {
        this.source = source;
    }

    public Iterable<? extends JavaFileObject> getSourceFiles() {
        return super.fileManager.getJavaFileObjectsFromFiles(this.source.getFiles());
    }

    @Override
    public void close() throws IOException {
        super.close();

        // and save all of our bytes into our classloader
        for (Entry<String, ClassMemFileObject> entry : this.classes.entrySet()) {
            String key = entry.getKey();
            ClassMemFileObject value = entry.getValue();

            this.bytesClassloader.saveBytes(key, value.getLocation(), value.getBytes());
        }

        this.classes.clear();
    }
}

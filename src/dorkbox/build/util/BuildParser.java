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

import com.esotericsoftware.wildcard.Paths;
import com.esotericsoftware.yamlbeans.YamlException;
import com.esotericsoftware.yamlbeans.YamlReader;
import com.esotericsoftware.yamlbeans.parser.Parser.ParserException;
import com.esotericsoftware.yamlbeans.tokenizer.Tokenizer.TokenizerException;
import dorkbox.Builder;
import dorkbox.build.SimpleArgs;
import dorkbox.util.FileUtil;
import dorkbox.util.OS;
import dorkbox.util.Sys;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

public class BuildParser {
    @SuppressWarnings("unchecked")
    public static HashMap<String, HashMap<String, Object>> parse(SimpleArgs args) {
        HashMap<String, HashMap<String, Object>> data = new HashMap<String, HashMap<String, Object>>();

        String buildDir = "build";
        String fileName = new File(buildDir, "build.oak").getPath();

        if (args.has("-file") || args.has("-f")) {
            fileName = args.getNext();
        }

        String normalizeAsFile = FileUtil.normalizeAsFile(fileName);

        final File file = new File(normalizeAsFile);
        if (!file.canRead()) {
            Builder.log().title("Build location").println("Build instructions not found", normalizeAsFile);
            return data;
        }

        Builder.log().title("Build location").println("Compiling build instructions", normalizeAsFile);


        // replace $dir$ with the parent dir, for use in parameters
        final File parentDir = file.getParentFile();


        BufferedReader fileReader = null;
        try {
            fileReader = new BufferedReader(new FileReader(file));
            StringBuilder buffer = new StringBuilder(2048);
            // we SPLIT each map by the "name" field (which MUST be the first entry for a build file)
            String currentEntry = null;
            String name = "name";
            while (true) {
                String line = fileReader.readLine();
                if (line == null) {
                    break;
                }
                line = line.trim();
                if (line.regionMatches(true, 0, name, 0, name.length())) {
                    if (currentEntry != null) {
                        HashMap<String, Object> parsed = parseYaml(parentDir, new StringReader(buffer.toString()));
                        data.put(currentEntry, parsed);
                        buffer.delete(0, buffer.length());
                    }
                    currentEntry = line.substring(line.indexOf(':')+1).trim();
                } else if (!line.isEmpty()) {
                    buffer.append(line);
                    buffer.append(OS.LINE_SEPARATOR);
                }
            }

            // parse the last entry
            HashMap<String, Object> parsed = parseYaml(parentDir, new StringReader(buffer.toString()));

            Object object = parsed.get("source");
            // always add these to as the default.
            Paths sourcePaths = new Paths(parentDir.getAbsolutePath(), "**/*.java");

            if (object == null) {
                object = new ArrayList<String>();
            } else if (object instanceof String) {
                String first = (String) object;
                ArrayList<String> list = new ArrayList<String>();
                list.add(first);
                object = list;
            }

            List<String> list = (List<String>) object;
            List<File> files = sourcePaths.getFiles();
            for (File f : files) {
                list.add(FileUtil.normalize(f).getAbsolutePath());
            }
            parsed.put("source", list);


            object = parsed.get("classpath");
            // always use these as the default. don't want the runtimes on our path
            Paths classPaths = new Paths(parentDir.getParentFile().getAbsolutePath() + File.separator + "libs", "**/*.jar", "!jdkRuntimes");

            if (object == null) {
                object = new ArrayList<String>();
            } else if (object instanceof String) {
                String first = (String) object;
                list = new ArrayList<String>();
                list.add(first);
                object = list;
            }

            list = (List<String>) object;
            files = classPaths.getFiles();
            for (File f : files) {
                list.add(FileUtil.normalize(f).getAbsolutePath());
            }
            parsed.put("classpath", list);


            data.put(currentEntry, parsed);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            Sys.close(fileReader);
        }

        return data;
    }

    /**
     * Parse the yaml for the specific reader
     */
    @SuppressWarnings("unchecked")
    private static HashMap<String, Object> parseYaml(final File parentFile, Reader fileReader) throws IOException {
        HashMap<String, Object> data = null;
        YamlReader yamlReader = new YamlReader(fileReader) {
            @SuppressWarnings("rawtypes")
            @Override
            protected Object readValue (Class type, Class elementType, Class defaultType) throws YamlException, ParserException,
                TokenizerException {

                Object value = super.readValue(type, elementType, defaultType);
                // replace $dir$ with the parent dir, for use in parameters
                if (value instanceof String) {
                    value = ((String)value).replaceAll("\\$dir\\$", parentFile.getAbsolutePath());
                }

                return value;
            }
        };

        try {
            data = yamlReader.read(HashMap.class);
            yamlReader.close();
            if (data == null) {
                return new HashMap<String, Object>(0);
            } else {
                return data;
            }
        } catch (YamlException ex) {
            throw new IOException("Error reading YAML file: " + parentFile, ex);
        }
    }

    public static Paths getPathsFromMap(HashMap<String, Object> map, String key) throws IOException {
        Paths paths = new Paths();
        if (map.containsKey(key)) {
            Object object = map.get(key);
            if (object instanceof String) {
                checkPath(paths, (String) object);
            } else if (object instanceof Collection) {
                @SuppressWarnings("rawtypes")
                Collection col = (Collection) object;
                for (Object c : col) {
                    checkPath(paths, (String) c);
                }
            } else {
                throw new IOException("Unknown source type: " + object.getClass().getSimpleName());
            }
        }

        return paths;
    }


    public static List<String> getStringsFromMap(HashMap<String, Object> map, String key) throws IOException {
        ArrayList<String> deps = new ArrayList<String>();
        if (map.containsKey(key)) {
            Object object = map.get(key);
            if (object instanceof String) {
                deps.add((String)object);
            } else if (object instanceof Collection) {
                @SuppressWarnings("rawtypes")
                Collection col = (Collection) object;
                for (Object c : col) {
                    deps.add((String)c);
                }
            } else {
                throw new IOException("Unknown source type: " + object.getClass().getSimpleName());
            }
        }

        return deps;
    }

    private static void checkPath(Paths paths, String c) throws IOException {
        File file = new File(c);
        if (file.canRead() || file.isDirectory()) {
            if (file.isFile()) {
                paths.add(file.getParent(), file.getName());
            } else {
                // it's a directory, so we should add all classes
                paths.glob(file.getAbsolutePath(), "**/*.class");
            }
        } else {
            // we use the full path info
            Paths testPaths = new Paths(".", c);
            if (testPaths.isEmpty()) {
                testPaths = new Paths(file.getParentFile().getAbsolutePath(), file.getName());

                if (testPaths.isEmpty()) {
                    throw new IOException("Location does not exist: " + c);
                }
            }

            Iterator<String> iterator = testPaths.iterator();
            while (iterator.hasNext()) {
                String next = FileUtil.normalizeAsFile(iterator.next());
                file = new File(next);
                if (!file.canRead()) {
                    throw new IOException("Location does not exist: " + next);
                }

                if (file.isFile()) {
                    paths.add(file.getParent(), file.getName());
                } else {
                    // it's a directory, so we should add all classes
                    paths.glob(next, "**/*.class");
                }
            }
        }
    }
}

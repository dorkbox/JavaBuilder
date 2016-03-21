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

import com.esotericsoftware.yamlbeans.YamlException;
import com.esotericsoftware.yamlbeans.YamlReader;
import com.esotericsoftware.yamlbeans.parser.Parser;
import com.esotericsoftware.yamlbeans.tokenizer.Tokenizer;
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

public class BuildParser {

    public static HashMap<String, ArrayList<String>> parse(SimpleArgs args) {
        final HashMap<String, ArrayList<String>> data = new HashMap<String, ArrayList<String>>();

        File buildFile = new File("build.oak");
        boolean specified = args.has("-file") || args.has("-f");
        if (specified) {
            buildFile = new File(args.getNext());
        }

        buildFile = FileUtil.normalize(buildFile);
        if (!buildFile.canRead()) {
            // autodetect
            if (!specified) {
                specified = true;
                // are we in root/lib dir?
                File dist = new File(buildFile.getParentFile(), "build");
                if (dist.isDirectory()) {
                    buildFile = new File(dist, "build.oak");
                    specified = false;
                }
                else {
                    dist = new File(buildFile.getParentFile(), "../build");
                    if (dist.isDirectory()) {
                        buildFile = new File(dist, "build.oak");
                        specified = false;
                    }
                }
            }

            if (specified) {
                BuildLog.title("Build location").println("Build instructions not found", buildFile.getAbsolutePath());
                BuildLog.println("You can specify the location via '-f <name>' or '-file <name>'");
                return null;
            }
        }

        BuildLog.title("Build location").println("Compiling build instructions", buildFile.getAbsolutePath());


        BufferedReader fileReader = null;
        try {
            fileReader = new BufferedReader(new FileReader(buildFile));
            StringBuilder buffer = new StringBuilder(2048);
            while (true) {
                String line = fileReader.readLine();
                if (line == null) {
                    break;
                }
                line = line.trim();
                if (!line.isEmpty()) {
                    buffer.append(line);
                    buffer.append(OS.LINE_SEPARATOR);
                }
            }

            ArrayList<String> list = new ArrayList<String>();

            // parse the build file
            HashMap<String, Object> parsed = parseBuildFile(buildFile, new StringReader(buffer.toString()));

            // save out the location of our build file
            list.add(buildFile.getAbsolutePath());
            data.put("buildFile", list);


            Object object = parsed.get("source");
            if (object instanceof String) {
                String first = (String) object;
                list = new ArrayList<String>();
                list.add(first);
            } else {
                list = new ArrayList<String>((Collection<? extends String>) object);
            }

            for (Iterator<String> iterator = list.iterator(); iterator.hasNext(); ) {
                final String s = iterator.next();
                if (s.isEmpty()) {
                    iterator.remove();
                }
            }

            data.put("source", list);


            object = parsed.get("classpath");
            if (object instanceof String) {
                String first = (String) object;
                list = new ArrayList<String>();
                list.add(first);
            } else {
                list = new ArrayList<String>((Collection<? extends String>) object);
            }

            for (Iterator<String> iterator = list.iterator(); iterator.hasNext(); ) {
                final String s = iterator.next();
                if (s.isEmpty()) {
                    iterator.remove();
                }
            }

            data.put("classpath", list);

            return data;
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            Sys.close(fileReader);
        }

        return null;
    }

    /**
     * Parse the yaml for the specific reader
     */
    private static
    HashMap<String, Object> parseBuildFile(final File file, final Reader fileReader) throws IOException {
        YamlReader yamlReader = new YamlReader(fileReader) {
            @Override
            protected
            Object readValue(Class type, Class elementType, Class defaultType) throws YamlException, Parser.ParserException,
                                                                                      Tokenizer.TokenizerException {
                Object value = super.readValue(type, elementType, defaultType);
                return value;
            }
        };

        try {
            HashMap<String, Object> data = yamlReader.read(HashMap.class);
            yamlReader.close();

            if (data == null) {
                return new HashMap<String, Object>(0);
            } else {
                return data;
            }
        } catch (YamlException ex) {
            throw new IOException("Error reading build file: " + file.getAbsolutePath(), ex);
        }
    }
}

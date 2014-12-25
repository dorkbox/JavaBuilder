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

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;

import com.esotericsoftware.wildcard.Paths;
import com.esotericsoftware.yamlbeans.YamlException;
import com.esotericsoftware.yamlbeans.YamlReader;
import com.esotericsoftware.yamlbeans.parser.Parser.ParserException;
import com.esotericsoftware.yamlbeans.tokenizer.Tokenizer.TokenizerException;

import dorkbox.build.SimpleArgs;
import dorkbox.util.FileUtil;
import dorkbox.util.Sys;

public class BuildParser {
    public static String fileName = "build.oak";

    @SuppressWarnings("unchecked")
    public static HashMap<String, Object> parse(SimpleArgs args) {
        String fileName = BuildParser.fileName;
        if (args.has("-file") || args.has("-f")) {
            fileName = args.getNext();
        }

        final File file = new File(FileUtil.normalizeAsFile(fileName));


        HashMap<String, Object> data = null;

        Reader fileReader = null;
        try {
            fileReader = new FileReader(file);
            YamlReader yamlReader = new YamlReader(fileReader) {
                @SuppressWarnings("rawtypes")
                @Override
                protected Object readValue (Class type, Class elementType, Class defaultType) throws YamlException, ParserException,
                    TokenizerException {

                    Object value = super.readValue(type, elementType, defaultType);
                    // replace $dir$ with the parent dir, for use in parameters
                    if (value instanceof String) {
                        value = ((String)value).replaceAll("\\$dir\\$", file.getParent());
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
                throw new IOException("Error reading YAML file: " + file, ex);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            Sys.close(fileReader);
        }

        return new HashMap<String, Object>(0);
    }

    public static Paths getPathsFromMap(HashMap<String, Object> map, String key) throws IOException {
        Paths sourcePaths = new Paths();
        if (map.containsKey(key)) {
            Object object = map.get(key);
            if (object instanceof String) {
                sourcePaths.glob(".", (String) object);
            } else if (object instanceof Collection) {
                @SuppressWarnings("rawtypes")
                Collection col = (Collection) object;
                for (Object c : col) {
                    // we use the full path info
                    Paths paths = new Paths(".", (String) c);
                    if (paths.isEmpty()) {
                        throw new IOException("Location does not exist: " + c);
                    } else {
                        Iterator<String> iterator = paths.iterator();
                        while (iterator.hasNext()) {
                            String next = FileUtil.normalizeAsFile(iterator.next());
                            File file = new File(next);
                            if (!file.canRead()) {
                                throw new IOException("Location does not exist: " + next);
                            }

                            sourcePaths.add(file.getParent(), file.getName());
                        }
                    }
                }
            } else {
                throw new IOException("Unknown source type: " + object.getClass().getSimpleName());
            }
        }

        return sourcePaths;
    }
}

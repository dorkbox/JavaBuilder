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
import dorkbox.license.License;
import dorkbox.license.LicenseType;

public class LicenseLibs {

    public static final License
    BouncyCastle = new License("BouncyCastle", LicenseType.MIT)
                             .u("http://www.bouncycastle.org")
                             .c("Copyright 2000-2009, The Legion Of The Bouncy Castle");

    public static final License
    FastMD5 = new License("FastMD5", LicenseType.LGPLv3)
                        .u("http://www.twmacinta.com/myjava/fast_md5.php")
                        .c("Copyright 1996, Santeri Paavolainen, Helsinki Finland")
                        .c("Many changes Copyright 2002 - 2010 Timothy W Macinta")
                        .n("Originally written by Santeri Paavolainen, Helsinki Finland 1996");

    public static final License
    JavaTar = new License("Javatar", LicenseType.PUBLIC)
                        .u("http://www.trustice.com/java/tar")
                        .c("Timothy Gerard Endres, time@gjt.org");

    public static final License
    LzmaJava = new License("LZMA-Java", LicenseType.APACHE)
                         .u("http://jponge.github.com/lzma-java")
                         .c("http://www.7-zip.org/sdk.html")
                         .c("Copyright 2014 Igor Pavlov")
                         .a("Julien Ponge (julien.ponge@gmail.com)");

    public static final License
    OpenJDK = new License("OpenJDK", LicenseType.GPLv2_CP)
                         .u("http://openjdk.java.net")
                         .u("https://github.com/alexkasko/openjdk-unofficial-builds")
                         .c("Copyright 2007, Sun Microsystems, Inc")
                         .n("http://www.gnu.org/software/classpath/license.html")
                         .n("  When GNU Classpath is used unmodified as the core class library for a virtual machine,")
                         .n("  compiler for the java language, or for a program written in the java programming language")
                         .n("  it does not affect the licensing for distributing those programs directly.");

    public static final License
    SLF4J = new License("SLF4J", LicenseType.MIT)
                      .u("http://www.slf4j.org/")
                      .c("Copyright 2004-2008, QOS.ch");

    public static final License
    WildCard = new License("Wildcard", LicenseType.BSD)
                         .u("https://github.com/EsotericSoftware/wildcard")
                         .c("Copyright 2008, Nathan Sweet");

    public static final License
    YamlBeans = new License("YamlBeans", LicenseType.BSD)
                         .u("https://github.com/EsotericSoftware/yamlbeans")
                         .c("Copyright 2006, Ola Bini")
                         .c("Copyright 2008, Nathan Sweet");

}

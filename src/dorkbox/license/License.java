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
package dorkbox.license;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import dorkbox.build.Project;
import dorkbox.util.LocationResolver;

public class License implements Comparable<License> {
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final String LINE_SEPARATOR = System.getProperty("line.separator");

    private static int maxLicenseFileSize = Integer.MAX_VALUE/16;

    public static List<License> list(Object... licenses) {
        List<License> list = new ArrayList<License>(licenses.length);
        for (Object l : licenses) {
            if (l instanceof License) {
                list.add((License) l);
            } else  if (l instanceof Collection) {
                List<License> list2 = convert((Collection<?>) l);
                list.addAll(list2);
            } else {
                throw new RuntimeException("Not a license! Whoops!");
            }
        }
        return list;
    }

    private static List<License> convert(Collection<?> collection) {
        List<License> list = new ArrayList<License>(collection.size());
        for (Object c : collection) {
            if (c instanceof License) {
                list.add((License) c);
            } else  if (c instanceof Collection) {
                List<License> list2 = convert((Collection<?>) c);
                list.addAll(list2);
            } else {
                throw new RuntimeException("Not a license! Whoops!");
            }
        }

        return list;
    }

    /**
     * Returns the LICENSE text file, as a combo of the listed licenses. Duplicates are removed.
     */
    public static String buildString(List<License> licenses) {
        StringBuilder b = new StringBuilder();


        // The FIRST one is always FIRST! (the rest are alphabetical)
        License firstLicense = licenses.remove(0);
        // remove dupes
        Set<License> dedupe = new HashSet<License>(licenses);
        licenses.add(0, firstLicense);

        licenses = new ArrayList<License>(dedupe);
        Collections.sort(licenses);
        licenses.add(0, firstLicense);


        String NL = LINE_SEPARATOR;
        String HEADER = "    - ";
        String SPACER = "      ";
        String SPACR1 = "       ";
        String SPACR2 = "        ";

        boolean first = true;

        for (License l : licenses) {
            if (first) {
                first = false;
            } else {
                b.append(NL).append(NL);
            }

            b.append(HEADER).append(l.name).append(" - ").append(l.type.getDescription()).append(NL);
            if (l.urls != null) {
                for (String s : l.urls) {
                    b.append(SPACER).append(s).append(NL);
                }
            }
            if (l.copyrights != null) {
                for (String s : l.copyrights) {
                    b.append(SPACER).append(s).append(NL);
                }
            }
            if (l.authors != null) {
                for (String s : l.authors) {
                    b.append(SPACR1).append(s).append(NL);
                }
            }
            if (l.notes != null) {
                for (String s : l.notes) {
                    b.append(SPACR2).append(s).append(NL);
                }
            }
        }

        return b.toString();
    }


    public static void install(File targetLocation) throws IOException {
        if (targetLocation == null) {
            throw new IllegalArgumentException("targetLocation cannot be null.");
        }

        // copy over full text licenses
        List<LicenseWrapper> licenseWrappers = License.getActualLicensesAsBytes(null);
        for (LicenseWrapper entry : licenseWrappers) {
            byte[] bytes = entry.bytes;

            File targetLicenseFile = new File(targetLocation, "LICENSE." + entry.license.getExtension());
            FileOutputStream fileOutputStream = new FileOutputStream(targetLicenseFile);
            copyStream(new ByteArrayInputStream(bytes), fileOutputStream);
            fileOutputStream.close();
        }
    }

    /**
     * Install the listed license files + full text licenses into the target directory.
     */
    public static void install(String targetLocation, List<License> licenses) throws IOException {
        if (targetLocation == null) {
            throw new IllegalArgumentException("targetLocation cannot be null.");
        }
        if (licenses == null || licenses.isEmpty()) {
            throw new IllegalArgumentException("licenses cannot be null or empty");
        }

        install(new File(targetLocation), licenses);
    }

    /**
     * Install the listed license files + full text licenses into the target directory.
     */
    public static void install(File targetLocation, List<License> licenses) throws IOException {
        if (targetLocation == null) {
            throw new IllegalArgumentException("targetLocation cannot be null.");
        }
        if (licenses == null || licenses.isEmpty()) {
            throw new IllegalArgumentException("licenses cannot be null or empty");
        }

        // remove all old license files
        File[] listFiles = targetLocation.listFiles();
        if (listFiles != null) {
            for (File f : listFiles) {
                if (f.isFile() && f.getName().startsWith("LICENSE.")) {
                    f.delete();
                }
            }
        }

        // create main license file
        String licenseFile = License.buildString(licenses);

        InputStream input = new ByteArrayInputStream(licenseFile.getBytes(UTF_8));
        OutputStream output = new FileOutputStream(new File(targetLocation, "LICENSE"));

        copyStream(input, output);
        output.close();

        // copy over full text licenses
        List<LicenseWrapper> licenseWrappers = License.getActualLicensesAsBytes(licenses);
        for (LicenseWrapper entry : licenseWrappers) {
            byte[] bytes = entry.bytes;

            File targetLicenseFile = new File(targetLocation, "LICENSE." + entry.license.getExtension());
            FileOutputStream fileOutputStream = new FileOutputStream(targetLicenseFile);
            copyStream(new ByteArrayInputStream(bytes), fileOutputStream);
            fileOutputStream.close();
        }
    }

    /**
     * Install the listed license files + full text licenses into the target zip file.
     * @param overrideDate
     */
    public static void install(ZipOutputStream zipOutputStream, List<License> licenses, long date) throws IOException {
        if (zipOutputStream == null) {
            throw new IllegalArgumentException("zipOutputStream cannot be null.");
        }
        if (licenses == null || licenses.isEmpty()) {
            throw new IllegalArgumentException("licenses cannot be null or empty");
        }

        if (date == -1) {
            date = System.currentTimeMillis();
        }

        String licenseFile = License.buildString(licenses);

        // WHAT IF LICENSE ALREADY EXISTS?!?!
        ZipEntry zipEntry = new ZipEntry("LICENSE");
        zipEntry.setTime(date);
        zipOutputStream.putNextEntry(zipEntry);

        ByteArrayInputStream input = new ByteArrayInputStream(licenseFile.getBytes(UTF_8));
        copyStream(input, zipOutputStream);
        zipOutputStream.closeEntry();

        // iterator is different every time...
        List<LicenseWrapper> licenseWrappers = License.getActualLicensesAsBytes(licenses);
        for (LicenseWrapper entry : licenseWrappers) {
            byte[] bytes = entry.bytes;

            zipEntry = new ZipEntry("LICENSE." + entry.license.getExtension());
            zipEntry.setTime(date);
            zipOutputStream.putNextEntry(zipEntry);

            zipOutputStream.write(bytes, 0, bytes.length);
            zipOutputStream.closeEntry();
        }
    }

    /**
     * @param licenses if NULL, then it returns ALL of the license types
     */
    private static List<LicenseWrapper> getActualLicensesAsBytes(List<License> licenses) throws IOException {
        // de-duplicate types
        Set<LicenseType> types = new HashSet<LicenseType>(0);
        if (licenses != null) {
            for (License l : licenses) {
                types.add(l.type);
            }
        } else {
            for (LicenseType lt : LicenseType.values()) {
                types.add(lt);
            }
        }

        List<LicenseWrapper> licenseList = new ArrayList<LicenseWrapper>(types.size());

        // look on disk, or look in a jar for the licenses.
        // Either way, we want the BYTES of those files!
        String rootPath = LocationResolver.get(License.class).getPath();
        File rootFile = new File(rootPath);
        String fileName = License.class.getCanonicalName();

        int maxLicenseFileSize = License.maxLicenseFileSize;

        // this is PRIMARILY when running in an IDE
        if (rootFile.isDirectory()) {
            String nameAsFile = fileName.replace('.', File.separatorChar).substring(0, fileName.lastIndexOf('.'));
            String location = rootFile.getParent();
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append(location).append(File.separator)
                         .append("src").append(File.separator)
                         .append(nameAsFile).append(File.separator)
                         .append("LICENSE.");

            String baseName = stringBuilder.toString();

            for (LicenseType lt : types) {
                String pathname = baseName + lt.getExtension();
                File f = new File(pathname);
                if (f.length() > maxLicenseFileSize) {
                    throw new RuntimeException("WTF are you doing?!?");
                }

                FileInputStream input = new FileInputStream(f);
                ByteArrayOutputStream output = new ByteArrayOutputStream((int) f.length());
                copyStream(input, output);
                input.close();

                licenseList.add(new LicenseWrapper(lt, output.toByteArray()));
            }
        } else if (rootPath.endsWith(Project.JAR_EXTENSION) && isZipFile(rootFile)) {
            // have to go digging for it!
            String nameAsFile = fileName.replace('.', '/').substring(0, fileName.lastIndexOf('.')+1);

            HashMap<String, LicenseType> licenseNames = new HashMap<String, LicenseType>(types.size());
            for (LicenseType l : types) {
                licenseNames.put(nameAsFile + "LICENSE." + l.getExtension(), l);
            }

            ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(rootFile));
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                String name = entry.getName();

                LicenseType licenseType = licenseNames.get(name);
                if (licenseType != null) {
                    // read out bytes!
                    ByteArrayOutputStream output = new ByteArrayOutputStream(4096);
                    copyStream(zipInputStream, output);

                    licenseList.add(new LicenseWrapper(licenseType, output.toByteArray()));
                    zipInputStream.closeEntry();
                }
            }
            zipInputStream.close();
        } else {
            throw new IOException("Don't know what this is, but - KAPOW_ON_getActualLicensesAsBytes");
        }

        Collections.sort(licenseList);

        return licenseList;
    }

    /**
     * Copy the contents of the input stream to the output stream.
     * <p>
     * DOES NOT CLOSE THE STEAMS!
     */
    private static <T extends OutputStream> T copyStream(InputStream inputStream, T outputStream) throws IOException {
        byte[] buffer = new byte[4096];
        int read = 0;
        while ((read = inputStream.read(buffer)) > 0) {
            outputStream.write(buffer, 0, read);
        }

        return outputStream;
    }

    /**
     * @return true if the file is a zip/jar file
     */
    private static boolean isZipFile(File file) {
        byte[] ZIP_HEADER = { 'P', 'K', 0x3, 0x4 };
        boolean isZip = true;
        byte[] buffer = new byte[ZIP_HEADER.length];

        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "r");
            raf.readFully(buffer);
            for (int i = 0; i < ZIP_HEADER.length; i++) {
                if (buffer[i] != ZIP_HEADER[i]) {
                    isZip = false;
                    break;
                }
            }
        } catch (Exception e) {
            isZip = false;
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return isZip;
    }

    public String name;
    public LicenseType type;

    public List<String> urls;
    private List<String> copyrights;
    private List<String> authors;
    private List<String> notes;

    public License(String licenseName, LicenseType licenseType) {
        this.name = licenseName;
        this.type = licenseType;
    }

    /** URL **/
    public License u(String url) {
        if (this.urls == null) {
            this.urls = new ArrayList<String>();
        }
        this.urls.add(url);
        return this;
    }

    /** COPYRIGHT **/
    public License c(String copyright) {
        if (this.copyrights == null) {
            this.copyrights = new ArrayList<String>();
        }
        this.copyrights.add(copyright);
        return this;
    }

    /** License_NOTE **/
    public License n(String note) {
        if (this.notes == null) {
            this.notes = new ArrayList<String>();
        }
        this.notes.add(note);
        return this;
    }

    /** AUTHOR **/
    public License a(String author) {
        if (this.authors == null) {
            this.authors = new ArrayList<String>();
        }
        this.authors.add(author);
        return this;
    }

    public License clear() {
        if (this.urls != null) {
            this.urls.clear();
        }
        if (this.copyrights != null) {
            this.copyrights.clear();
        }
        if (this.authors != null) {
            this.authors.clear();
        }
        if (this.notes != null) {
            this.notes.clear();
        }
        return this;
    }

    /**
     * ignore case when sorting these
     */
    @Override
    public int compareTo(License o) {
        return this.name.toLowerCase().compareTo(o.name.toLowerCase());
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (this.name == null ? 0 : this.name.hashCode());
        result = prime * result + (this.type == null ? 0 : this.type.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        License other = (License) obj;
        if (this.name == null) {
            if (other.name != null) {
                return false;
            }
        } else if (!this.name.equals(other.name)) {
            return false;
        }
        if (this.type != other.type) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return this.name;
    }
}

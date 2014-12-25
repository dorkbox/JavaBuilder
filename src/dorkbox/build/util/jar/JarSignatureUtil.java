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
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Map;
import java.util.Map.Entry;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.ASN1TaggedObject;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.cms.ContentInfo;
import org.bouncycastle.asn1.cms.SignedData;

import dorkbox.util.Base64Fast;
import dorkbox.util.OS;
import dorkbox.util.Sys;

public class JarSignatureUtil {
    /**
     * a small helper function that will convert a manifest into an array of
     * bytes
     */
    public static final byte[] serialiseManifest(Manifest manifest) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        manifest.write(baos);
        baos.flush();
        baos.close();
        return baos.toByteArray();

    }

    /**
     * update the attributes in the manifest to have the appropriate message
     * digests. we store the new entries into the entries Map and return it (we
     * do not compute the digests for those entries in the META-INF directory)
     */
    public static final Map<String, Attributes> updateManifestHashes(Manifest manifest, JarFile jarFile, MessageDigest messageDigest) throws IOException {
        Map<String, Attributes> entries = manifest.getEntries();
        Enumeration<JarEntry> jarElements = jarFile.entries();
        String digestName = messageDigest.getAlgorithm() + "-Digest";

        while (jarElements.hasMoreElements()) {
            JarEntry jarEntry = jarElements.nextElement();
            String name = jarEntry.getName();

            if (name.startsWith(JarUtil.metaInfName)) {
                continue;
            } else if (!jarEntry.isDirectory()) {
                // store away the digest into a new Attribute
                // because we don't already have an attribute list
                // for this entry. we do not store attributes for
                // directories within the JAR
                Attributes attributes = new Attributes();
                // attributes.putValue("Name", name); NOT NECESSARY!
                InputStream inputStream = jarFile.getInputStream(jarEntry);
                attributes.putValue(digestName, JarUtil.updateDigest(inputStream, messageDigest));
                Sys.close(inputStream);

                entries.put(name, attributes);
            }
        }

        return entries;
    }

    /**
     * @return null if there is a problem with the certificate loading process.
     */
    public static final String extractSignatureHashFromSignatureBlock(byte[] signatureBlock) {
        ASN1InputStream sigStream = null;
        try {
            CertificateFactory certFactory = CertificateFactory.getInstance("X.509");

            InputStream signatureIn = new ByteArrayInputStream(signatureBlock);
            sigStream = new ASN1InputStream(signatureIn);
            ASN1Primitive signatureASN = sigStream.readObject();
            ASN1Sequence seq = ASN1Sequence.getInstance(signatureASN);
            ASN1TaggedObject tagged = (ASN1TaggedObject) seq.getObjectAt(1);

            // Extract certificates
            SignedData newSignedData = SignedData.getInstance(tagged.getObject());

            @SuppressWarnings("rawtypes")
            Enumeration newSigOjects = newSignedData.getCertificates().getObjects();
            Object newSigElement = newSigOjects.nextElement();

            if (newSigElement instanceof DERSequence) {
                DERSequence newSigDERElement = (DERSequence) newSigElement;
                InputStream newSigIn = new ByteArrayInputStream(newSigDERElement.getEncoded());
                Certificate newSigCertificate = certFactory.generateCertificate(newSigIn);

                // certificate bytes
                byte[] newSigCertificateBytes = newSigCertificate.getEncoded();
                String encodeToString = Base64Fast.encodeToString(newSigCertificateBytes, false);
                return encodeToString;
            }
        } catch (IOException e) {} catch (CertificateException e) {}
        finally {
            Sys.close(sigStream);
        }
        return null;
    }

    /**
     * Verify that the two certificates MATCH from within a signature block (ie,
     * XXXXX.DSA in the META-INF directory).
     *
     * @return true if the two certificates are the same. false otherwise.
     */
    public static final boolean compareCertificates(byte[] newSignatureContainerBytes, byte[] oldSignatureContainerBytes) {
        ASN1InputStream newSigStream = null;
        ASN1InputStream oldSigStream = null;
        try {
            CertificateFactory certFactory = CertificateFactory.getInstance("X.509");

            InputStream newSignatureIn = new ByteArrayInputStream(newSignatureContainerBytes);
            newSigStream = new ASN1InputStream(newSignatureIn);
            ASN1Primitive newSigASNPrim = newSigStream.readObject();
            ContentInfo newSigContent = ContentInfo.getInstance(newSigASNPrim);

            InputStream oldSignatureIn = new ByteArrayInputStream(oldSignatureContainerBytes);
            oldSigStream = new ASN1InputStream(oldSignatureIn);
            ASN1Primitive oldSigASNPrim = oldSigStream.readObject();
            ContentInfo oldSigContent = ContentInfo.getInstance(oldSigASNPrim);

            // Extract certificates
            SignedData newSignedData = SignedData.getInstance(newSigContent.getContent());
            @SuppressWarnings("rawtypes")
            Enumeration newSigOjects = newSignedData.getCertificates().getObjects();

            SignedData oldSignedData = SignedData.getInstance(oldSigContent.getContent());
            @SuppressWarnings("rawtypes")
            Enumeration oldSigOjects = oldSignedData.getCertificates().getObjects();

            Object newSigElement = newSigOjects.nextElement();
            Object oldSigElement = oldSigOjects.nextElement();

            if (newSigElement instanceof DERSequence && oldSigElement instanceof DERSequence) {
                DERSequence newSigDERElement = (DERSequence) newSigElement;
                InputStream newSigIn = new ByteArrayInputStream(newSigDERElement.getEncoded());
                Certificate newSigCertificate = certFactory.generateCertificate(newSigIn);

                DERSequence oldSigDERElement = (DERSequence) oldSigElement;
                InputStream oldSigIn = new ByteArrayInputStream(oldSigDERElement.getEncoded());
                Certificate oldSigCertificate = certFactory.generateCertificate(oldSigIn);

                // certificate bytes
                byte[] newSigCertificateBytes = newSigCertificate.getEncoded();
                byte[] oldSigCertificateBytes = oldSigCertificate.getEncoded();

                return Arrays.equals(newSigCertificateBytes, oldSigCertificateBytes);
            }
        } catch (IOException e) {} catch (CertificateException e) {}
        finally {
            Sys.close(newSigStream);
            Sys.close(oldSigStream);
        }

        return false;
    }

    /**
     * Creates a NEW signature file manifest based on the supplied message
     * digest and manifest.
     */
    @SuppressWarnings("deprecation")
    public static final Manifest createSignatureFileManifest(MessageDigest messageDigest, Manifest manifest, byte[] manifestBytes) throws IOException, SecurityException, NoSuchMethodException, IllegalArgumentException, IllegalAccessException, InvocationTargetException {

        String messageDigestTitle = messageDigest.getAlgorithm() + "-Digest";

        // create the new manifest signature (.SF)
        Manifest signatureManifest = new Manifest();

        Attributes signatureMainAttributes = signatureManifest.getMainAttributes();
        signatureMainAttributes.putValue(Attributes.Name.SIGNATURE_VERSION.toString(), "1.0");

        String version = System.getProperty("java.version");
        String javaVendor = System.getProperty("java.vendor");
        signatureMainAttributes.putValue("Created-By", version + " (" + javaVendor + ")");

        // SIGN THE WHOLE MANIFEST
        messageDigest.reset();
        messageDigest.update(manifestBytes, 0, manifestBytes.length);

        /*
         * Do not insert a default newline at the end of the output line, as
         * java.util.jar does its own line management (see
         * Manifest.make72Safe()). Inserting additional new lines will cause
         * line-wrapping problems.
         */
        String entireManifestHash = Base64Fast.encodeToString(messageDigest.digest(), false);
        signatureMainAttributes.putValue(messageDigestTitle + "-Manifest", entireManifestHash);
        // System.err.println("ENCODED ALL : " + entireManifestHash);

        // //////////////////////////
        // Instead of reverse engineering the BYTES, we'll just read the
        // manifest again and encode on the fly.
        // //////////////////////////
        ByteArrayOutputStream manifestStream = new ByteArrayOutputStream();

        Method writeMainMethod = Attributes.class.getDeclaredMethod("writeMain",
                                                                    new Class<?>[] {DataOutputStream.class});
        writeMainMethod.setAccessible(true);

        // MAIN ATTRIBUTES
        DataOutputStream dataOutputStream = new DataOutputStream(manifestStream);
        // Write out the main attributes for the manifest
        writeMainMethod.invoke(manifest.getMainAttributes(), dataOutputStream);
        dataOutputStream.flush();
        manifestStream.flush();

        // HASH the contents of the main attributes (WHICH ARE ALWAYS FIRST!)
        byte[] mainAttributesByteArray = manifestStream.toByteArray();
        messageDigest.reset();
        messageDigest.update(mainAttributesByteArray, 0, mainAttributesByteArray.length);

        /*
         * Do not insert a default newline at the end of the output line, as
         * java.util.jar does its own line management (see
         * Manifest.make72Safe()). Inserting additional new lines will cause
         * line-wrapping problems.
         */
        String mainAttribsManifestHash = Base64Fast.encodeToString(messageDigest.digest(), false);
        if (mainAttribsManifestHash != null) {
            signatureMainAttributes.putValue(messageDigestTitle + "-Manifest-Main-Attributes", mainAttribsManifestHash);
            // System.err.println("ENCODED main: " + mainAttribsManifestHash);
        } else {
            throw new RuntimeException("Unable to create manifest-main-attribute signature");
        }

        // PER-ENTRY ATTRIBUTES
        Method writeMethod = Attributes.class.getDeclaredMethod("write", new Class<?>[] {DataOutputStream.class});
        writeMethod.setAccessible(true);

        Method make72Method = Manifest.class.getDeclaredMethod("make72Safe", new Class<?>[] {StringBuffer.class});
        make72Method.setAccessible(true);

        Map<String, Attributes> entries = manifest.getEntries();
        Map<String, Attributes> signatureEntries = signatureManifest.getEntries();

        for (Entry<String, Attributes> e : entries.entrySet()) {
            manifestStream.reset();
            dataOutputStream = new DataOutputStream(manifestStream);

            // has to be string buffer.
            StringBuffer buffer = new StringBuffer("Name: ");
            String entryName = e.getKey();

            if (entryName != null) {
                byte[] vb = entryName.getBytes(OS.UTF_8); // by doing this, the following new string
                                                          // will be safe (UTF-8) despite warnings
                entryName = new String(vb, 0, 0, vb.length);
            }
            buffer.append(entryName);
            buffer.append("\r\n"); // must be this because of stupid windows...
            make72Method.invoke(null, buffer);
            dataOutputStream.writeBytes(buffer.toString());

            // Write out the attributes for the manifest
            writeMethod.invoke(e.getValue(), dataOutputStream);
            dataOutputStream.flush();
            manifestStream.flush();

            // HASH the contents of the attributes
            byte[] attributesByteArray = manifestStream.toByteArray();
            messageDigest.reset();
            messageDigest.update(attributesByteArray, 0, attributesByteArray.length);

            /*
             * Do not insert a default newline at the end of the output line, as
             * java.util.jar does its own line management (see
             * Manifest.make72Safe()). Inserting additional new lines will cause
             * line-wrapping problems.
             */
            String entryHash = Base64Fast.encodeToString(messageDigest.digest(), false);

            Attributes attribute = new Attributes();
            attribute.putValue(messageDigestTitle, entryHash);
            signatureEntries.put(entryName, attribute);

            // System.err.println("ENCODED " + entryName + " : " + entryHash);
        }

        return signatureManifest;
    }

}

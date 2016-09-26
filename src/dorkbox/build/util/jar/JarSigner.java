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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Calendar;
import java.util.Date;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.DSAParameter;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.params.DSAKeyParameters;
import org.bouncycastle.crypto.params.DSAParameters;
import org.bouncycastle.crypto.params.DSAPrivateKeyParameters;
import org.bouncycastle.crypto.params.DSAPublicKeyParameters;
import org.bouncycastle.crypto.util.PrivateKeyFactory;
import org.bouncycastle.crypto.util.PublicKeyFactory;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import dorkbox.build.util.BuildLog;
import dorkbox.util.Base64Fast;
import dorkbox.util.IO;
import dorkbox.util.crypto.CryptoDSA;
import dorkbox.util.crypto.CryptoX509;

public final
class JarSigner {

    static {
        BouncyCastleProvider provider = new BouncyCastleProvider();
        Security.addProvider(provider);
    }

    private
    JarSigner() {
    }

    public static
    File sign(String jarName, String name) {

        BuildLog.println();
        BuildLog.title("Signing JAR")
                .println(jarName, name.toUpperCase());

        if (jarName == null) {
            throw new IllegalArgumentException("jarName cannot be null.");
        }

        try {
            File jarFile = new File(jarName);
            ByteArrayOutputStream signJarFile;

            if (jarFile.isFile() && jarFile.canRead()) {
                signJarFile = signJar(jarFile, name);
            }
            else {
                throw new RuntimeException("Unable to read file: " + jarFile.getCanonicalPath());
            }

            // write out the file
            OutputStream outputStream = new FileOutputStream(jarFile);
            signJarFile.writeTo(outputStream);
            IO.close(outputStream);

            return new File(jarName);
        } catch (Throwable ex) {
            throw new RuntimeException("Unable to sign jar file! " + ex.getMessage());
        }
    }

    /**
     * the actual JAR signing method
     */
    private static
    ByteArrayOutputStream signJar(File jarFile, String name)
                    throws IOException, IllegalAccessException, InvocationTargetException, NoSuchMethodException, GeneralSecurityException {

        // proper "jar signing" does not allow for ECC signatures to be used. RSA/DSA and that's it.
        // so this "self signed" cert is just that. wimpy.
        // the magic is in the uber-strong ECC key that is used internally, and also has AES keys mixed in.
        DSAKeyParameters[] wimpyKeys = getWimpyKeys();
        DSAPublicKeyParameters wimpyPublicKey = (DSAPublicKeyParameters) wimpyKeys[0];
        DSAPrivateKeyParameters wimpyPrivateKey = (DSAPrivateKeyParameters) wimpyKeys[1];


        // create the certificate
        Calendar expiry = Calendar.getInstance();
        expiry.add(Calendar.YEAR, 2);

        Date startDate = new Date();              // time from which certificate is valid
        Date expiryDate = expiry.getTime();       // time after which certificate is not valid
        BigInteger serialNumber = BigInteger.valueOf(System.currentTimeMillis());     // serial number for certificate



        X509CertificateHolder wimpyX509CertificateHolder = CryptoX509.DSA.createCertHolder(startDate,
                                                                                           expiryDate,
                                                                                           new X500Name("ST=Lunar Base Alpha, O=Dorkbox, CN=Dorkbox Server, emailaddress=admin@dorkbox.com"),
                                                                                           new X500Name("ST=Earth, O=Dorkbox, CN=Dorkbox Client, emailaddress=admin@dorkbox.com"),
                                                                                           serialNumber,
                                                                                           wimpyPrivateKey,
                                                                                           wimpyPublicKey);

        JarFile jar = new JarFile(jarFile.getCanonicalPath());

        // UNFORTUNATELY, with java6, we CANNOT do anything higher. As such, a CUSTOM signing tool will be developed,
        // which the launcher will verify on it's own.
        // FORTUNATELY, this is will produce the exact same output as if using the command line.
        String digestName = CryptoX509.Util.getDigestNameFromCert(wimpyX509CertificateHolder);
        MessageDigest messageDigest = MessageDigest.getInstance(digestName);

        // get the manifest out of the jar.
        Manifest manifest = JarUtil.getManifestFile(jar);

        // it ONLY exists if it's an "executable" jar
        if (manifest == null) {
            manifest = new Manifest();

            // have to add basic entries.
            Attributes mainAttributes = manifest.getMainAttributes();
            mainAttributes.putValue(Attributes.Name.MANIFEST_VERSION.toString(), "1.0");
        }
        else {
            // clear out all entries in the manifest
            Map<String, Attributes> entries = manifest.getEntries();
            if (entries.size() > 0) {
                entries.clear();
            }
        }


        // create the message digest and start updating the
        // the attributes in the manifest to contain the SHA digests
        JarSignatureUtil.updateManifestHashes(manifest, jar, messageDigest);

        byte[] manifestBytes = JarSignatureUtil.serialiseManifest(manifest);


        // create a NEW signature file manifest based on the supplied message digest and manifest.
        Manifest signatureFileManifest = JarSignatureUtil.createSignatureFileManifest(messageDigest, manifest, manifestBytes);
        byte[] signatureFileManifestBytes = JarSignatureUtil.serialiseManifest(signatureFileManifest);


        byte[] signatureBlockBytes = CryptoX509.createSignature(signatureFileManifestBytes, wimpyX509CertificateHolder, wimpyPrivateKey);

        ByteArrayOutputStream byteArrayOutputStream = JarUtil.createNewJar(jar,
                                                                           name,
                                                                           manifestBytes,
                                                                           signatureFileManifestBytes,
                                                                           signatureBlockBytes);

        // close the JAR file that we have been using
        jar.close();
        return byteArrayOutputStream;
    }


    public static
    DSAKeyParameters[] getWimpyKeys() throws IOException {
        String wimpyKeyName = "wimpyCert.key";

        DSAPrivateKeyParameters wimpyPrivateKey = null;
        DSAPublicKeyParameters wimpyPublicKey = null;

        File wimpyKeyRawFile = new File(wimpyKeyName);

        // do we need to create the (wimpy) certificate keys?
        if (!wimpyKeyRawFile.canRead()) {
            // using DSA, since that is compatible with ALL java versions
            @SuppressWarnings("deprecation")
            AsymmetricCipherKeyPair generateKeyPair = CryptoDSA.generateKeyPair(new SecureRandom(), 8192);
            wimpyPrivateKey = (DSAPrivateKeyParameters) generateKeyPair.getPrivate();
            wimpyPublicKey = (DSAPublicKeyParameters) generateKeyPair.getPublic();

            writeDsaKeysToFile(wimpyPrivateKey, wimpyPublicKey, wimpyKeyRawFile);
        }
        else {
            FileInputStream inputStream = new FileInputStream(wimpyKeyRawFile);
            long fileSize = inputStream.getChannel()
                                       .size();

            // check file size.
            if (fileSize > Integer.MAX_VALUE - 1) {
                System.err.println("Corrupt wimpyKeyFile! " + wimpyKeyRawFile.getAbsolutePath() + " Creating a new one.");

                // using DSA, since that is compatible with ALL java versions
                @SuppressWarnings("deprecation")
                AsymmetricCipherKeyPair generateKeyPair = CryptoDSA.generateKeyPair(new SecureRandom(), 8192);
                wimpyPrivateKey = (DSAPrivateKeyParameters) generateKeyPair.getPrivate();
                wimpyPublicKey = (DSAPublicKeyParameters) generateKeyPair.getPublic();

                writeDsaKeysToFile(wimpyPrivateKey, wimpyPublicKey, wimpyKeyRawFile);
            }
            else {
                // read in the entire file as bytes.
                int fileSizeAsInt = (int) fileSize;

                byte[] inputBytes = new byte[fileSizeAsInt];
                inputStream.read(inputBytes, 0, fileSizeAsInt);
                IO.close(inputStream);

                // read public key length
                int wimpyPublicKeyLength = (inputBytes[fileSizeAsInt - 4] & 0xff) << 24 |
                                           (inputBytes[fileSizeAsInt - 3] & 0xff) << 16 |
                                           (inputBytes[fileSizeAsInt - 2] & 0xff) << 8 |
                                           (inputBytes[fileSizeAsInt - 1] & 0xff) << 0;


                byte[] publicKeyBytes = new byte[wimpyPublicKeyLength];
                byte[] privateKeyBytes = new byte[fileSizeAsInt - 4 - wimpyPublicKeyLength];

                System.arraycopy(inputBytes, 0, publicKeyBytes, 0, publicKeyBytes.length);
                System.arraycopy(inputBytes, publicKeyBytes.length, privateKeyBytes, 0, privateKeyBytes.length);

                displayByteHash(publicKeyBytes);

                wimpyPublicKey = (DSAPublicKeyParameters) PublicKeyFactory.createKey(publicKeyBytes);
                wimpyPrivateKey = (DSAPrivateKeyParameters) PrivateKeyFactory.createKey(privateKeyBytes);
            }
        }

        return new DSAKeyParameters[] {wimpyPublicKey, wimpyPrivateKey};
    }

    private static
    void writeDsaKeysToFile(DSAPrivateKeyParameters wimpyPrivateKey, DSAPublicKeyParameters wimpyPublicKey, File wimpyKeyRawFile)
                    throws IOException {

        DSAParameters parameters = wimpyPublicKey.getParameters(); // has to convert to DSAParameter so encoding works.
        byte[] publicKeyBytes = new SubjectPublicKeyInfo(new AlgorithmIdentifier(X9ObjectIdentifiers.id_dsa,
                                                                                 new DSAParameter(parameters.getP(),
                                                                                                  parameters.getQ(),
                                                                                                  parameters.getG()).toASN1Primitive()),
                                                         new ASN1Integer(wimpyPublicKey.getY())).getEncoded();
        // SAME AS:
        //        Certificate[] certificates = Launcher.class.getProtectionDomain().getCodeSource().getCertificates();
        //        if (certificates.length != 1) {
        //            // WHOOPS!
        //            Exit.FailedSecurity("Incorrect certificate length!");
        //        }
        //
        //        Certificate certificate = certificates[0];
        //        PublicKey publicKey = certificate.getPublicKey();
        //        byte[] publicKeyBytes = publicKey.getEncoded();
        //
        //        digest.reset();
        //        digest.update(publicKeyBytes, 0, publicKeyBytes.length);
        //        hashPublicKeyBytes = digest.digest();


        parameters = wimpyPrivateKey.getParameters();
        byte[] privateKeyBytes = new PrivateKeyInfo(new AlgorithmIdentifier(X9ObjectIdentifiers.id_dsa, new DSAParameter(parameters.getP(),
                                                                                                                         parameters.getQ(),
                                                                                                                         parameters.getG()).toASN1Primitive()),
                                                    new ASN1Integer(wimpyPrivateKey.getX())).getEncoded();

        // write public length to bytes.
        byte[] publicKeySize = new byte[] {(byte) (publicKeyBytes.length >>> 24), (byte) (publicKeyBytes.length >>> 16),
                                           (byte) (publicKeyBytes.length >>> 8), (byte) (publicKeyBytes.length >>> 0)};

        ByteArrayOutputStream keyOutputStream = new ByteArrayOutputStream(4 + publicKeyBytes.length + privateKeyBytes.length);

        keyOutputStream.write(publicKeyBytes, 0, publicKeyBytes.length);
        keyOutputStream.write(privateKeyBytes, 0, privateKeyBytes.length);
        keyOutputStream.write(publicKeySize, 0, publicKeySize.length); // mess with people staring at the keys (store length at the end).

        displayByteHash(publicKeyBytes);

        // write out the file
        OutputStream outputStream = new FileOutputStream(wimpyKeyRawFile);
        keyOutputStream.writeTo(outputStream);
        IO.close(outputStream);
    }

    private static
    void displayByteHash(byte[] publicKeyBytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            digest.reset();
            digest.update(publicKeyBytes, 0, publicKeyBytes.length);

            String digestString = Base64Fast.encodeToString(digest.digest(), false);

            String origDigestHash = "9f5LkG90ITAMR37xxbXGXAGyaGkZL1dP7FzU8y/CL8gskIxegZTRbOn0g3ks/eCJ5jSKTX4eVZCPmA0TKj7zlw==";
            if (!digestString.equals(origDigestHash)) {
                System.err.println("Wimpy public key bytes. Need to modify " + JarSigner.class.getSimpleName() + " and in the Launcher");
                System.err.println(digestString);
            }

        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
    }
}

OAK
===

Dorkbox OAK is a Java project management and build tool, using the Java language.

The main distinction between this tool and others, such as Ant, Maven, Ivy, Gradle, etc. is that
*this* build tool lets you work directly in Java. Since you are building a Java project, it can 
safely be assumed that you already know Java, why use XML, Groovy, or some other language to build
your project?

- This is for cross-platform use, specifically - linux 32/64, mac 32/64, and windows 32/64. Java 6+


For example:

 - See Build.java to see an example
 
OR, for a "real world" example

 - See the build code in the "build" directory, which is the build code used to build OAK  
   - If you want to compile it yourself, copy the entire contents of the libs directory into the dist directory.  
   - Then (from the project root), java -jar dist/OAK.jar build oak  
   (note: make sure that you have the java JDK installed!)
   


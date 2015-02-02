JavaBuilder
===========

The JavaBuilder project is a Java project management and build tool, using the Java language.

The main distinction between this tool and others, such as Ant, Maven, Ivy, Gradle, etc. is that
*this* build tool lets you work directly in Java. Since you are building a Java project, it can 
safely be assumed that you already know Java. Additionally, because it's running *compiled code*
(instead of interpreting or parsing lengthy config files) and because it uses the FastMD5 project
for dependency checking and build verification, it's *wicked fast*.

Why use XML, Groovy, Scala, JavaScript, or some other language to build your project? Just use Java!

- This is for cross-platform use, specifically - linux 32/64, mac 32/64, and windows 32/64. Java 6+


For example:

 - See Build.java
 
For a real world example:

 - See the build code in the "build" directory, which is the build code used to build itself.    
   - If you want to compile it yourself, copy the entire contents of the libs directory into the dist directory.  
   - Then (from the project root), java -jar dist/JavaBuilder.jar build javabuilder dist  
  
   
An example of program log output is available here: https://github.com/dorkbox/JavaBuilder/wiki  
   
```
Note: This project was inspired (and some parts heavily modified) by the excellent 
      Scar project, and includes utility classes/methods from a variety of sources.
```

JavaBuilder
===========

The JavaBuilder project is a Java project management and build tool, using the Java language.

The main distinction between this tool and others, such as Ant, Maven, Ivy, Gradle, etc. is that
*this* build tool lets you work directly in Java. Since you are building a Java project, it can 
safely be assumed that you already know Java. Additionally, because it's running *compiled code*
(instead of interpreting or parsing lengthy config files) it's *wicked fast*.

#####  Why use XML, Groovy, Scala, JavaScript, or some other language to build your project? 

- This is for cross-platform use, specifically - linux 32/64, mac 32/64, and windows 32/64. Java 6+
- Ability to reproduce builds *exactly* the same (provided the same 'builddate') as a remote build
- This also permits the use of **cross-compiling** *projects* or even specific *classes* in a project (for example, when a project is compiled to java6, but has a single file that is code specific to java7+)

For example:

 - See [Build.java](https://github.com/dorkbox/JavaBuilder/blob/master/src/dorkbox/Build.java)
 
For a real world example:

 - See the build code in the "build" directory, which is the build code used to build itself.    
   - If you want to compile it yourself, copy the entire contents of the libs directory into the dist directory.  
   - Then (from the project root), java -jar dist/JavaBuilder_v1.23.jar build JavaBuilder dist
  
   
An example of program log output is available here: https://github.com/dorkbox/JavaBuilder/wiki  
 
   
```
Please see the commit log for a comprehensive changelog
```
```
Note: This project was inspired (and some parts heavily modified) by the excellent 
      Scar project, and includes utility classes/methods from a variety of sources.
```

License
---------
This project is © 2012 dorkbox llc, and is distributed under the terms of the Apache v2.0 License. See file "LICENSE" for further references.

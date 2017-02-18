/*
 * Copyright 2015 dorkbox, llc
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
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Set;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

public
class DependencyWalker {
    private static
    String getSourceLocation(final String sourceFile, final String packageName) {
        int rootIndex = sourceFile.indexOf(packageName);
        if (rootIndex == -1) {
            throw new RuntimeException("Something really weird happened...");
        }

        return sourceFile.substring(0, rootIndex);
    }

    private static
    void addIfValidType(final String rootSource, final String packageSource, final Type type, final Set<String> dependencies) {
        type.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(ClassOrInterfaceType n, Void arg) {
                String name = n.getNameAsString()
                               .replace('.', '/') + ".java";

                addIfValid(rootSource, packageSource, name, dependencies);
                super.visit(n, arg);
            }
        }, null);
    }

    private static
    void addIfValid(final String rootSource, final String packageSource, final String name, final Set<String> dependencies) {
        if (name.indexOf('/') > 0) {
            // this means we base our check on the ROOT
            File file = new File(rootSource, name);
            if (file.exists()) {
                dependencies.add(name);
                collect(file, dependencies);
            }
        }
        else {
            // we base our check on the current package
            File file = new File(packageSource, name);
            if (file.exists()) {
                String newName = file.getAbsolutePath();
                dependencies.add(newName);
                collect(file, dependencies);
            }
        }
    }

    private static
    String importSource(final String rootSource, final String packageSource, final ImportDeclaration anImport) {
        String name = anImport.getNameAsString()
                              .replace('.', '/');

        if (name.indexOf('/') > 0) {
            // this means we base our check on the ROOT
            File file = new File(rootSource, name);
            if (file.exists()) {
                return name;
            }
        }
        else {
            // we base our check on the current package
            File file = new File(packageSource, name);
            if (file.exists()) {
                return packageSource + '/' + name;
            }
        }

        return null;
    }


    public static
    String collect(final File sourceFile, final Set<String> dependencies) {

        String relativeNameNoExtension = null;
        FileInputStream in = null;
        try {
            in = new FileInputStream(sourceFile);

            CompilationUnit cu = JavaParser.parse(in);

            String packageName = cu.getPackageDeclaration()
                                   .get()
                                   .getNameAsString()
                                   .replace('.', '/');
            String rootSource = getSourceLocation(sourceFile.getAbsolutePath(), packageName);
            String packageSource = new File(rootSource, packageName).getAbsolutePath();

            relativeNameNoExtension = new File(packageName,
                                               cu.getTypes()
                                                 .get(0)
                                                 .getNameAsString()).getPath();

            // check all imports
            List<ImportDeclaration> imports = cu.getImports();
            for (ImportDeclaration anImport : imports) {
                String importSource = importSource(rootSource, packageSource, anImport);
                if (importSource != null) {
                    dependencies.add(importSource);
                    collect(new File(rootSource, importSource), dependencies);
                }
            }

            // check everything else
            cu.accept(new VoidVisitorAdapter<Void>() {
                @Override
                public void visit(FieldDeclaration n, Void arg) {
                    for (VariableDeclarator v : n.getVariables()) {
                        Type type1 = v.getType();
                        addIfValidType(rootSource, packageSource, type1, dependencies);
                    }
                }

                @Override
                public void visit(AnnotationDeclaration n, Void arg) {
                    List<AnnotationExpr> annotations = n.getAnnotations();
                    for (AnnotationExpr annotation : annotations) {
                        String name = annotation.getNameAsString()
                                                .replace('.', '/') + ".java";
                        addIfValid(rootSource, packageSource, name, dependencies);
                    }
                }

                @Override
                public void visit(MethodDeclaration n, Void arg) {
                    // either the these will be in a different package (and our import check will get it), or it will be in the same package
                    List<Parameter> parameters = n.getParameters();
                    for (Parameter parameter : parameters) {
                        Type type1 = parameter.getType();
                        addIfValidType(rootSource, packageSource, type1, dependencies);
                    }


                    Type type1 = n.getType();
                    addIfValidType(rootSource, packageSource, type1, dependencies);

                    List<AnnotationExpr> annotations = n.getAnnotations();
                    for (AnnotationExpr annotation : annotations) {
                        String name = annotation.getNameAsString()
                                                .replace('.', '/') + ".java";
                        addIfValid(rootSource, packageSource, name, dependencies);
                    }
                }

                @Override
                public void visit(ClassOrInterfaceType n, Void arg) {
                    String name = n.getNameAsString()
                                   .replace('.', '/') + ".java";
                    addIfValid(rootSource, packageSource, name, dependencies);
                }
            }, null);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        return relativeNameNoExtension;
    }
}

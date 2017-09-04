/**********************************************************************
Copyright (c) 2010 Andy Jefferson and others. All rights reserved.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

Contributors:
   ...
**********************************************************************/
package org.datanucleus.jpa.query;

import java.io.IOException;
import java.io.Writer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.persistence.AccessType;
import javax.persistence.Embeddable;
import javax.persistence.Entity;
import javax.persistence.ManyToMany;
import javax.persistence.ManyToOne;
import javax.persistence.MappedSuperclass;
import javax.persistence.OneToMany;
import javax.persistence.OneToOne;
import javax.persistence.Transient;
import javax.tools.JavaFileObject;

import org.datanucleus.jpa.query.AnnotationProcessorUtils.TypeCategory;

/**
 * Annotation processor for JPA to generate "dummy" classes for all Entity classes for use with Criteria API.
 * Any class ({MyClass}) that has a JPA "class" annotation will have a stub class ({MyClass}_) generated.
 * <ul>
 * <li>For each managed class X in package p, a metamodel class X_ in package p is created.</li>
 * <li>The name of the metamodel class is derived from the name of the managed class by appending "_" 
 * to the name of the managed class.</li>
 * <li>The metamodel class X_ must be annotated with the javax.persistence.StaticMetamodel annotation</li>
 * <li>If class X extends another class S, where S is the most derived managed class (i.e., entity or 
 * mapped superclass) extended by X, then class X_ must extend class S_, where S_ is the meta-model class 
 * created for S.</li>
 * <li>For every persistent non-collection-valued attribute y declared by class X, where the type of y is Y, 
 * the metamodel class must contain a declaration as follows:
 * <pre>public static volatile SingularAttribute&lt;X, Y&gt; y;</pre></li>
 * <li>For every persistent collection-valued attribute z declared by class X, where the element type
 * of z is Z, the metamodel class must contain a declaration as follows:
 *     <ul>
 *     <li>if the collection type of z is java.util.Collection, then 
 *     <pre>public static volatile CollectionAttribute&lt;X, Z&gt; z;</pre></li>
 *     <li>if the collection type of z is java.util.Set, then
 *     <pre>public static volatile SetAttribute&lt;X, Z&gt; z;</pre></li>
 *     <li>if the collection type of z is java.util.List, then
 *     <pre>public static volatile ListAttribute&lt;X, Z&gt; z;</pre></li>
 *     <li>if the collection type of z is java.util.Map, then
 *     <pre>public static volatile MapAttribute&lt;X, K, Z&gt; z;</pre>
 *     where K is the type of the key of the map in class X</li>
 *     </ul>
 * </li>
 * </ul>
 */
@SupportedAnnotationTypes({"javax.persistence.Entity", "javax.persistence.Embeddable", "javax.persistence.MappedSuperclass"})
public class JPACriteriaProcessor extends AbstractProcessor
{
    private static final String CLASS_NAME_SUFFIX = "_";

    private static final String CODE_INDENT = "    ";

    Types typesHandler;

    protected static Class[] annotationsWithTargetEntity =
        new Class[] {OneToOne.class, OneToMany.class, ManyToOne.class, ManyToMany.class};

    /* (non-Javadoc)
     * @see javax.annotation.processing.AbstractProcessor#process(java.util.Set, javax.annotation.processing.RoundEnvironment)
     */
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv)
    {
        if (roundEnv.processingOver())
        {
            return false;
        }

        typesHandler = processingEnv.getTypeUtils();
        Set<? extends Element> elements = roundEnv.getRootElements();
        for (Element e : elements)
        {
            if (e instanceof TypeElement)
            {
                processClass((TypeElement)e);
            }
        }
        return false;
    }

    /**
     * Handler for processing a JPA annotated class to create the criteria class stub.
     * @param el The class element
     */
    protected void processClass(TypeElement el)
    {
        if (el == null || !isJPAAnnotated(el))
        {
            return;
        }

        // TODO Set imports to only include the classes we require
        // TODO Set references to other classes to be the class name and put the package in the imports
        // TODO Support specification of the location for writing the class source files
        Elements elementUtils = processingEnv.getElementUtils();
        String className = elementUtils.getBinaryName(el).toString();
        String pkgName = className.substring(0, className.lastIndexOf('.'));
        String classSimpleName = className.substring(className.lastIndexOf('.') + 1);
        String classNameNew = className + CLASS_NAME_SUFFIX;
        System.out.println("DataNucleus : JPA Criteria - " + className + " -> " + classNameNew);

        Map<String, TypeMirror> genericLookups = null;
        List<? extends TypeParameterElement> elTypeParams = el.getTypeParameters();
        for (TypeParameterElement elTypeParam : elTypeParams)
        {
            List<? extends TypeMirror> elTypeBounds = elTypeParam.getBounds();
            if (elTypeBounds != null && !elTypeBounds.isEmpty())
            {
                genericLookups = new HashMap<String, TypeMirror>();
                genericLookups.put(elTypeParam.toString(), elTypeBounds.get(0));
            }
        }

        TypeElement superEl = getPersistentSupertype(el);
        try
        {
            JavaFileObject javaFile = processingEnv.getFiler().createSourceFile(classNameNew);
            Writer w = javaFile.openWriter();
            try
            {
                w.append("package " + pkgName + ";\n");
                w.append("\n");
                w.append("import javax.persistence.metamodel.*;\n");
                w.append("\n");
                w.append("@StaticMetamodel(" + classSimpleName + ".class)\n");
                w.append("public class " + classSimpleName + CLASS_NAME_SUFFIX);
                if (superEl != null)
                {
                    String superClassName = elementUtils.getBinaryName(superEl).toString();
                    w.append(" extends ").append(superClassName + CLASS_NAME_SUFFIX);
                }
                w.append("\n");
                w.append("{\n");

                // Find the members to use for persistence processing
                AccessType clsAccessType = (AccessType) AnnotationProcessorUtils.getValueForAnnotationAttribute(el, AccessType.class, "value");
                List<? extends Element> members = null;
                if (clsAccessType == AccessType.FIELD)
                {
                    // Only use fields
                    members = AnnotationProcessorUtils.getFieldMembers(el);
                }
                else if (clsAccessType == AccessType.PROPERTY)
                {
                    // Only use properties
                    members = AnnotationProcessorUtils.getPropertyMembers(el);
                }
                else
                {
                    // Default access type - whichever type (field or method) is annotated first
                    members = getDefaultAccessMembers(el);
                }

                if (members != null)
                {
                    Iterator<? extends Element> iter = members.iterator();
                    while (iter.hasNext())
                    {
                        Element member = iter.next();
                        boolean isTransient = false;
                        List<? extends AnnotationMirror> annots = member.getAnnotationMirrors();
                        if (annots != null)
                        {
                            Iterator<? extends AnnotationMirror> annotIter = annots.iterator();
                            while (annotIter.hasNext())
                            {
                                AnnotationMirror annot = annotIter.next();
                                if (annot.getAnnotationType().toString().equals(Transient.class.getName()))
                                {
                                    // Ignore this
                                    isTransient = true;
                                    break;
                                }
                            }
                        }

                        if (!isTransient)
                        {
                            if (member.getKind() == ElementKind.FIELD ||
                                (member.getKind() == ElementKind.METHOD && AnnotationProcessorUtils.isJavaBeanGetter((ExecutableElement) member)))
                            {
                                TypeMirror type = AnnotationProcessorUtils.getDeclaredType(member);
                                String typeName = AnnotationProcessorUtils.getDeclaredTypeName(processingEnv, type, true);
                                TypeCategory cat = AnnotationProcessorUtils.getTypeCategoryForTypeMirror(typeName);
                                String memberName = AnnotationProcessorUtils.getMemberName(member);

                                w.append(CODE_INDENT).append("public static volatile " + cat.getTypeName()).append("<" + classSimpleName + ", ");
                                if (cat == TypeCategory.ATTRIBUTE)
                                {
                                    if (type instanceof DeclaredType)
                                    {
                                        // Note this works for things like Bean Validation 2.0 @NotNull which comes through as "(@javax.validation.constraints.NotNull :: theUserType)"
                                        type = ((DeclaredType)type).asElement().asType();
                                    }

                                    if (type instanceof PrimitiveType)
                                    {
                                        if (type.toString().equals("long"))
                                        {
                                            w.append("Long");
                                        }
                                        else if (type.toString().equals("int"))
                                        {
                                            w.append("Integer");
                                        }
                                        else if (type.toString().equals("short"))
                                        {
                                            w.append("Short");
                                        }
                                        else if (type.toString().equals("float"))
                                        {
                                            w.append("Float");
                                        }
                                        else if (type.toString().equals("double"))
                                        {
                                            w.append("Double");
                                        }
                                        else if (type.toString().equals("char"))
                                        {
                                            w.append("Character");
                                        }
                                        else if (type.toString().equals("byte"))
                                        {
                                            w.append("Byte");
                                        }
                                        else if (type.toString().equals("boolean"))
                                        {
                                            w.append("Boolean");
                                        }
                                        else
                                        {
                                            w.append(type.toString());
                                        }
                                    }
                                    else
                                    {
                                        String name = type.toString();
                                        TypeMirror target = null;
                                        for (int i=0;i<annotationsWithTargetEntity.length;i++)
                                        {
                                            Object targetValue = AnnotationProcessorUtils.getValueForAnnotationAttribute(member, annotationsWithTargetEntity[i], "targetEntity");
                                            if (targetValue != null)
                                            {
                                                target = (TypeMirror)targetValue;
                                                break;
                                            }
                                        }
                                        if (target != null)
                                        {
                                            name = target.toString();
                                        }
                                        else if (genericLookups != null && genericLookups.containsKey(name))
                                        {
                                            // This is a generic type, so replace with the bound type
                                            // equates to "T extends MyOtherType" and putting "MyOtherType" in
                                            name = genericLookups.get(name).toString();
                                        }
                                        w.append(name);
                                    }
                                }
                                else if (cat == TypeCategory.MAP)
                                {
                                    TypeMirror keyType = getTypeParameter(member, type, 0, false);
                                    String keyTypeName = AnnotationProcessorUtils.getDeclaredTypeName(processingEnv, keyType, true);
                                    TypeMirror valueType = getTypeParameter(member, type, 1, true);
                                    String valueTypeName = AnnotationProcessorUtils.getDeclaredTypeName(processingEnv, valueType, true);
                                    w.append(keyTypeName + ", " + valueTypeName);
                                }
                                else
                                {
                                    TypeMirror elementType = getTypeParameter(member, type, 0, true);
                                    String elementTypeName = AnnotationProcessorUtils.getDeclaredTypeName(processingEnv, elementType, true);
                                    w.append(elementTypeName);
                                }
                                w.append("> " + memberName + ";\n");
                            }
                        }
                    }
                }

                w.append("}\n");
                w.flush();
            }
            finally
            {
                w.close();
            }
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    TypeMirror getTypeParameter(Element element, TypeMirror type, int position, boolean checkTarget)
    {
        if (type.getKind() == TypeKind.ARRAY)
        {
            return ((ArrayType)type).getComponentType();
        }
        if (type.getKind() != TypeKind.DECLARED)
        {
            return ((DeclaredType)type).asElement().asType();
        }

        if (checkTarget) 
        {
            // Check annotations for a value
            TypeMirror target = null;
            for (int i=0;i<annotationsWithTargetEntity.length;i++)
            {
                Object targetValue = AnnotationProcessorUtils.getValueForAnnotationAttribute(element, annotationsWithTargetEntity[i], "targetEntity");
                if (targetValue != null)
                {
                    target = (TypeMirror)targetValue;
                    break;
                }
            }
            if (target != null)
            {
                return target;
            }
        }

        // Use generics
        List<? extends TypeMirror> params = ((DeclaredType)type).getTypeArguments();
        TypeMirror param = (params == null || params.size() < position+1) ? typesHandler.getNullType() : params.get(position);
        return param;
    }

    /**
     * Method to find the next persistent supertype above this one.
     * @param element The element
     * @return Its next parent that is persistable (or null if no persistable predecessors)
     */
    public TypeElement getPersistentSupertype(TypeElement element)
    {
        TypeMirror superType = element.getSuperclass();
        if (superType == null || "java.lang.Object".equals(element.toString()))
        {
            return null;
        }

        TypeElement superElement = (TypeElement) processingEnv.getTypeUtils().asElement(superType);
        if (isJPAAnnotated(superElement))
        {
            return superElement;
        }
        return getPersistentSupertype(superElement);
    }
    /**
     * Convenience accessor for members for the default access type of the supplied type element.
     * If properties are annotated then returns all properties, otherwise returns all fields. 
     * @param el The type element
     * @return The members
     */
    public static List<? extends Element> getDefaultAccessMembers(TypeElement el)
    {
        Iterator<? extends Element> memberIter = el.getEnclosedElements().iterator();
        while (memberIter.hasNext())
        {
            Element member = memberIter.next();
            
            if (AnnotationProcessorUtils.isMethod(member))
            {
                ExecutableElement method = (ExecutableElement)member;
                if (AnnotationProcessorUtils.isJavaBeanGetter(method) || AnnotationProcessorUtils.isJavaBeanSetter(method))
                {
                    // Property
                    Iterator<? extends AnnotationMirror> annIter = member.getAnnotationMirrors().iterator();
                    while (annIter.hasNext())
                    {
                        AnnotationMirror ann = annIter.next();
                        String annTypeName = ann.getAnnotationType().toString();
                        if (annTypeName.startsWith("javax.persistence"))
                        {
                            return AnnotationProcessorUtils.getPropertyMembers(el);
                        }
                    }
                }
            }
        }
        return AnnotationProcessorUtils.getFieldMembers(el);
    }

    /**
     * Convenience method to return if this class element has any of the defining JPA annotations.
     * @param el The class element
     * @return Whether it is to be considered a JPA annotated class
     */
    public static boolean isJPAAnnotated(TypeElement el)
    {
        if ((el.getAnnotation(Entity.class) != null) ||
            (el.getAnnotation(MappedSuperclass.class) != null) ||
            (el.getAnnotation(Embeddable.class) != null))
        {
            return true;
        }
        return false;
    }

    @Override
    public SourceVersion getSupportedSourceVersion() 
    {
        return SourceVersion.latest();
    }
}
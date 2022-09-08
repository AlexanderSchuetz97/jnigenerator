//
// Copyright Alexander Schütz, 2022
//
// This file is part of jnigenerator.
//
// jnigenerator is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// jnigenerator is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// A copy of the GNU General Public License should be provided
// in the COPYING files in top level directory of jnigenerator.
// If not, see <https://www.gnu.org/licenses/>.
//
package io.github.alexanderschuetz97.jnigenerator;

import org.apache.bcel.classfile.ClassParser;
import org.apache.bcel.classfile.Field;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.ArrayType;
import org.apache.bcel.generic.ObjectType;
import org.apache.bcel.generic.Type;
import org.apache.bcel.util.ClassPath;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

@Mojo(name = "jnigenerator",
        defaultPhase = LifecyclePhase.PROCESS_CLASSES,
        requiresDependencyResolution = ResolutionScope.COMPILE,
        requiresDependencyCollection = ResolutionScope.COMPILE)
public class JNIGenerator extends AbstractMojo {

    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    protected MavenProject project;

    @Parameter(defaultValue = "${project.build.outputDirectory}", required = true)
    protected File classes;

    @Parameter(property = "structs")
    protected Member[] structs;

    @Parameter(property = "exceptions")
    protected Member[] exceptions;

    @Parameter(property = "headerOutput")
    protected String headerOutput;

    @Parameter(property = "implOutput")
    protected String implOutput;

    @Parameter(property = "headerInclude")
    protected String headerInclude;

    @Parameter(property = "builders")
    protected String[] builders;

    @Parameter(property = "builderDir")
    protected String builderDir;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            generate();
        } catch (Throwable e) {
            getLog().error(e);
            throw new MojoExecutionException("it died", e);
        }

        build();
    }

    protected void build() throws MojoExecutionException, MojoFailureException {
        if (builders == null || builders.length == 0) {
            return;
        }

        ProcessBuilder processBuilder = new ProcessBuilder().command(builders).inheritIO();
        if (builderDir != null) {
            processBuilder = processBuilder.directory(new File(builderDir));
        }

        try {
            Process proc = processBuilder.start();
            int v = proc.waitFor();
            if (v != 0) {
                throw new MojoFailureException("Builder process exited with value " + v);
            }
        } catch (IOException | InterruptedException e) {
            throw new MojoExecutionException("it died", e);
        }
    }

    protected void generate() throws Throwable {

        Generation generation = start();

        Map<String, Member> structsSet = new TreeMap<>();
        Map<String, Member> exceptionsSet = new TreeMap<>();

        if (structs != null) {
            for (Member member : structs) {
                structsSet.put(member.getClassname(), member);
            }
        }

        if (exceptions != null) {
            for (Member member : exceptions) {
                exceptionsSet.put(member.getClassname(), member);
            }
        }

        structsSet.remove("java.lang.Enum");
        structsSet.remove("java.lang.String");





        Set<String> allClasses = new TreeSet<>();
        allClasses.addAll(structsSet.keySet());
        allClasses.addAll(exceptionsSet.keySet());

        Map<String, JavaClass> jclasses = getClasses(allClasses);

        for (JavaClass clazz : jclasses.values()) {
            generateClassInit(generation, clazz);
        }



        for (String struct : structsSet.keySet()) {
            generateStruct(generation, structsSet.get(struct), jclasses.get(struct));
        }

        for (String exc : exceptionsSet.keySet()) {
            generateException(generation, exceptionsSet.get(exc), jclasses.get(exc));
        }



        finish(generation);
    }

    public Map<String, JavaClass> getClasses(Set<String> needed) throws IOException {
        final List<File> classFiles = new ArrayList<>();
        Files.walkFileTree(classes.toPath(), new FileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                File theFile = file.toFile();
                if (theFile.getName().endsWith(".class")) {
                    classFiles.add(theFile);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                return FileVisitResult.CONTINUE;
            }
        });

        Map<String, JavaClass> classes = new HashMap<>();
        for (File f : classFiles) {
            //TODO performance improve no need to parse every class we only care about like 1
            try(FileInputStream fais = new FileInputStream(f)) {
                ClassParser parser = new ClassParser(fais, f.getAbsolutePath());
                JavaClass javaClass = parser.parse();
                if (needed.contains(javaClass.getClassName())) {
                    classes.put(javaClass.getClassName(), javaClass);
                }
            }
        }

        needed.removeAll(classes.keySet());
        for (String sn : needed) {
            JavaClass clazz = getClass(sn);
            classes.put(sn, clazz);
        }


        return classes;
    }

    public JavaClass getClass(String name) throws IOException {
        ClassParser parser = new ClassParser(ClassPath.SYSTEM_CLASS_PATH.getClassFile(name).getInputStream(), name + ".class");
        return parser.parse();
    }

    protected Generation start() {
        Generation generation = new Generation();
        generation.header(
                "//THIS FILE IS MACHINE GENERATED, DO NOT EDIT",
                "#include <jni.h>",
                "#include <stddef.h>",
                "",
                "/**",
                " * initializes the the state of the generated code. Must be called once when your library loads. ",
                " * returns true if initialization succeeds. If this method returns false then an exception is pending in the JNIEnv that explains the error.",
                " * it is recommended to call this method in your JNI_OnLoad method",
                "*/",
                "jboolean jnigenerator_init(JNIEnv * env);",
                "",
                "/**",
                " * destroys the the state of the generated code. You can safely call jnigenerator_init() again afterwards. ",
                " * it is recommended to call this method in your JNI_OnUnload method",
                "*/",
                "void jnigenerator_destroy(JNIEnv * env);",
                "",
                "/**",
                " * equivalent to (*env)->ExceptionCheck(env) just much shorter to write.",
                " */",
                "jboolean jerr(JNIEnv * env);",
                "",
                "/**",
                " * Creates a new byte array of the given length and copies the give buffer into it.",
                " * Return NULL when array creation fails. In this case a java exception is thrown.",
                " */",
                "jbyteArray jarrayB(JNIEnv * env, jbyte * buffer, jsize len);",
                "",
                "/**",
                " * Returns the enum ordinal or -1 if the passed enum value is NULL.",
                " */",
                "jint jenum_ordinal(JNIEnv * env, jobject enumValue);",
                "",
                "/**",
                " * Returns the name of the enum constant or NULL passed enum value is NULL.",
                " */",
                "jstring jenum_name(JNIEnv * env, jobject enumValue);",
                ""
        );

        generation.impl(
                "//THIS FILE IS MACHINE GENERATED, DO NOT EDIT",
                headerInclude,
                "static jclass internal_Exception = 0;",
                "static jclass internal_OutOfMemoryError = 0;",
                "static jclass internal_IllegalArgumentException = 0;",
                "static jclass internal_NullPointerException = 0;",
                "static jclass internal_Enum = 0;",
                "static jmethodID internal_Enum_ordinal = 0;",
                "static jmethodID internal_Enum_name = 0;",
                "",
                "static jclass makeGlobalClassRef(JNIEnv * env, const char * name) {",
                "   jclass clazz = (*env) -> FindClass(env, name);",
                "   if (clazz == 0) {",
                "       return 0;",
                "   }",
                "",
                "   jclass global = (*env) -> NewGlobalRef(env, clazz);",
                "   (*env) -> DeleteLocalRef(env, clazz);",
                "   return global;",
                "}",
                "",
                "static void throw_internal_OutOfMemoryError(JNIEnv * env, const char* message) {",
                "    if (!(*env) -> ExceptionCheck(env)) {",
                "        (*env) -> ThrowNew(env, internal_OutOfMemoryError, message);",
                "    }",
                "}",
                "",
                "static void throw_internal_IllegalArgumentException(JNIEnv * env, const char * message) {",
                "    if (!(*env) -> ExceptionCheck(env)) {",
                "        (*env) -> ThrowNew(env, internal_IllegalArgumentException, message);",
                "    }",
                "}",
                "",
                "static void throw_internal_NullPointerException(JNIEnv * env, const char * message) {",
                "    if (!(*env) -> ExceptionCheck(env)) {",
                "        (*env) -> ThrowNew(env, internal_NullPointerException, message);",
                "    }",
                "}",
                "",
                "jboolean jerr(JNIEnv * env) {",
                "    return (*env) -> ExceptionCheck(env);",
                "}",
                "",
                "jbyteArray jarrayB(JNIEnv * env, jbyte * buffer, jsize len) {",
                "    if (len < 0) {",
                "        throw_internal_IllegalArgumentException(env, \"jarrayB len < 0\");",
                "        return 0;",
                "    }",
                "    jbyteArray res = (*env) -> NewByteArray(env, len);",
                "    if (res == 0) {",
                "        throw_internal_OutOfMemoryError(env, \"jarrayB NewByteArray\");",
                "        return 0;",
                "    }",
                "    if (len == 0) {",
                "        return 0;",
                "    }",
                "    if (buffer == 0) {",
                "        (*env)->DeleteLocalRef(env, res);",
                "        throw_internal_NullPointerException(env, \"jarrayB buffer = NULL\");",
                "    }",
                "    (*env)->SetByteArrayRegion(env, res, 0, len, (const jbyte*) buffer);",
                "    return res;",
                "}",
                "",
                "jint jenum_ordinal(JNIEnv * env, jobject enumValue) {",
                "    if (enumValue == 0) {",
                "        return -1;",
                "    }",
                "    return (jint) (*env) -> CallIntMethod(env, enumValue, internal_Enum_ordinal);",
                "}",
                "",
                "jstring jenum_name(JNIEnv * env, jobject enumValue) {",
                "    if (enumValue == 0) {",
                "        return 0;",
                "    }",
                "    return (jstring) (*env) -> CallObjectMethod(env, enumValue, internal_Enum_name);",
                "}",
                ""
        );

        generation.init(
                "    internal_Exception = makeGlobalClassRef(env, \"java/lang/Exception\");",
                "    if (internal_Exception == 0) {",
                "        return JNI_FALSE;",
                "    }",
                "",
                "    internal_OutOfMemoryError = makeGlobalClassRef(env, \"java/lang/OutOfMemoryError\");",
                "    if (internal_OutOfMemoryError == 0) {",
                "        return JNI_FALSE;",
                "    }",
                "",
                "    internal_IllegalArgumentException = makeGlobalClassRef(env, \"java/lang/IllegalArgumentException\");",
                "    if (internal_IllegalArgumentException == 0) {",
                "        return JNI_FALSE;",
                "    }",
                "",
                "    internal_NullPointerException = makeGlobalClassRef(env, \"java/lang/NullPointerException\");",
                "    if (internal_NullPointerException == 0) {",
                "        return JNI_FALSE;",
                "    }",
                "    internal_Enum = makeGlobalClassRef(env, \"java/lang/Enum\");",
                "    if (internal_Enum == 0) {",
                "        return JNI_FALSE;",
                "    }",
                "",
                "    internal_Enum_ordinal = (*env) ->GetMethodID(env, internal_Enum, \"ordinal\", \"()I\");",
                "    if (internal_Enum_ordinal == 0) {",
                "        return JNI_FALSE;",
                "    }",
                "",
                "    internal_Enum_name = (*env) ->GetMethodID(env, internal_Enum, \"name\", \"()Ljava/lang/String;\");",
                "    if (internal_Enum_name == 0) {",
                "        return JNI_FALSE;",
                "    }",
                ""
        );

        generation.destroy(
                "    if (internal_Exception != 0) {",
                "        (*env) -> DeleteGlobalRef(env, internal_Exception);",
                "        internal_Exception = 0;",
                "    }",
                "    if (internal_OutOfMemoryError != 0) {",
                "        (*env) -> DeleteGlobalRef(env, internal_OutOfMemoryError);",
                "        internal_OutOfMemoryError = 0;",
                "    }",
                "    if (internal_IllegalArgumentException != 0) {",
                "        (*env) -> DeleteGlobalRef(env, internal_IllegalArgumentException);",
                "        internal_IllegalArgumentException = 0;",
                "    }",
                "    if (internal_NullPointerException != 0) {",
                "        (*env) -> DeleteGlobalRef(env, internal_NullPointerException);",
                "        internal_NullPointerException = 0;",
                "    }",
                "    if (internal_Enum != 0) {",
                "        (*env) -> DeleteGlobalRef(env, internal_Enum);",
                "        internal_Enum = 0;",
                "    }",
                "    internal_Enum_name = 0;",
                "    internal_Enum_ordinal = 0;",
                ""
        );


        return generation;
    }

    protected String simpleClassName(String clazz) {
        if (clazz.endsWith(".")) {
            throw new IllegalArgumentException("Invalid class name " + clazz);
        }
        int idx = Math.max(clazz.lastIndexOf("/"), clazz.lastIndexOf('.'));

        if (idx == -1) {

            //Default package?
            return clazz;
        }

        return clazz.substring(idx+1);
    }

    protected String nativeClassName(String clazz) {
        return clazz.replace('.', '/');
    }

    protected String getJValueUnionMember(Type type) {
        //jboolean z;
        //jbyte    b;
        //jchar    c;
        //jshort   s;
        //jint     i;
        //jlong    j;
        //jfloat   f;
        //jdouble  d;
        //jobject  l;

        switch (type.getType()) {
            case(4):
                return "z";
            case(5):
                return "c";
            case(6):
                return "f";
            case(7):
                return "d";
            case(8):
                return "b";
            case(9):
                return "s";
            case(10):
                return "i";
            case(11):
                return "j";
            case(13):
                //ARRAY
                return "l";
            case(14):
                return "l";
            default:
                throw new IllegalArgumentException(type.getSignature());
        }
    }
    protected String getCType(Type type) {
        switch (type.getType()) {
            case(4):
                return "jboolean";
            case(5):
                return "jchar";
            case(6):
                return "jfloat";
            case(7):
                return "jdouble";
            case(8):
                return "jbyte";
            case(9):
                return "jshort";
            case(10):
                return "jint";
            case(11):
                return "jlong";
            case(13):
                //ARRAY
                ArrayType arrayType = (ArrayType) type;
                if (arrayType.getDimensions() > 1) {
                    return "jarray";
                }

                Type component = arrayType.getElementType();
                if (component == null) {
                    return "jarray";
                }

                switch (component.getType()) {
                    case(4):
                        return "jbooleanArray";
                    case(5):
                        return "jcharArray";
                    case(6):
                        return "jfloatArray";
                    case(7):
                        return "jdoubleArray";
                    case(8):
                        return "jbyteArray";
                    case(9):
                        return "jshortArray";
                    case(10):
                        return "jintArray";
                    case(11):
                        return "jlongArray";
                    case(12):
                        return "jarray";
                    case(13):
                        return "jarray";
                    case(14):
                        return "jobjectArray";
                }

                throw new IllegalArgumentException("13 -> " + component.getSignature());
            case(14):
                ObjectType oType = (ObjectType) type;
                String cname = oType.getClassName();
                switch (cname) {
                    case("java.lang.String"):
                        return "jstring";
                    case("java.lang.ref.WeakReference"):
                        return "jweak";
                    case("java.lang.Class"):
                        return "jclass";
                    default:
                        return "jobject";
                }

            case(12):
                return "void";
            default:
                throw new IllegalArgumentException(type.getSignature());
        }
    }

    protected String getCAccessor(Type type) {
        if (type == null) {
            return "Void";
        }
        switch (type.getType()) {
            case(4):
                return "Boolean";
            case(5):
                return "Char";
            case(6):
                return "Float";
            case(7):
                return "Double";
            case(8):
                return "Byte";
            case(9):
                return "Short";
            case(10):
                return "Int";
            case(11):
                return "Long";
            case(13):
               //ARRAY
            case(14):
                return "Object";
            case(12):
                return "Void";
            default:
                throw new IllegalArgumentException(type.getSignature());
        }
    }

    protected boolean containsJStringParameter(Method method) {
        for (Type t : method.getArgumentTypes()) {
            if ("jstring".equals(getCType(t))) {
                return true;
            }
        }

        return false;
    }

    protected static final Map<String, String> NO_CASTS = new HashMap<>();

    protected static final Map<String, String> CAST_CONST_CHAR_PTR = new HashMap<>();
    static {
        CAST_CONST_CHAR_PTR.put("jstring", "(const char*)");
    }


    protected String getCParameterUse(Method method, Map<String, String> casts) {
        Type[] argumentTypes = method.getArgumentTypes();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < argumentTypes.length; i++) {
            String cast = casts.get(getCType(argumentTypes[i]));
            if (cast == null) {
                cast = "";
            }
            sb.append(", ");
            sb.append(cast);
            sb.append("p");
            sb.append(i);
        }

        return sb.toString();
    }

    protected static final Map<String, String> NO_SUBSTITUTION = new HashMap<>();
    protected static final Map<String, String> CHAR_PTR_SUBSTITUTION = new HashMap<>();
    static {
        CHAR_PTR_SUBSTITUTION.put("jstring", "char*");
    }

    protected static final Map<String, String> CONST_CHAR_PTR_SUBSTITUTION = new HashMap<>();
    static {
        CONST_CHAR_PTR_SUBSTITUTION.put("jstring", "const char*");
    }

    protected String getCParameters(Method method, Map<String, String> substitution) {
        Type[] argumentTypes = method.getArgumentTypes();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < argumentTypes.length; i++) {
            sb.append(", ");
            String cType = getCType(argumentTypes[i]);
            String subsitute = substitution.get(cType);
            if (subsitute != null) {
                cType = subsitute;
            }

            sb.append(cType);
            sb.append(' ');
            sb.append('p');
            sb.append(i);
        }

        return sb.toString();
    }


    protected void generateClassInit(Generation stubbing, JavaClass clazz) {
        if (!stubbing.clazz(clazz.getClassName())) {
            return;
        }

        String scn = simpleClassName(clazz.getClassName());
        String nat = nativeClassName(clazz.getClassName());
        stubbing.impl("static jclass " + scn + " = 0;");
        stubbing.init(
                "    " + scn + " = makeGlobalClassRef(env, \"" + nat +"\");",
                "    if ("+scn+" == 0) {",
                "        (*env) -> ExceptionClear(env);",
                "        (*env) -> ThrowNew(env, Exception, \"cant find " + nat +"\");",
                "        return JNI_FALSE;",
                "    }",
                "");

        stubbing.destroy(
                "    if ("+ scn+" != 0) {",
                "        (*env) -> DeleteGlobalRef(env, " + scn + ");",
                "        " + scn + " = 0;",
                "    }",
                ""
        );

        stubbing.header("jboolean jinstanceof_" + scn + "(JNIEnv * env, jobject value);");
        stubbing.impl(
                "jboolean jinstanceof_" + scn + "(JNIEnv * env, jobject value) {",
                "   return (*env)->IsInstanceOf(env, value, "+ scn +");",
                "}",
                "");

    }

    protected void generateException(Generation generation, Member member, JavaClass clazz) {
        String scn = simpleClassName(clazz.getClassName());
        String nat = nativeClassName(clazz.getClassName());

        Map<String, Method> sorter = new TreeMap<>();
        for (Method m : clazz.getMethods()) {
            if (!m.getName().equals("<init>")) {
                continue;
            }

            if (!m.isPublic() && member.isOnlyPublic()) {
                continue;
            }

            if (m.isStatic()) {
                continue;
            }

            if (member.containsFilter(m.getSignature())) {
                continue;
            }
            sorter.put(m.getSignature(), m);
        }

        int counter = -1;
        for (Method m : sorter.values()) {
            counter++;
            String nativeMethodRefName = scn + "_EC_" + counter;

            String suffix = "";
            if (counter > 0) {
                suffix = "_" + counter;
            }

            String sig = m.getSignature();

            generation.impl("static jmethodID " + nativeMethodRefName + " = 0;");
            generation.init(
                    "    " + nativeMethodRefName + " = (*env) -> GetMethodID(env, " + scn + ", \"<init>\", \"" + sig + "\");",
                    "    if (" + nativeMethodRefName + " == 0) {",
                    "        (*env) -> ExceptionClear(env);",
                    "        (*env) -> ThrowNew(env, Exception, \"cant find " + nat + ".<init>" + sig + "\");",
                    "        return JNI_FALSE;",
                    "    }",
                    "");
            generation.destroy(
                    "    "+ nativeMethodRefName + " = 0;"
            );



            generation.header("void jthrow_" + scn + suffix + "(JNIEnv * env" + getCParameters(m, NO_SUBSTITUTION) + ");");
            generation.impl(
                    "void jthrow_" + scn + suffix + "(JNIEnv * env" + getCParameters(m, NO_SUBSTITUTION) + ") {",
                    "    if ((*env) -> ExceptionCheck(env)) {",
                    "        return;",
                    "    }",
                    "    jobject obj = (*env) -> NewObject(env, " + scn + ", " + nativeMethodRefName + getCParameterUse(m, NO_CASTS) + ");",
                    "    if (obj == NULL) {",
                    "        throw_internal_OutOfMemoryError(env, \"NewObject\");",
                    "        return;",
                    "    }",
                    "    (*env)->Throw(env, obj);",
                    "}",
                    ""
            );

            if (containsJStringParameter(m)) {
                generateJStringException(generation, scn, m, nativeMethodRefName, suffix);
            }

        }

    }

    private void generateJStringException(Generation generation, String scn, Method m, String nativeMethodRefName, String suffix) {
        Type[] types = m.getArgumentTypes();

        generation.header("void jthrowC_" + scn + suffix + "(JNIEnv * env" + getCParameters(m, CHAR_PTR_SUBSTITUTION) + ");");
        generation.header("void jthrowCC_" + scn + suffix + "(JNIEnv * env" + getCParameters(m, CONST_CHAR_PTR_SUBSTITUTION) + ");");

        generation.impl(
                "void jthrowC_" + scn + suffix + "(JNIEnv * env" + getCParameters(m, CHAR_PTR_SUBSTITUTION) + ") {",
                "    jthrowCC_" + scn + suffix + "(env" + getCParameterUse(m, CAST_CONST_CHAR_PTR) + ");",
                "}",
                ""
        );

        if (m.getSignature().equals("(Ljava/lang/String;)V")) {
            System.out.println(scn);
            generation.impl(
                    "void jthrowCC_" + scn + suffix + "(JNIEnv * env" + getCParameters(m, CONST_CHAR_PTR_SUBSTITUTION) + ") {",
                    "    if ((*env) -> ExceptionCheck(env)) {",
                    "        return;",
                    "    }",
                    "    (*env) -> ThrowNew(env, "+ scn+", p0);",
                    "}"
            );

            return;
        }

        generation.impl(
                "void jthrowCC_" + scn + suffix + "(JNIEnv * env" + getCParameters(m, CONST_CHAR_PTR_SUBSTITUTION) + ") {",
                "    if ((*env) -> ExceptionCheck(env)) {",
                "        return;",
                "    }",
                "    jvalue parameters[" + types.length +"];"
                );

        for (int i = 0; i < types.length; i++) {
            String cType = getCType(types[i]);
            if ("jstring".equals(cType)) {
                generation.impl(
                        "    if (p" + i + " == 0) {",
                        "        parameters[" + i + "].l = 0;",
                        "    } else {",
                        "        parameters[" + i + "].l = (*env) -> NewStringUTF(env, p" + i + ");",
                        "        if (parameters[" + i + "].l == 0) {",
                        "            throw_internal_OutOfMemoryError(env, \"NewStringUTF\");",
                        "            return;",
                        "        }",
                        "    }");
                continue;
            }
            generation.impl(
                    "    parameters[" + i + "]." + getJValueUnionMember(types[i]) + " = p" + i + ";"
            );
        }

        generation.impl(
                "    jobject obj = (*env) -> NewObjectA(env, " + scn + ", " + nativeMethodRefName + ", (const jvalue*) parameters);",
                "    if (obj == NULL) {",
                "        throw_internal_OutOfMemoryError(env, \"NewObjectA\");",
                "        return;",
                "    }",
                "    (*env)->Throw(env, obj);",
                "}",
                ""
        );


    }

    protected void generateStruct(Generation generation, Member member, JavaClass clazz) {

        String scn = simpleClassName(clazz.getClassName());
        String nat = nativeClassName(clazz.getClassName());

        List<String> enumValues = new ArrayList<>();

        for (Field f : clazz.getFields()) {
            String name = f.getName();

            if (!f.isPublic() && member.isOnlyPublic()) {
                continue;
            }

            if (member.containsFilter(name)) {
                continue;
            }

            Type type = f.getType();
            String sig = type.getSignature();
            String nativeFieldName = scn + "_" + name;
            String fieldIDMethod = f.isStatic() ? "GetStaticFieldID" : "GetFieldID";

            if (f.isEnum()) {
                generation.impl("static jobject " + nativeFieldName + " = 0;");
                generation.destroy(
                        "    if (" + nativeFieldName + " != 0) {",
                        "        (*env)->DeleteGlobalRef(env, "+ nativeFieldName + ");",
                        "    }",
                        "    "+ nativeFieldName + " = 0;"
                );

                String enumFieldInit = "enum_field_init_" + nativeFieldName;
                generation.init(
                        "    " + nativeFieldName + " = 0;",
                        "    jfieldID " + enumFieldInit + " = 0;",
                        "    " + enumFieldInit + " = (*env) -> " + fieldIDMethod + "(env, " + scn + ", \"" + name + "\", \"" + sig + "\");",
                        "    if (" + enumFieldInit + " == 0) {",
                        "        (*env) -> ExceptionClear(env);",
                        "        (*env) -> ThrowNew(env, Exception, \"cant find " + nat + "_" + name + "_" + sig + "\");",
                        "        return JNI_FALSE;",
                        "    }",
                        "    " + nativeFieldName + " = (*env) -> GetStaticObjectField(env, " + scn + ", " + enumFieldInit + ");",
                        "    if (" + nativeFieldName + " == 0) {",
                        "        (*env) -> ExceptionClear(env);",
                        "        (*env) -> ThrowNew(env, Exception, \"cant get enum value of " + nat + "_" + name + "_" + sig + "\");",
                        "        return JNI_FALSE;",
                        "    }",
                        "    ");

                generation.header("jobject jenum_" + scn + "_" + name + "();");
                generation.impl(
                        "jobject jenum_" + scn + "_" + name + "() {",
                        "   return "+nativeFieldName+";",
                        "}",
                        "");

                enumValues.add(nativeFieldName);
                continue;
            }

            generation.impl("static jfieldID " + nativeFieldName + " = 0;");

            generation.init(
                    "    " + nativeFieldName + " = (*env) -> " + fieldIDMethod + "(env, " + scn + ", \"" + name + "\", \"" + sig + "\");",
                    "    if (" + nativeFieldName + " == 0) {",
                    "        (*env) -> ExceptionClear(env);",
                    "        (*env) -> ThrowNew(env, Exception, \"cant find " + nat + "_" + name + "_" + sig + "\");",
                    "        return JNI_FALSE;",
                    "    }",
                    "");

            generation.destroy(
                    "    "+ nativeFieldName + " = 0;"
            );

            String ctype = getCType(type);
            String acc = getCAccessor(type);
            String cast = "";
            if ("Object".equals(acc) && !"jobject".equals(ctype)) {
                cast = "(" + ctype + ") ";
            }

            if (f.isStatic()) {
                generation.header("void jset_" + scn + "_" + name + "(JNIEnv * env, "+ ctype +" value);");
                generation.impl(
                        "void jset_" + scn + "_" + name + "(JNIEnv * env, "+ ctype +" value) {",
                        "   (*env)->SetStatic" + acc + "Field(env, "+ scn +", " + nativeFieldName + ", value);",
                        "}",
                        "");

                generation.header(ctype + " jget_" + scn + "_" + name + "(JNIEnv * env);");
                generation.impl(
                        ctype + " jget_" + scn + "_" + name + "(JNIEnv * env) {",
                        "   return "+ cast +"(*env)->GetStatic" + acc + "Field(env, "+ scn +", " + nativeFieldName + ");",
                        "}",
                        "");
            } else {
                generation.header("void jset_" + scn + "_" + name + "(JNIEnv * env, jobject instance, "+ ctype +" value);");
                generation.impl(
                        "void jset_" + scn + "_" + name + "(JNIEnv * env, jobject instance, "+ ctype +" value) {",
                        "   (*env)->Set" + acc + "Field(env, instance, " + nativeFieldName + ", value);",
                        "}",
                        "");

                if ("jbyteArray".equals(ctype)) {
                    generation.header("jboolean jsetA_" + scn + "_" + name + "(JNIEnv * env, jobject instance, jbyte * value, jsize len);");
                    generation.impl(
                            "jboolean jsetA_" + scn + "_" + name + "(JNIEnv * env, jobject instance, jbyte * value, jsize len) {",
                            "    if (value == 0) {",
                            "        (*env)->SetObjectField(env, instance," + nativeFieldName + ", 0);",
                            "        return JNI_TRUE;",
                            "    }",
                            "    if (len < 0) {",
                            "        len = 0;",
                            "    }",
                            "    jbyteArray tmp = (*env)->NewByteArray(env, len);",
                            "    if (tmp == 0) {",
                            "        throw_internal_OutOfMemoryError(env, \"NewByteArray\");",
                            "        return JNI_FALSE;",
                            "    }",
                            "    if (len > 0) {",
                            "        (*env)->SetByteArrayRegion(env, tmp, 0, len, (const jbyte*) value);",
                            "    }",
                            "    (*env)->SetObjectField(env, instance, " + nativeFieldName + ", tmp);",
                            "    (*env)->DeleteLocalRef(env, tmp);",
                            "    return JNI_TRUE;",
                            "}",
                            "");
                }

                if ("jlongArray".equals(ctype)) {
                    generation.header("jboolean jsetA_" + scn + "_" + name + "(JNIEnv * env, jobject instance, jlong * value, jsize len);");
                    generation.impl(
                            "jboolean jsetA_" + scn + "_" + name + "(JNIEnv * env, jobject instance, jlong * value, jsize len) {",
                            "    if (value == 0) {",
                            "        (*env)->SetObjectField(env, instance," + nativeFieldName + ", 0);",
                            "        return JNI_TRUE;",
                            "    }",
                            "    if (len < 0) {",
                            "        len = 0;",
                            "    }",
                            "    jlongArray tmp = (*env)->NewLongArray(env, len);",
                            "    if (tmp == 0) {",
                            "        throw_internal_OutOfMemoryError(env, \"NewByteArray\");",
                            "        return JNI_FALSE;",
                            "    }",
                            "    if (len > 0) {",
                            "        (*env)->SetLongArrayRegion(env, tmp, 0, len, (const jlong*) value);",
                            "    }",
                            "    (*env)->SetObjectField(env, instance, " + nativeFieldName + ", tmp);",
                            "    (*env)->DeleteLocalRef(env, tmp);",
                            "    return JNI_TRUE;",
                            "}",
                            "");
                }


                if ("jstring".equals(ctype)) {
                    generation.header("jboolean jsetC_" + scn + "_" + name + "(JNIEnv * env, jobject instance, char * value);");
                    generation.header("jboolean jsetCC_" + scn + "_" + name + "(JNIEnv * env, jobject instance, const char * value);");

                    generation.impl(
                            "jboolean jsetC_" + scn + "_" + name + "(JNIEnv * env, jobject instance, char * value) {",
                            "    return jsetCC_"  + scn + "_" + name + "(env, instance, (char*) value);",
                            "}",
                            "");


                    generation.impl(
                            "jboolean jsetCC_" + scn + "_" + name + "(JNIEnv * env, jobject instance, const char * value) {",
                            "    if (value == 0) {",
                            "        (*env)->SetObjectField(env, instance," + nativeFieldName + ", 0);",
                            "        return JNI_TRUE;",
                            "    }",
                            "    jstring tmp = (*env)->NewStringUTF(env, value);",
                            "    if (tmp == 0) {",
                            "        throw_internal_OutOfMemoryError(env, \"NewStringUTF\");",
                            "        return JNI_FALSE;",
                            "    }",
                            "    (*env)->SetObjectField(env, instance, " + nativeFieldName + ", tmp);",
                            "    (*env)->DeleteLocalRef(env, tmp);",
                            "    return JNI_TRUE;",
                            "}",
                            "");
                    generation.header("jboolean jsetWC_" + scn + "_" + name + "(JNIEnv * env, jobject instance, wchar_t * value);");
                    generation.impl(
                            "jboolean jsetWC_" + scn + "_" + name + "(JNIEnv * env, jobject instance, wchar_t * value) {",
                            "    if (value == 0) {",
                            "        (*env)->SetObjectField(env, instance," + nativeFieldName + ", 0);",
                            "        return JNI_TRUE;",
                            "    }",
                            "    ",
                            "    jsize i = 0;",
                            "    while (value[i] != 0) {",
                            "        i++;",
                            "    }",
                            "    ",
                            "    jstring tmp;",
                            "    if (sizeof(wchar_t) == sizeof(jchar)) {",
                            "        tmp = (*env) -> NewString(env, (const jchar*) value, i);",
                            "    } else {",
                            "        jchar tBuf[i];",
                            "        for (jsize j = 0; j < i; j++) {",
                            "            tBuf[j] = (jchar) value[j];",
                            "        }",
                            "        tmp = (*env) -> NewString(env, (const jchar*) tBuf, i);",
                            "    }",
                            "    if (tmp == 0) {",
                            "        throw_internal_OutOfMemoryError(env, \"NewByteArray\");",
                            "        return JNI_FALSE;",
                            "    }",
                            "    (*env)->SetObjectField(env, instance, " + nativeFieldName + ", tmp);",
                            "    (*env)->DeleteLocalRef(env, tmp);",
                            "    return JNI_TRUE;",
                            "}",
                            "");
                }


                generation.header(ctype + " jget_" + scn + "_" + name + "(JNIEnv * env, jobject instance);");
                generation.impl(
                        ctype + " jget_" + scn + "_" + name + "(JNIEnv * env, jobject instance) {",
                        "   return "+ cast +"(*env)->Get" + acc + "Field(env, instance, " + nativeFieldName +");",
                        "}",
                        "");
            }
        }

        if (!enumValues.isEmpty()) {
            generation.header("jsize jenum_" + scn + "_count();");
            generation.impl(
                    "jsize jenum_" + scn + "_count() {",
                    "    return " + enumValues.size() + ";",
                    "}");


            generation.header("jobject* jenum_" + scn + "_values();");

            String enumArrayField = scn + "_enum_values";

            generation.impl("jobject " + enumArrayField + "[" + enumValues.size() + "];");

            generation.init(
                    "    for (int i = 0; i < " + enumValues.size() + "; i++) {",
                    "        " + enumArrayField + "[i] = 0;",
                    "    }");

            int i = 0;
            for (String enumValueFieldName : enumValues) {
                generation.init("    "  + enumArrayField + "[" + (i++) + "] = " + enumValueFieldName + ";");
            }

            generation.destroy(
                    "    for (int i = 0; i < " + enumValues.size() + "; i++) {",
                    "        " + enumArrayField + "[i] = 0;",
                    "    }",
                    "");


            generation.impl(
                    "jobject* jenum_" + scn + "_values() {",
                    "    return " + enumArrayField + ";",
                    "}",
                    "");



        }

        Map<String, Method> sorter = new TreeMap<>();
        for (Method m : clazz.getMethods()) {
            if (m.isSynthetic()) {
                continue;
            }

            if (clazz.isEnum() && (m.getName().equals("valueOf") || m.getName().equals("values"))) {
                //TODO
                continue;
            }

            if (!m.isPublic() && member.isOnlyPublic()) {
                continue;
            }

            if (member.containsFilter(m.getName() + m.getSignature())) {
                continue;
            }

            sorter.put(m.getName() + m.getSignature(), m);
        }

        Map<String, Integer> counter = new TreeMap<>();
        for (Method m : sorter.values()) {

            String name = m.getName();
            Integer cnt = counter.get(name);
            if (cnt == null) {
                cnt = -1;
            }
            cnt++;
            counter.put(name, cnt);
            String nativeMethodRefName;
            if (name.startsWith("<clinit>")) {
                //We don't need to call this special method
                continue;
            }

            if (name.equals("<init>")) {
                nativeMethodRefName = scn + "_C_" + cnt;
            } else {
                nativeMethodRefName = scn + "_M_" + name + "_" + cnt;
            }

            String suffix = "";
            if (cnt > 0) {
                suffix = "_" + cnt;
            }

            String sig = m.getSignature();

            generation.impl("static jmethodID " + nativeMethodRefName + " = 0;");
            String refFunc = m.isStatic() ? "GetStaticMethodID": "GetMethodID";
            generation.init(
                    "    " + nativeMethodRefName + " = (*env) -> "+ refFunc +"(env, " + scn + ", \"" + name + "\", \"" + sig + "\");",
                    "    if (" + nativeMethodRefName + " == 0) {",
                    "        (*env) -> ExceptionClear(env);",
                    "        (*env) -> ThrowNew(env, Exception, \"cant find " + nat + "." + name + sig + "\");",
                    "        return JNI_FALSE;",
                    "    }",
                    "");
            generation.destroy(
                    "    "+ nativeMethodRefName + " = 0;"
            );


            generateMethod(generation, scn, m, name, nativeMethodRefName, suffix);

        }
    }

    private void generateMethod(Generation generation, String scn, Method m, String name, String nativeMethodRefName, String suffix) {


        if (name.equals("<init>")) {
            generation.header("jobject jnew_" + scn + suffix + "(JNIEnv * env" + getCParameters(m, NO_SUBSTITUTION) + ");");
            generation.impl(
                    "jobject jnew_" + scn + suffix + "(JNIEnv * env" + getCParameters(m, NO_SUBSTITUTION) + ") {",
                    "    jobject obj = (*env) -> NewObject(env, " + scn + ", " + nativeMethodRefName + getCParameterUse(m, NO_CASTS) + ");",
                    "    if (obj == NULL) {",
                    "        throw_internal_OutOfMemoryError(env, \"NewObject\");",
                    "    }",
                    "    return obj;",
                    "}",
                    ""
            );

            return;
        }

        String rctype = getCType(m.getReturnType());
        String acc = getCAccessor(m.getReturnType());
        String cast = "";
        if ("Object".equals(acc) && !"jobject".equals(rctype)) {
            cast = "(" + rctype + ") ";
        }

        if (!"void".equals(rctype)) {
            cast = "return " + cast;
        }

        if (m.isStatic()) {
            generation.header(rctype + " jcall_" + scn + "_" + name + suffix + "(JNIEnv * env" + getCParameters(m, NO_SUBSTITUTION) + ");");
            generation.impl(
                    rctype + " jcall_" + scn + "_" + name + suffix + "(JNIEnv * env" + getCParameters(m, NO_SUBSTITUTION) + ") {",
                    "    " + cast + "(*env) -> CallStatic" + acc + "Method(env, " + scn + ", " + nativeMethodRefName + getCParameterUse(m, NO_CASTS) + ");",
                    "}",
                    ""
            );

            return;
        }

        generation.header(rctype + " jcall_" + scn + "_" + name + suffix + "(JNIEnv * env, jobject instance" + getCParameters(m, NO_SUBSTITUTION) + ");");
        generation.impl(
                rctype + " jcall_" + scn + "_" + name + suffix + "(JNIEnv * env, jobject instance" + getCParameters(m, NO_SUBSTITUTION) + ") {",
                "    " + cast + "(*env) -> Call" + acc + "Method(env, instance, " + nativeMethodRefName + getCParameterUse(m, NO_CASTS) + ");",
                "}",
                ""
        );

    }

    protected void finish(Generation generation) throws IOException {
        File header = new File(headerOutput);
        File impl = new File(implOutput);

        header.delete();
        header.createNewFile();
        impl.delete();
        impl.createNewFile();

        try(FileOutputStream faos = new FileOutputStream(header)) {
            faos.write(generation.getHeader().getBytes(StandardCharsets.UTF_8));
        }

        generation.impl(
                "",
                "jboolean jnigenerator_init(JNIEnv * env) {",
                generation.getInit(),
                "    return JNI_TRUE;",
                "}");

        generation.impl(
                "",
                "void jnigenerator_destroy(JNIEnv * env) {",
                generation.getDestroy(),
                "}");

        try(FileOutputStream faos = new FileOutputStream(impl)) {
            faos.write(generation.getImpl().getBytes(StandardCharsets.UTF_8));
        }

    }


}

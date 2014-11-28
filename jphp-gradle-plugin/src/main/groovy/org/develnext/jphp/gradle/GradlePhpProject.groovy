package org.develnext.jphp.gradle

import org.develnext.jphp.core.compiler.jvm.JvmCompiler
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.FileCollectionDependency
import org.gradle.api.tasks.TaskDependency
import php.runtime.env.CompileScope
import php.runtime.env.Context
import php.runtime.env.Environment
import php.runtime.ext.support.Extension
import php.runtime.loader.dump.ModuleDumper
import php.runtime.reflection.ModuleEntity
import php.runtime.reflection.support.Entity

import java.nio.charset.Charset
import java.util.jar.JarFile
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class GradlePhpProject {
    final static COMPILED_JAR_NAME = "libs/jphp.compiled.jar";

    final Project project;
    final PhpGradleExtension config;

    final File buildDir;
    final File classesBuildDir;
    final File libsBuildDir;
    final File resourcesBuildDir;
    final File tmpBuildDir;

    List<File> includePaths;

    final Set<String> extensions;

    final CompileScope compileScope;

    final Set<String> jphpExtensions;

    GradlePhpProject(Project project) {
        this.project = project;
        this.config  = project.php;

        includePaths    = [project.file(config.srcDir)];
        buildDir        = project.buildDir;

        classesBuildDir   = new File(buildDir, "/classes");
        libsBuildDir      = new File(buildDir, "/libs");
        resourcesBuildDir = new File(buildDir, "/resources");
        tmpBuildDir       = new File(buildDir, "/tmp");

        extensions        = [] as HashSet;
        jphpExtensions    = [] as HashSet;

        config.extensions.each {
            extensions.add(it.toLowerCase());
        }

        compileScope = new CompileScope();


        def compileConfiguration = getOrCreateConfiguration("compile");

        def file = new File(project.projectDir, "/" + COMPILED_JAR_NAME);

        if (!file.exists()) {
            if (!file.parentFile.exists()) {
                file.parentFile.mkdirs();
            }

            def zipFile = new ZipOutputStream(new FileOutputStream(file));
            zipFile.close();
        }

        compileConfiguration.dependencies.add(project.dependencies.create(project.files(COMPILED_JAR_NAME)));
    }

    /**
     * @return
     */
    def compile() {
        cleanClasses();

        registerExtensionsJars();

        def phpClassesFile = new File(tmpBuildDir, '/$php_classes.list');

        if (phpClassesFile.parentFile != null && !phpClassesFile.parentFile.exists()) {
            phpClassesFile.parentFile.mkdirs();
        }

        def env = new Environment(compileScope);
        def classFiles = [] as List<File>;
        def modules    = [] as List<ModuleEntity>;

        eachSourceFile { File file ->
            def context     = new Context(file, Charset.forName(config.charset));
            def compiler    = new JvmCompiler(env, context);
            def module      = compiler.compile(false);

            modules.add(module);
            classFiles.addAll(writeToClasses(module));
        }

        def writer = new PrintWriter(phpClassesFile);
        try {
            classFiles.each { writer.write(it.path + "\n"); }
        } finally {
            writer.close();
        }

        // Save reflection info.
        def classesDump = new DataOutputStream(new FileOutputStream(new File(tmpBuildDir, '/$php_classes.dump')));

        classesDump.writeInt(modules.size());

        modules.each {
            def moduleDumper = new ModuleDumper(it.context, env, true);
            moduleDumper.includeData = false;

            moduleDumper.save(it, classesDump);
        }

        classesDump.close();
    }

    def build() {
        def file = new File(project.projectDir, "/" + COMPILED_JAR_NAME);

        if (file.parentFile != null && !file.parentFile.exists()) {
            file.parentFile.mkdirs();
        }

        if (file.exists()) {
            file.delete();
        }

        def jarFile = new ZipOutputStream(new FileOutputStream(file));

        def phpClassesFile = new File(tmpBuildDir, '/$php_classes.list');

        if (phpClassesFile.isFile()) {
            def scanner = new Scanner(phpClassesFile);

            while (scanner.hasNextLine()) {
                def it = new File(scanner.nextLine());

                if (it.isFile()) {
                    def path = it.path
                            .replace('\\', '/')
                            .replace(classesBuildDir.path.replace('\\', '/') + '/', '');

                    def entry = new ZipEntry(path);
                    jarFile.putNextEntry(entry)

                    def fis = new FileInputStream(it);
                    byte[] bytes = new byte[1024];
                    int length;

                    while ((length = fis.read(bytes)) >= 0) {
                        jarFile.write(bytes, 0, length);
                    }

                    jarFile.closeEntry();
                }
            }
        }

        jarFile.putNextEntry(new ZipEntry("JPHP-INF/standalone.extensions.list"));
        jarFile.write(jphpExtensions.join("\n").bytes);
        jarFile.closeEntry();

        jarFile.putNextEntry(new ZipEntry("JPHP-INF/classes.dump"));
            def fis = new FileInputStream(new File(tmpBuildDir, '/$php_classes.dump'));
            byte[] bytes = new byte[1024];
            int length;

            while ((length = fis.read(bytes)) >= 0) {
                jarFile.write(bytes, 0, length);
            }
        jarFile.closeEntry();

        jarFile.close();
    }

    def protected cleanClasses() {
        def phpClassesFile = new File(tmpBuildDir, '/$php_classes.list');

        if (phpClassesFile.isFile()) {
            def scanner = new Scanner(phpClassesFile);

            while (scanner.hasNextLine()) {
                def file = new File(scanner.nextLine());

                if (file.isFile()) {
                    file.delete();
                    file.deleteOnExit();
                }

                def list = file.parentFile != null ? file.parentFile.listFiles() : null;

                if (file.parentFile != null && list != null && list.length == 0) {
                    file.parentFile.delete();
                    file.parentFile.deleteOnExit();
                }
            }
        }
    }

    def protected getClassFileOfEntity(Entity entity) {
        def file = new File(classesBuildDir, "/" + entity.internalName + ".class");

        if (file.parentFile != null && !file.parentFile.exists()) {
            file.parentFile.mkdirs();
        }

        return file;
    }

    def File writeEntityToClassFile(Entity entity) {
        def file = getClassFileOfEntity(entity);
        def out  = new FileOutputStream(file, false);

        println "   Compile entity '${entity.internalName}' to '${file.path}'"

        try {
            out.write(entity.data);
        } finally {
            out.close();
        }

        return file;
    }

    def List<File> writeToClasses(ModuleEntity module) {
        def result = new ArrayList<File>();

        result.add(writeEntityToClassFile(module));

        for (el in module.classes) {
            result.add(writeEntityToClassFile(el));
        }

        for (el in module.functions) {
            result.add(writeEntityToClassFile(el));
        }

        for (el in module.closures) {
            result.add(writeEntityToClassFile(el));
        }

        for (el in module.generators) {
            result.add(writeEntityToClassFile(el));
        }

        return result;
    }

    def protected registerExtensionsJars() {
        jphpExtensions.clear();

        Configuration compileConfiguration = getOrCreateConfiguration("compile");

        compileConfiguration.each {
            registerExtensionJar(it);
        }
    }

    def protected registerExtensionJar(File jarFile) {
        def zipFile = new ZipFile(jarFile, ZipFile.OPEN_READ);
        def entry   = zipFile.getEntry("JPHP-INF/extensions.list");

        if (entry != null) {
            Scanner scanner = new Scanner(zipFile.getInputStream(entry));
            while (scanner.hasNext()) {
                String line = scanner.nextLine().trim();

                if (!line.isEmpty()) {
                    try {
                        Extension extension = (Extension) Class.forName(line).newInstance();
                        compileScope.registerExtension(extension);

                        jphpExtensions.add(extension.class.name);
                    } catch (ClassNotFoundException e) {
                        // nop.
                    }
                }
            }
        }

        zipFile.close();
    }

    def protected eachSourceFile(Closure c) {
        eachSourceFile(c, extensions);
    }

    def protected eachSourceFile(Closure c, Collection<String> extensions) {
        eachFile(c, includePaths, extensions);
    }

    def protected eachFile (Closure c, File path, Collection<String> extensions) {
        eachFile(c, path.listFiles().findAll(), extensions);
    }

    def protected eachFile (Closure c, Collection<File> paths, Collection<String> extensions) {
        paths.each {

            if (it.isDirectory()) {
                eachFile(c, it, extensions);
            } else {
                def ext = it.path.substring(it.path.lastIndexOf('.') + 1).toLowerCase();

                if (extensions == null || extensions.size() == 0 || extensions.contains(ext)) {
                    c.call(it);
                } else {
                    // nop
                }
            }
        }
    }

    protected Configuration getOrCreateConfiguration(String name) {
        ConfigurationContainer container = project.configurations
        container.findByName(name) ?: container.create(name)
    }
}

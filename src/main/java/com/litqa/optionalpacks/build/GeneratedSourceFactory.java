package com.litqa.optionalpacks.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes the loader independent runtime and the loader specific event hook into the generated Java source tree.
 */
public final class GeneratedSourceFactory {

    private static final String RUNTIME_TEMPLATE = """
            package @@PACKAGE@@.optionalpacks;

            import java.io.BufferedReader;
            import java.io.File;
            import java.io.FilterInputStream;
            import java.io.IOException;
            import java.io.InputStream;
            import java.io.InputStreamReader;
            import java.lang.reflect.Constructor;
            import java.lang.reflect.Field;
            import java.lang.reflect.InvocationHandler;
            import java.lang.reflect.Method;
            import java.lang.reflect.Proxy;
            import java.net.URL;
            import java.nio.charset.StandardCharsets;
            import java.nio.file.DirectoryStream;
            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.nio.file.Paths;
            import java.nio.file.StandardCopyOption;
            import java.util.ArrayList;
            import java.util.Collections;
            import java.util.Enumeration;
            import java.util.List;
            import java.util.Locale;
            import java.util.function.Supplier;
            import java.util.jar.JarEntry;
            import java.util.jar.JarFile;
            import java.util.logging.Level;
            import java.util.logging.Logger;
            import java.util.zip.ZipEntry;
            import java.util.zip.ZipFile;

            /**
             * Loader independent runtime for optional pack discovery.
             *
             * <p>Forge and NeoForge receive a temporary directory containing the bundled zip files and register it
             * through the loader pack repository. Forge 1.16.5 uses the older client resource pack finder API.</p>
             */
            public final class OptionalPacksRuntime {

                private static final Logger LOG = Logger.getLogger("Optional Packs");
                private static final String MANIFEST = "optional_packs/packs.txt";
                private static final String RESOURCE_PREFIX = "optional_packs/resourcepacks/";
                private static final String DATA_PREFIX = "optional_packs/datapacks/";
                private static final Object LOCK = new Object();

                private static Path resourceRuntimeDirectory;
                private static Path dataRuntimeDirectory;
                private static volatile String activeModId;

                private OptionalPacksRuntime() {
                }

                /**
                 * Handles a Forge or NeoForge AddPackFindersEvent without requiring a compile time dependency on the
                 * event class. The event object and mod id are supplied by the generated loader hook.
                 *
                 * @param event loader event instance
                 * @param modId workspace mod id
                 */
                public static void onAddPackFinders(Object event, String modId) {
                    activeModId = modId;
                    try {
                        Object packType = invokeNoArgs(event, "getPackType");
                        if (packType == null) {
                            return;
                        }
                        boolean client = isClientPackType(packType);
                        Path directory = runtimeDirectory(client);
                        if (directory == null) {
                            return;
                        }
                        Object source = createFolderRepositorySource(directory, packType);
                        if (source == null) {
                            LOG.warning("Optional Packs could not construct a FolderRepositorySource");
                            return;
                        }
                        if (invokeAddRepositorySource(event, source)) {
                            return;
                        }
                        LOG.warning("Optional Packs could not add a pack repository source");
                    } catch (Throwable throwable) {
                        LOG.log(Level.WARNING, "Optional Packs pack registration failed", throwable);
                    }
                }

                /**
                 * Prepares the temporary directory used by the direct Forge pack repository source code.
                 * The generated Forge hook calls this method before constructing a FolderRepositorySource.
                 *
                 * @param client {@code true} for client resources, {@code false} for server data
                 * @return directory containing the bundled zip files, or {@code null} on failure
                 */
                public static Path preparePackDirectory(boolean client) {
                    try {
                        return runtimeDirectory(client);
                    } catch (Throwable throwable) {
                        LOG.log(Level.WARNING, "Optional Packs could not prepare the pack directory", throwable);
                        return null;
                    }
                }

                public static Path preparePackDirectory(boolean client, String modId) {
                    activeModId = modId;
                    return preparePackDirectory(client);
                }

                /**
                 * Extracts the bundled resource packs into per-pack directories for Forge 1.16.5's FolderPack API.
                 *
                 * @return extracted pack directories
                 */
                public static List<File> prepareLegacyPackDirectories() {
                    List<File> result = new ArrayList<>();                    try {
                        Path directory = runtimeDirectory(true);
                        if (directory == null) {
                            return result;
                        }
                        Path extractRoot = Files.createTempDirectory("optional_packs_legacy_");
                        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.zip")) {
                            for (Path archive : stream) {
                                String fileName = archive.getFileName().toString();
                                String name = fileName.substring(0, fileName.length() - ".zip".length());
                                Path target = extractRoot.resolve(name);
                                try {
                                    extractZip(archive, target);
                                    if (Files.exists(target.resolve("pack.mcmeta"))) {
                                        result.add(target.toFile());
                                    }
                                } catch (Throwable throwable) {
                                    LOG.log(Level.WARNING,
                                            "Optional Packs legacy extraction failed for " + archive, throwable);
                                    deleteTree(target);
                                }
                            }
                        }
                    } catch (Throwable throwable) {
                        LOG.log(Level.WARNING, "Optional Packs could not prepare legacy pack directories", throwable);
                    }
                    return result;
                }

                public static List<File> prepareLegacyPackDirectories(String modId) {
                    activeModId = modId;
                    return prepareLegacyPackDirectories();
                }

                private static void extractZip(Path archive, Path target) throws IOException {
                    Files.createDirectories(target);
                    try (ZipFile zip = new ZipFile(archive.toFile())) {
                        Enumeration<? extends ZipEntry> entries = zip.entries();
                        while (entries.hasMoreElements()) {
                            ZipEntry entry = entries.nextElement();
                            Path destination = target.resolve(entry.getName()).normalize();
                            if (!destination.startsWith(target)) {
                                continue;
                            }
                            if (entry.isDirectory()) {
                                Files.createDirectories(destination);
                            } else {
                                Path parent = destination.getParent();
                                if (parent != null) {
                                    Files.createDirectories(parent);
                                }
                                try (InputStream input = zip.getInputStream(entry)) {
                                    Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
                                }
                            }
                        }
                    }
                }

                private static void deleteTree(Path path) {
                    try {
                        if (!Files.exists(path)) {
                            return;
                        }
                        List<Path> paths = new ArrayList<>();
                        try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                            for (Path child : stream) {
                                paths.add(child);
                            }
                        }
                        for (Path child : paths) {
                            deleteTree(child);
                        }
                        Files.deleteIfExists(path);
                    } catch (Throwable ignored) {
                        // Temporary files are best effort.
                    }
                }

                /**
                 * Copies the bundled zips into the game directory. Used by Forge 1.16.5, which predates the pack
                 * finder event.
                 */
                public static void copyToGameDirectory() {
                    try {
                        Path gameDirectory = gameDirectory();
                        if (gameDirectory == null) {
                            LOG.warning("Optional Packs could not determine the game directory");
                            return;
                        }
                        copyManifestTo(gameDirectory.resolve("resourcepacks"), "resourcepack");
                        copyManifestTo(gameDirectory.resolve("datapacks"), "datapack");
                    } catch (Throwable throwable) {
                        LOG.log(Level.WARNING, "Optional Packs could not copy packs into the game directory", throwable);
                    }
                }

                /**
                 * Forces the client resource pack repository to notice files copied by copyToGameDirectory.
                 */
                public static void reloadResourcePacks() {
                    try {
                        Class<?> minecraftClass = load("net.minecraft.client.Minecraft");
                        if (minecraftClass == null) {
                            return;
                        }
                        Object minecraft = minecraftClass.getMethod("getInstance").invoke(null);
                        if (minecraft == null) {
                            return;
                        }
                        Object repository = invokeNoArgs(minecraft, "getResourcePackRepository");
                        if (repository == null) {
                            return;
                        }
                        for (Method method : repository.getClass().getMethods()) {
                            if (method.getName().equals("reload") && method.getParameterCount() == 0) {
                                method.invoke(repository);
                                return;
                            }
                        }
                    } catch (Throwable throwable) {
                        LOG.log(Level.FINE, "Optional Packs could not reload the resource pack repository", throwable);
                    }
                }

                /**
                 * Registers the bundled resource packs with the Forge 1.16.5 client repository. The old API uses
                 * ResourcePackInfo objects and a pack finder instead of the modern FolderRepositorySource.
                 */
                public static void registerLegacyResourcePacks(String modId) {
                    activeModId = modId;
                    boolean registered = false;
                    try {
                        registered = tryRegisterLegacyPackFinder();
                    } catch (Throwable throwable) {
                        LOG.log(Level.WARNING, "Optional Packs legacy pack finder registration failed", throwable);
                    }
                    if (!registered) {
                        copyToGameDirectory();
                    }
                    reloadResourcePacks();
                }

                private static boolean tryRegisterLegacyPackFinder() throws Exception {
                    Class<?> minecraftClass = load("net.minecraft.client.Minecraft");
                    if (minecraftClass == null || load("net.minecraft.resources.FilePack") == null
                            || load("net.minecraft.resources.ResourcePackInfo") == null) {
                        return false;
                    }
                    Object minecraft = minecraftClass.getMethod("getInstance").invoke(null);
                    if (minecraft == null) {
                        return false;
                    }
                    Object repository = invokeNoArgs(minecraft, "getResourcePackRepository");
                    if (repository == null) {
                        return false;
                    }
                    Method addPackFinder = findMethod(repository.getClass(), "addPackFinder", 1);
                    if (addPackFinder == null) {
                        return false;
                    }
                    final Path directory = runtimeDirectory(true);
                    if (directory == null) {
                        return false;
                    }
                    Class<?> finderType = addPackFinder.getParameterTypes()[0];
                    Object finder = Proxy.newProxyInstance(finderType.getClassLoader(),
                            new Class<?>[]{finderType}, new InvocationHandler() {
                                @Override
                                public Object invoke(Object proxy, Method method, Object[] arguments) {
                                    if (arguments != null && arguments.length == 2) {
                                        registerLegacyPacks(directory, arguments[0], arguments[1]);
                                        return null;
                                    }
                                    return defaultProxyValue(proxy, method, arguments);
                                }
                            });
                    addPackFinder.invoke(repository, finder);
                    return true;
                }

                private static void registerLegacyPacks(Path directory, Object consumer, Object infoFactory) {
                    try {
                        Class<?> filePackClass = load("net.minecraft.resources.FilePack");
                        Class<?> infoClass = load("net.minecraft.resources.ResourcePackInfo");
                        Class<?> decoratorClass = load("net.minecraft.resources.IPackNameDecorator");
                        Class<?> priorityClass = load("net.minecraft.resources.ResourcePackInfo$Priority");
                        if (filePackClass == null || infoClass == null || decoratorClass == null
                                || priorityClass == null) {
                            LOG.warning("Optional Packs legacy resource pack classes are unavailable");
                            return;
                        }
                        Object decorator = staticField(decoratorClass, "BUILT_IN");
                        Object priority = staticField(priorityClass, "TOP");
                        Method create = findMethod(infoClass, "create", 6);
                        if (decorator == null || priority == null || create == null) {
                            LOG.warning("Optional Packs legacy ResourcePackInfo API is unavailable");
                            return;
                        }
                        Method accept = findMethod(consumer.getClass(), "accept", 1);
                        if (accept == null) {
                            return;
                        }
                        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.zip")) {
                            for (Path archive : stream) {
                                try {
                                    String fileName = archive.getFileName().toString();
                                    String id = "optional_packs_" + fileName.substring(0,
                                            fileName.length() - ".zip".length());
                                    Object filePack = createLegacyFilePack(filePackClass, archive.toFile(), id);
                                    if (filePack == null) {
                                        continue;
                                    }
                                    Object supplier = createLegacySupplier(filePack);
                                    Object info = createLegacyPackInfo(create, id, supplier, infoFactory, priority,
                                            decorator);
                                    if (info != null) {
                                        accept.invoke(consumer, info);
                                    }
                                } catch (Throwable throwable) {
                                    LOG.log(Level.WARNING,
                                            "Optional Packs legacy pack registration failed for " + archive, throwable);
                                }
                            }
                        }
                    } catch (Throwable throwable) {
                        LOG.log(Level.WARNING, "Optional Packs legacy pack registration failed", throwable);
                    }
                }

                private static Object createLegacyFilePack(Class<?> filePackClass, File file, String id) {
                    for (Constructor<?> constructor : filePackClass.getDeclaredConstructors()) {
                        Class<?>[] parameterTypes = constructor.getParameterTypes();
                        Object[] arguments = new Object[parameterTypes.length];
                        boolean usable = true;
                        for (int index = 0; index < parameterTypes.length; index++) {
                            Class<?> parameterType = parameterTypes[index];
                            if (parameterType.isInstance(file)) {
                                arguments[index] = file;
                            } else if (parameterType == boolean.class) {
                                arguments[index] = Boolean.FALSE;
                            } else if (parameterType == String.class) {
                                arguments[index] = id;
                            } else {
                                usable = false;
                                break;
                            }
                        }
                        if (usable) {
                            try {
                                constructor.setAccessible(true);
                                return constructor.newInstance(arguments);
                            } catch (Throwable throwable) {
                                LOG.log(Level.FINE, "Optional Packs FilePack constructor failed", throwable);
                            }
                        }
                    }
                    return null;
                }

                private static Object createLegacySupplier(final Object pack) {
                    return Proxy.newProxyInstance(Supplier.class.getClassLoader(),
                            new Class<?>[]{Supplier.class}, new InvocationHandler() {
                                @Override
                                public Object invoke(Object proxy, Method method, Object[] arguments) {
                                    if ("get".equals(method.getName())) {
                                        return pack;
                                    }
                                    return defaultProxyValue(proxy, method, arguments);
                                }
                            });
                }

                private static Object createLegacyPackInfo(Method create, String id, Object supplier, Object factory,
                                                           Object priority, Object decorator) {
                    Class<?>[] parameterTypes = create.getParameterTypes();
                    Object[] arguments = new Object[parameterTypes.length];
                    for (int index = 0; index < parameterTypes.length; index++) {
                        Class<?> parameterType = parameterTypes[index];
                        if (parameterType == String.class) {
                            arguments[index] = id;
                        } else if (parameterType == boolean.class) {
                            arguments[index] = Boolean.FALSE;
                        } else if (parameterType.isInstance(supplier)) {
                            arguments[index] = supplier;
                        } else if (parameterType.isInstance(factory)) {
                            arguments[index] = factory;
                        } else if (parameterType.isInstance(priority)) {
                            arguments[index] = priority;
                        } else if (parameterType.isInstance(decorator)) {
                            arguments[index] = decorator;
                        } else {
                            return null;
                        }
                    }
                    try {
                        return create.invoke(null, arguments);
                    } catch (Throwable throwable) {
                        LOG.log(Level.WARNING, "Optional Packs ResourcePackInfo.create failed", throwable);
                        return null;
                    }
                }

                private static Object defaultProxyValue(Object proxy, Method method, Object[] arguments) {
                    String name = method.getName();
                    if ("toString".equals(name)) {
                        return "optional_packs_proxy";
                    }
                    if ("hashCode".equals(name)) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(name)) {
                        return arguments != null && arguments.length == 1 && proxy == arguments[0];
                    }
                    return null;
                }

                private static boolean isClientPackType(Object packType) {
                    String typeName = String.valueOf(packType).toUpperCase(Locale.ROOT);
                    if (typeName.contains("CLIENT")) {
                        return true;
                    }
                    if (typeName.contains("SERVER")) {
                        return false;
                    }
                    Object directory = invokeNoArgs(packType, "getDirectory");
                    return directory != null && "assets".equals(String.valueOf(directory));
                }

                private static Path runtimeDirectory(boolean client) throws IOException {
                    synchronized (LOCK) {
                        Path existing = client ? resourceRuntimeDirectory : dataRuntimeDirectory;
                        if (existing != null) {
                            return existing;
                        }
                        Path directory = Files.createTempDirectory(client
                                ? "optional_packs_resourcepacks_"
                                : "optional_packs_datapacks_");
                        directory.toFile().deleteOnExit();
                        String prefix = client ? RESOURCE_PREFIX : DATA_PREFIX;
                        for (String[] entry : readManifest()) {
                            boolean resource = "resourcepack".equals(entry[0]);
                            if (resource != client) {
                                continue;
                            }
                            try (InputStream input = openResource(prefix + entry[1] + ".zip")) {
                                if (input == null) {
                                    LOG.warning("Optional Packs is missing bundled archive " + prefix + entry[1]);
                                    continue;
                                }
                                Files.copy(input, directory.resolve(entry[1] + ".zip"),
                                        StandardCopyOption.REPLACE_EXISTING);
                            }
                        }
                        if (client) {
                            resourceRuntimeDirectory = directory;
                        } else {
                            dataRuntimeDirectory = directory;
                        }
                        return directory;
                    }
                }

                private static void copyManifestTo(Path target, String kind) throws IOException {
                    Files.createDirectories(target);
                    boolean resource = "resourcepack".equals(kind);
                    String prefix = resource ? RESOURCE_PREFIX : DATA_PREFIX;
                    for (String[] entry : readManifest()) {
                        if (!kind.equals(entry[0])) {
                            continue;
                        }
                        try (InputStream input = openResource(prefix + entry[1] + ".zip")) {
                            if (input == null) {
                                continue;
                            }
                            Files.copy(input, target.resolve(entry[1] + ".zip"),
                                    StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }

                private static Path gameDirectory() {
                    Class<?> pathsClass = load("net.minecraftforge.fml.loading.FMLPaths");
                    if (pathsClass != null) {
                        Object gameDir = staticField(pathsClass, "GAMEDIR");
                        if (gameDir != null) {
                            Object value = invokeNoArgs(gameDir, "get");
                            if (value instanceof Path) {
                                return (Path) value;
                            }
                        }
                    }
                    String userDirectory = System.getProperty("user.dir");
                    return userDirectory == null ? null : Paths.get(userDirectory);
                }

                private static Object createFolderRepositorySource(Path directory, Object packType) {
                    Class<?> sourceClass = load("net.minecraft.server.packs.repository.FolderRepositorySource");
                    Class<?> packSourceClass = load("net.minecraft.server.packs.repository.PackSource");
                    if (sourceClass == null || packSourceClass == null) {
                        return null;
                    }
                    Object builtIn = staticField(packSourceClass, "BUILT_IN");
                    for (Constructor<?> constructor : sourceClass.getDeclaredConstructors()) {
                        Class<?>[] parameterTypes = constructor.getParameterTypes();
                        Object[] arguments = new Object[parameterTypes.length];
                        boolean usable = true;
                        for (int index = 0; index < parameterTypes.length; index++) {
                            Class<?> parameterType = parameterTypes[index];
                            if (parameterType.isInstance(directory)) {
                                arguments[index] = directory;
                            } else if (parameterType.isInstance(directory.toFile())) {
                                arguments[index] = directory.toFile();
                            } else if (parameterType.isInstance(packType)) {
                                arguments[index] = packType;
                            } else if (builtIn != null && parameterType.isInstance(builtIn)) {
                                arguments[index] = builtIn;
                            } else if (parameterType.getName().endsWith("DirectoryValidator")) {
                                Object validator = createDirectoryValidator(parameterType);
                                if (validator == null) {
                                    usable = false;
                                    break;
                                }
                                arguments[index] = validator;
                            } else {
                                usable = false;
                                break;
                            }
                        }
                        if (usable) {
                            try {
                                constructor.setAccessible(true);
                                return constructor.newInstance(arguments);
                            } catch (Throwable throwable) {
                                LOG.log(Level.FINE, "Optional Packs FolderRepositorySource constructor failed",
                                        throwable);
                            }
                        }
                    }
                    return null;
                }

                private static Object createDirectoryValidator(Class<?> validatorClass) {
                    for (Constructor<?> constructor : validatorClass.getDeclaredConstructors()) {
                        Class<?>[] parameterTypes = constructor.getParameterTypes();
                        if (parameterTypes.length != 1) {
                            continue;
                        }
                        Object argument = null;
                        String typeName = parameterTypes[0].getName();
                        if (parameterTypes[0].isInterface()
                                && ("java.nio.file.PathMatcher".equals(typeName)
                                || "java.util.function.Predicate".equals(typeName))) {
                            argument = Proxy.newProxyInstance(parameterTypes[0].getClassLoader(),
                                    new Class<?>[]{parameterTypes[0]}, rejectingHandler());
                        } else if (parameterTypes[0] == boolean.class) {
                            argument = Boolean.FALSE;
                        }
                        if (argument != null) {
                            try {
                                constructor.setAccessible(true);
                                return constructor.newInstance(argument);
                            } catch (Throwable throwable) {
                                LOG.log(Level.FINE, "Optional Packs DirectoryValidator constructor failed", throwable);
                            }
                        }
                    }
                    return null;
                }

                private static InvocationHandler rejectingHandler() {
                    return new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] arguments) {
                            String name = method.getName();
                            if ("toString".equals(name)) {
                                return "optional_packs_rejecting_matcher";
                            }
                            if ("hashCode".equals(name)) {
                                return System.identityHashCode(proxy);
                            }
                            if ("equals".equals(name)) {
                                return arguments != null && arguments.length == 1 && proxy == arguments[0];
                            }
                            return Boolean.FALSE;
                        }
                    };
                }

                private static boolean invokeAddRepositorySource(Object event, Object source) {
                    for (Method method : event.getClass().getMethods()) {
                        if ("addRepositorySource".equals(method.getName()) && method.getParameterCount() == 1) {
                            try {
                                method.invoke(event, source);
                                return true;
                            } catch (Throwable throwable) {
                                LOG.log(Level.WARNING, "Optional Packs addRepositorySource failed", throwable);
                                return false;
                            }
                        }
                    }
                    return false;
                }

                private static Method findMethod(Class<?> type, String name, int parameterCount) {
                    for (Method method : type.getMethods()) {
                        if (name.equals(method.getName()) && method.getParameterCount() == parameterCount) {
                            try {
                                method.setAccessible(true);
                            } catch (Throwable ignored) {
                                // The method may already be public and accessible.
                            }
                            return method;
                        }
                    }
                    for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                        for (Method method : current.getDeclaredMethods()) {
                            if (name.equals(method.getName()) && method.getParameterCount() == parameterCount) {
                                try {
                                    method.setAccessible(true);
                                } catch (Throwable ignored) {
                                    // The method may already be public and accessible.
                                }
                                return method;
                            }
                        }
                    }
                    return null;
                }

                private static List<String[]> readManifest() {
                    InputStream input = openResource(MANIFEST);
                    if (input == null) {
                        return Collections.emptyList();
                    }
                    List<String[]> entries = new ArrayList<>();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(input,
                            StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            int separator = line.indexOf('=');
                            if (separator <= 0 || separator == line.length() - 1) {
                                continue;
                            }
                            entries.add(new String[]{line.substring(0, separator), line.substring(separator + 1)});
                        }
                    } catch (IOException exception) {
                        LOG.log(Level.WARNING, "Optional Packs could not read its generated manifest", exception);
                    }
                    return entries;
                }

                private static InputStream openResource(String name) {
                    String modId = activeModId;
                    if (modId != null) {
                        InputStream modFileStream = openModFileResource(modId, name);
                        if (modFileStream != null) {
                            return modFileStream;
                        }
                    }
                    InputStream codeSourceStream = openCodeSourceResource(name);
                    if (codeSourceStream != null) {
                        return codeSourceStream;
                    }
                    ClassLoader loader = OptionalPacksRuntime.class.getClassLoader();
                    if (loader == null) {
                        loader = Thread.currentThread().getContextClassLoader();
                    }
                    return loader == null ? ClassLoader.getSystemResourceAsStream(name) : loader.getResourceAsStream(name);
                }

                /**
                 * Reads a mod jar resource through Forge or NeoForge's ModFile API. In production the mod class loader
                 * does not always expose nested resource paths through getResourceAsStream.
                 */
                private static InputStream openModFileResource(String modId, String name) {
                    try {
                        Class<?> modListClass = load("net.minecraftforge.fml.ModList");
                        if (modListClass == null) {
                            modListClass = load("net.neoforged.fml.ModList");
                        }
                        if (modListClass == null) {
                            return null;
                        }
                        Object modList = modListClass.getMethod("get").invoke(null);
                        Object modFileInfo = modListClass.getMethod("getModFileById", String.class)
                                .invoke(modList, modId);
                        if (modFileInfo == null) {
                            return null;
                        }
                        Object modFile = modFileInfo.getClass().getMethod("getFile").invoke(modFileInfo);
                        if (modFile == null) {
                            return null;
                        }
                        Method findResource = findMethod(modFile.getClass(), "findResource", 1);
                        if (findResource == null) {
                            return null;
                        }
                        Object result;
                        if (findResource.getParameterTypes()[0] == String[].class) {
                            result = findResource.invoke(modFile, (Object) new String[]{name});
                        } else {
                            result = findResource.invoke(modFile, name);
                        }
                        if (result instanceof Path && Files.exists((Path) result)) {
                            return Files.newInputStream((Path) result);
                        }
                    } catch (Throwable throwable) {
                        LOG.log(Level.FINE, "Optional Packs ModFile resource lookup failed: " + name, throwable);
                    }
                    return null;
                }

                /**
                 * Reads a resource from the code source location. This covers production jars whose class loader
                 * resource lookup is filtered while the ModFile API is unavailable.
                 */
                private static InputStream openCodeSourceResource(String name) {
                    try {
                        URL location = OptionalPacksRuntime.class.getProtectionDomain().getCodeSource().getLocation();
                        if (location == null) {
                            return null;
                        }
                        Path codeSource = Paths.get(location.toURI());
                        if (Files.isDirectory(codeSource)) {
                            Path resource = codeSource.resolve(name);
                            return Files.exists(resource) ? Files.newInputStream(resource) : null;
                        }
                        if (!Files.isRegularFile(codeSource)) {
                            return null;
                        }
                        final JarFile jar = new JarFile(codeSource.toFile());
                        JarEntry entry = jar.getJarEntry(name);
                        if (entry == null) {
                            jar.close();
                            return null;
                        }
                        return new FilterInputStream(jar.getInputStream(entry)) {
                            @Override
                            public void close() throws IOException {
                                try {
                                    super.close();
                                } finally {
                                    jar.close();
                                }
                            }
                        };
                    } catch (Throwable throwable) {
                        LOG.log(Level.FINE, "Optional Packs code source resource lookup failed: " + name, throwable);
                    }
                    return null;
                }

                private static Object invokeNoArgs(Object target, String methodName) {
                    if (target == null) {
                        return null;
                    }
                    try {
                        Method method = target.getClass().getMethod(methodName);
                        return method.invoke(target);
                    } catch (NoSuchMethodException ignored) {
                        return null;
                    } catch (Throwable throwable) {
                        LOG.log(Level.FINE, "Optional Packs reflection call failed: " + methodName, throwable);
                        return null;
                    }
                }

                private static Object staticField(Class<?> type, String fieldName) {
                    try {
                        Field field = type.getField(fieldName);
                        field.setAccessible(true);
                        return field.get(null);
                    } catch (Throwable throwable) {
                        LOG.log(Level.FINE, "Optional Packs static field is unavailable: " + fieldName, throwable);
                        return null;
                    }
                }

                private static Class<?> load(String name) {
                    try {
                        ClassLoader loader = OptionalPacksRuntime.class.getClassLoader();
                        return Class.forName(name, false, loader);
                    } catch (Throwable throwable) {
                        return null;
                    }
                }
            }
            """;

    private GeneratedSourceFactory() {
    }

    /**
     * Writes the runtime and hook sources for the current target.
     *
     * @param context build context
     * @param resourcePacks resource packs detected in this build
     * @param dataPacks data packs detected in this build
     * @throws IOException when generated source files cannot be written
     */
    public static void write(BuildContext context, List<NormalizedPack> resourcePacks,
                             List<NormalizedPack> dataPacks) throws IOException {
        Path packageDirectory = context.generatedJavaRoot()
                .resolve(context.packageName().replace('.', '/'))
                .resolve("optionalpacks");
        Files.createDirectories(packageDirectory);

        Files.writeString(packageDirectory.resolve("OptionalPacksRuntime.java"),
                RUNTIME_TEMPLATE.replace("@@PACKAGE@@", context.packageName()), StandardCharsets.UTF_8);
        Files.writeString(packageDirectory.resolve("OptionalPacksHook.java"),
                hookSource(context).replace("@@PACKAGE@@", context.packageName())
                        .replace("@@MODID@@", context.modId()),
                StandardCharsets.UTF_8);
    }

    private static String hookSource(BuildContext context) {
        return switch (context.target()) {
            case FORGE_LEGACY -> forgeLegacyRepositoryHook();
            case FORGE_MODERN -> forgeModernRepositoryHook();
            case FORGE_1165 -> legacyForgeHook();
            case NEOFORGE -> neoForgeHook(context.generatorName());
        };
    }

    private static String forgeLegacyRepositoryHook() {
        return """
                package @@PACKAGE@@.optionalpacks;

                import java.nio.file.Path;

                import net.minecraft.server.packs.PackType;
                import net.minecraft.server.packs.repository.FolderRepositorySource;
                import net.minecraft.server.packs.repository.PackSource;
                import net.minecraftforge.event.AddPackFindersEvent;
                import net.minecraftforge.eventbus.api.SubscribeEvent;
                import net.minecraftforge.fml.common.Mod;

                @Mod.EventBusSubscriber(modid = "@@MODID@@", bus = Mod.EventBusSubscriber.Bus.MOD)
                public final class OptionalPacksHook {

                    private OptionalPacksHook() {
                    }

                    @SubscribeEvent
                    public static void optionalPacksAddPackFinders(AddPackFindersEvent event) {
                        Path directory = OptionalPacksRuntime.preparePackDirectory(
                                event.getPackType() == PackType.CLIENT_RESOURCES, "@@MODID@@");
                        if (directory != null) {
                            event.addRepositorySource(new FolderRepositorySource(directory.toFile(), PackSource.BUILT_IN));
                        }
                    }
                }
                """;
    }

    private static String forgeModernRepositoryHook() {
        return """
                package @@PACKAGE@@.optionalpacks;

                import java.nio.file.Path;

                import net.minecraft.server.packs.PackType;
                import net.minecraft.server.packs.repository.FolderRepositorySource;
                import net.minecraft.server.packs.repository.PackSource;
                import net.minecraftforge.event.AddPackFindersEvent;
                import net.minecraftforge.eventbus.api.SubscribeEvent;
                import net.minecraftforge.fml.common.Mod;

                @Mod.EventBusSubscriber(modid = "@@MODID@@", bus = Mod.EventBusSubscriber.Bus.MOD)
                public final class OptionalPacksHook {

                    private OptionalPacksHook() {
                    }

                    @SubscribeEvent
                    public static void optionalPacksAddPackFinders(AddPackFindersEvent event) {
                        Path directory = OptionalPacksRuntime.preparePackDirectory(
                                event.getPackType() == PackType.CLIENT_RESOURCES, "@@MODID@@");
                        if (directory != null) {
                            event.addRepositorySource(new FolderRepositorySource(directory, event.getPackType(),
                                    PackSource.BUILT_IN));
                        }
                    }
                }
                """;
    }

    private static String legacyForgeHook() {
        return """
                package @@PACKAGE@@.optionalpacks;

                import java.io.File;
                import java.util.List;

                import net.minecraft.client.Minecraft;
                import net.minecraft.resources.FolderPack;
                import net.minecraft.resources.IPackNameDecorator;
                import net.minecraft.resources.ResourcePackInfo;
                import net.minecraftforge.api.distmarker.Dist;
                import net.minecraftforge.eventbus.api.SubscribeEvent;
                import net.minecraftforge.fml.common.Mod;
                import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

                @Mod.EventBusSubscriber(modid = "@@MODID@@", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
                public final class OptionalPacksHook {

                    private OptionalPacksHook() {
                    }

                    @SubscribeEvent
                    public static void optionalPacksClientSetup(FMLClientSetupEvent event) {
                        List<File> directories = OptionalPacksRuntime.prepareLegacyPackDirectories("@@MODID@@");
                        Minecraft minecraft = Minecraft.getInstance();
                        minecraft.getResourcePackRepository().addPackFinder((consumer, factory) -> {
                            for (File directory : directories) {
                                consumer.accept(ResourcePackInfo.create("optional_packs_" + directory.getName(), false,
                                        () -> new FolderPack(directory), factory, ResourcePackInfo.Priority.TOP,
                                        IPackNameDecorator.BUILT_IN));
                            }
                        });
                    }
                }
                """;
    }

    private static String neoForgeHook(String generatorName) {
        String imports;
        String annotation;
        if ("neoforge-1.20.4".equals(generatorName)) {
            imports = "import net.neoforged.bus.api.SubscribeEvent;\n"
                    + "import net.neoforged.fml.common.Mod;\n";
            annotation = "@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)";
        } else if ("neoforge-1.20.6".equals(generatorName)) {
            imports = "import net.neoforged.bus.api.SubscribeEvent;\n"
                    + "import net.neoforged.fml.common.EventBusSubscriber;\n";
            annotation = "@EventBusSubscriber(bus = EventBusSubscriber.Bus.MOD)";
        } else {
            imports = "import net.neoforged.bus.api.SubscribeEvent;\n"
                    + "import net.neoforged.fml.common.EventBusSubscriber;\n";
            annotation = "@EventBusSubscriber";
        }
        return "package @@PACKAGE@@.optionalpacks;\n\n"
                + "import net.neoforged.neoforge.event.AddPackFindersEvent;\n"
                + imports
                + "\n"
                + annotation + "\n"
                + "public final class OptionalPacksHook {\n"
                + "\n"
                + "    private OptionalPacksHook() {\n"
                + "    }\n"
                + "\n"
                + "    @SubscribeEvent\n"
                + "    public static void optionalPacksAddPackFinders(AddPackFindersEvent event) {\n"
                + "        OptionalPacksRuntime.onAddPackFinders(event, \"@@MODID@@\");\n"
                + "    }\n"
                + "}\n";
    }
}

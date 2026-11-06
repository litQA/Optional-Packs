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
            import java.io.IOException;
            import java.io.InputStream;
            import java.io.InputStreamReader;
            import java.lang.reflect.Constructor;
            import java.lang.reflect.Field;
            import java.lang.reflect.InvocationHandler;
            import java.lang.reflect.Method;
            import java.lang.reflect.Proxy;
            import java.nio.charset.StandardCharsets;
            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.nio.file.Paths;
            import java.nio.file.StandardCopyOption;
            import java.util.ArrayList;
            import java.util.Collections;
            import java.util.List;
            import java.util.Locale;
            import java.util.Optional;
            import java.util.logging.Level;
            import java.util.logging.Logger;

            /**
             * Loader independent runtime for optional pack discovery.
             *
             * <p>Forge and NeoForge receive a temporary directory containing the bundled zip files and register it
             * through the loader pack repository. Fabric's built-in pack API consumes directories, so the build stage
             * also prepares derived directory trees for Fabric workspaces.</p>
             */
            public final class OptionalPacksRuntime {

                private static final Logger LOG = Logger.getLogger("Optional Packs");
                private static final String MANIFEST = "optional_packs/packs.txt";
                private static final String RESOURCE_PREFIX = "optional_packs/resourcepacks/";
                private static final String DATA_PREFIX = "optional_packs/datapacks/";
                private static final Object LOCK = new Object();

                private static Path resourceRuntimeDirectory;
                private static Path dataRuntimeDirectory;

                private OptionalPacksRuntime() {
                }

                /**
                 * Handles a Forge or NeoForge AddPackFindersEvent without requiring a compile time dependency on the
                 * event class. The event object is supplied by the generated loader hook.
                 *
                 * @param event loader event instance
                 */
                public static void onAddPackFinders(Object event) {
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
                 * Registers Fabric built-in packs. Fabric's API reads pack directories under
                 * {@code resourcepacks/<id path>} rather than zip files.
                 *
                 * @param modId workspace mod id
                 */
                public static void registerFabricPacks(String modId) {
                    try {
                        Class<?> loaderClass = load("net.fabricmc.loader.api.FabricLoader");
                        Class<?> helperClass = load("net.fabricmc.fabric.api.resource.ResourceManagerHelper");
                        Class<?> locationClass = load("net.minecraft.resources.ResourceLocation");
                        Class<?> componentClass = load("net.minecraft.network.chat.Component");
                        Class<?> activationClass = load("net.fabricmc.fabric.api.resource.ResourcePackActivationType");
                        if (loaderClass == null || helperClass == null || locationClass == null
                                || componentClass == null || activationClass == null) {
                            LOG.warning("Optional Packs Fabric API or Minecraft classes are unavailable");
                            return;
                        }
                        Object loader = loaderClass.getMethod("getInstance").invoke(null);
                        Object containerOptional = loaderClass.getMethod("getModContainer", String.class)
                                .invoke(loader, modId);
                        Object container = containerOptional instanceof Optional
                                ? ((Optional<?>) containerOptional).orElse(null)
                                : containerOptional;
                        if (container == null) {
                            LOG.warning("Optional Packs could not find the Fabric mod container for " + modId);
                            return;
                        }
                        Object activation = staticField(activationClass, "NORMAL");
                        Method register = findFabricRegister(helperClass);
                        if (activation == null || register == null) {
                            LOG.warning("Optional Packs Fabric registration API is unavailable");
                            return;
                        }
                        for (String[] entry : readManifest()) {
                            boolean resource = "resourcepack".equals(entry[0]);
                            if (!resource && !"datapack".equals(entry[0])) {
                                continue;
                            }
                            String path = (resource ? "r_" : "d_") + entry[1];
                            try {
                                Object location = createResourceLocation(locationClass, "optional_packs", path);
                                Object displayName = createComponent(componentClass, entry[1]);
                                if (location == null || displayName == null) {
                                    LOG.warning("Optional Packs could not create Fabric metadata for " + entry[1]);
                                    continue;
                                }
                                Object registered = register.invoke(null, location, container, displayName, activation);
                                if (Boolean.FALSE.equals(registered)) {
                                    LOG.warning("Optional Packs Fabric pack registration returned false for " + path);
                                }
                            } catch (Throwable throwable) {
                                LOG.log(Level.WARNING, "Optional Packs Fabric pack registration failed for " + path,
                                        throwable);
                            }
                        }
                    } catch (Throwable throwable) {
                        LOG.log(Level.WARNING, "Optional Packs Fabric registration failed", throwable);
                    }
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

                private static Method findFabricRegister(Class<?> helperClass) {
                    Method fallback = null;
                    for (Method method : helperClass.getMethods()) {
                        if (!"registerBuiltinResourcePack".equals(method.getName())
                                || method.getParameterCount() != 4) {
                            continue;
                        }
                        if (fallback == null) {
                            fallback = method;
                        }
                        if (method.getParameterTypes()[2].getName().contains("Component")) {
                            return method;
                        }
                    }
                    return fallback;
                }

                private static Object createResourceLocation(Class<?> locationClass, String namespace, String path) {
                    String[] factoryNames = new String[]{"fromNamespaceAndPath", "tryBuild"};
                    for (String factoryName : factoryNames) {
                        try {
                            Method factory = locationClass.getMethod(factoryName, String.class, String.class);
                            return factory.invoke(null, namespace, path);
                        } catch (NoSuchMethodException ignored) {
                            // Try the next construction strategy.
                        } catch (Throwable throwable) {
                            LOG.log(Level.FINE, "Optional Packs ResourceLocation factory failed", throwable);
                        }
                    }
                    try {
                        Constructor<?> constructor = locationClass.getConstructor(String.class, String.class);
                        constructor.setAccessible(true);
                        return constructor.newInstance(namespace, path);
                    } catch (Throwable ignored) {
                        // Fall through to the single argument constructor.
                    }
                    try {
                        Constructor<?> constructor = locationClass.getConstructor(String.class);
                        constructor.setAccessible(true);
                        return constructor.newInstance(namespace + ":" + path);
                    } catch (Throwable throwable) {
                        LOG.log(Level.WARNING, "Optional Packs ResourceLocation construction failed", throwable);
                        return null;
                    }
                }

                private static Object createComponent(Class<?> componentClass, String text) {
                    try {
                        Method literal = componentClass.getMethod("literal", String.class);
                        return literal.invoke(null, text);
                    } catch (Throwable throwable) {
                        LOG.log(Level.WARNING, "Optional Packs Component construction failed", throwable);
                        return null;
                    }
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
                    ClassLoader loader = OptionalPacksRuntime.class.getClassLoader();
                    if (loader == null) {
                        loader = Thread.currentThread().getContextClassLoader();
                    }
                    return loader == null ? ClassLoader.getSystemResourceAsStream(name) : loader.getResourceAsStream(name);
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
            case FORGE -> forgeHook();
            case FORGE_1165 -> legacyForgeHook();
            case NEOFORGE -> neoForgeHook(context.generatorName());
            case FABRIC -> fabricHook();
        };
    }

    private static String forgeHook() {
        return """
                package @@PACKAGE@@.optionalpacks;

                import net.minecraftforge.event.AddPackFindersEvent;
                import net.minecraftforge.eventbus.api.SubscribeEvent;
                import net.minecraftforge.fml.common.Mod;

                @Mod.EventBusSubscriber(modid = "@@MODID@@", bus = Mod.EventBusSubscriber.Bus.MOD)
                public final class OptionalPacksHook {

                    private OptionalPacksHook() {
                    }

                    @SubscribeEvent
                    public static void optionalPacksAddPackFinders(AddPackFindersEvent event) {
                        OptionalPacksRuntime.onAddPackFinders(event);
                    }
                }
                """;
    }

    private static String legacyForgeHook() {
        return """
                package @@PACKAGE@@.optionalpacks;

                import net.minecraftforge.eventbus.api.SubscribeEvent;
                import net.minecraftforge.fml.common.Mod;
                import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
                import net.minecraftforge.fml.event.lifecycle.FMLConstructModEvent;

                @Mod.EventBusSubscriber(modid = "@@MODID@@", bus = Mod.EventBusSubscriber.Bus.MOD)
                public final class OptionalPacksHook {

                    private OptionalPacksHook() {
                    }

                    @SubscribeEvent
                    public static void optionalPacksConstruct(FMLConstructModEvent event) {
                        OptionalPacksRuntime.copyToGameDirectory();
                    }

                    @SubscribeEvent
                    public static void optionalPacksClientSetup(FMLClientSetupEvent event) {
                        OptionalPacksRuntime.reloadResourcePacks();
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
                + "        OptionalPacksRuntime.onAddPackFinders(event);\n"
                + "    }\n"
                + "}\n";
    }

    private static String fabricHook() {
        return """
                package @@PACKAGE@@.optionalpacks;

                import net.fabricmc.api.ModInitializer;

                public final class OptionalPacksHook implements ModInitializer {

                    @Override
                    public void onInitialize() {
                        OptionalPacksRuntime.registerFabricPacks("@@MODID@@");
                    }
                }
                """;
    }
}

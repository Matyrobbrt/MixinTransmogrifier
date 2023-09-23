package io.github.steelwoolmc.mixintransmog;

import cpw.mods.cl.ModuleClassLoader;
import cpw.mods.jarhandling.SecureJar;
import cpw.mods.modlauncher.Launcher;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.minecraftforge.fml.unsafe.UnsafeHacks;
import sun.misc.Unsafe;

import java.io.InputStream;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleReference;
import java.lang.module.ResolvedModule;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static cpw.mods.modlauncher.api.LamdbaExceptionUtils.rethrowFunction;
import static cpw.mods.modlauncher.api.LamdbaExceptionUtils.uncheck;
import static io.github.steelwoolmc.mixintransmog.Constants.LOG;

public final class InstrumentationHack {
    private static final Instrumentation INSTRUMENTATION = ByteBuddyAgent.install();
    private static final MethodHandles.Lookup TRUSTED_LOOKUP = uncheck(() -> {
        Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Unsafe unsafe = (Unsafe) theUnsafe.get(null);
        Field hackfield = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
        return (MethodHandles.Lookup) unsafe.getObject(unsafe.staticFieldBase(hackfield), unsafe.staticFieldOffset(hackfield));
    });
    private static final Path SELF_PATH = uncheck(() -> {
        URL jarLocation = InstrumentationHack.class.getProtectionDomain().getCodeSource().getLocation();
        return Path.of(jarLocation.toURI());
    });
    private static final String MIXIN_MODULE = "org.spongepowered.mixin";

    public static void inject() throws Throwable {
        Path mixinJarPath = SELF_PATH.resolve("fabric-mixin.jar");
        SecureJar mixinJar = SecureJar.from(mixinJarPath);

        Module mixinModule = Launcher.class.getModule().getLayer().findModule(MIXIN_MODULE).orElseThrow();
        ResolvedModule resolvedModule = Launcher.class.getModule().getLayer().configuration().findModule(MIXIN_MODULE).orElseThrow();
        Class<?> jarModuleReference = Class.forName("cpw.mods.cl.JarModuleFinder$JarModuleReference");
        ModuleReference reference = resolvedModule.reference();
        if (!jarModuleReference.isInstance(reference)) {
            LOG.error("Mixin module does not use a jar module reference");
            return;
        }
        // Replace code source
        SecureJar.ModuleDataProvider replacementProvider = mixinJar.moduleDataProvider();
        ModuleDescriptor replacementDescriptor = replacementProvider.descriptor();
        Field jarField = jarModuleReference.getDeclaredField("jar");
        UnsafeHacks.setField(jarField, reference, replacementProvider);
        Field descriptorField = ModuleReference.class.getDeclaredField("descriptor");
        UnsafeHacks.setField(descriptorField, reference, replacementDescriptor);
        Field locationField = ModuleReference.class.getDeclaredField("location");
        UnsafeHacks.setField(locationField, reference, mixinJar.getRootPath().toUri());
        // Add readability edge to the unnamed module, where classes from added packages are defined
        INSTRUMENTATION.redefineModule(mixinModule, Set.of(mixinModule.getClassLoader().getUnnamedModule()), Map.of(), Map.of(), Set.of(), Map.of());

        Set<String> mixinPackages = mixinJar.getPackages();
        // Good riddance, certs
        VarHandle certsField = TRUSTED_LOOKUP.findVarHandle(ClassLoader.class, "package2certs", ConcurrentHashMap.class);
        Map<String, ?> certs = (Map<String, ?>) certsField.get(mixinModule.getClassLoader());
        for (String pkg : mixinPackages) {
            certs.remove(pkg);
        }

        // Make modlauncher aware of added packages
        if (mixinModule.getClassLoader() instanceof ModuleClassLoader mcl) {
            Field packageLookupField = ModuleClassLoader.class.getDeclaredField("packageLookup");
            Map<String, ResolvedModule> packageLookup = UnsafeHacks.getField(packageLookupField, mcl);

            for (String pkg : mixinPackages) {
                packageLookup.put(pkg, resolvedModule);
            }
        }

        // Redefine existing classes
        ClassLoader bootClassLoader = Launcher.class.getClassLoader();
        List<ClassDefinition> redefinitions = new ArrayList<>();
        for (Class<?> cls : INSTRUMENTATION.getInitiatedClasses(bootClassLoader)) {
            if (mixinPackages.contains(cls.getPackageName())) {
                String path = cls.getName().replace('.', '/') + ".class";
                mixinJar.moduleDataProvider().open(path)
                    .map(rethrowFunction(InputStream::readAllBytes))
                    .ifPresent(bytes -> redefinitions.add(new ClassDefinition(cls, bytes)));
            }
        }
        LOG.info("Redefining {} mixin classes", redefinitions.size());
        INSTRUMENTATION.redefineClasses(redefinitions.toArray(ClassDefinition[]::new));
    }
}
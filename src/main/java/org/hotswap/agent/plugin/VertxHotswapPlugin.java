package org.hotswap.agent.plugin;

import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.logging.Logger;
import java.security.ProtectionDomain;

import org.hotswap.agent.annotation.*;
import org.hotswap.agent.command.ReflectionCommand;
import org.hotswap.agent.command.Scheduler;
import org.hotswap.agent.javassist.CannotCompileException;
import org.hotswap.agent.javassist.CtClass;
import org.hotswap.agent.javassist.CtMethod;
import org.hotswap.agent.javassist.NotFoundException;
import org.hotswap.agent.logging.AgentLogger;
import org.hotswap.agent.util.IOUtils;
import org.hotswap.agent.util.PluginManagerInvoker;
import org.hotswap.agent.command.Command;
import org.hotswap.agent.config.PluginManager;
import org.hotswap.agent.config.PluginConfiguration;
import org.hotswap.agent.javassist.CannotCompileException;
import org.hotswap.agent.javassist.CtClass;
import org.hotswap.agent.logging.AgentLogger;
import org.hotswap.agent.util.PluginManagerInvoker;
import org.hotswap.agent.util.classloader.*;
import org.hotswap.agent.HotswapAgent;

import org.objectweb.asm.*;

import static org.hotswap.agent.annotation.FileEvent.CREATE;
import static org.hotswap.agent.annotation.FileEvent.MODIFY;
import static org.hotswap.agent.annotation.LoadEvent.DEFINE;
import static org.hotswap.agent.annotation.LoadEvent.REDEFINE;
import static java.util.concurrent.TimeUnit.*;

@Plugin(name = "VertxHotswapPlugin", description = "Hotswap agent plugin that adds a restart of the Vert.x environment on class redefinition.",
		testedVersions = "Vert.x 3.0",
		expectedVersions = "Vert.x 3.0+")
public class VertxHotswapPlugin {
	private static AgentLogger log = AgentLogger.getLogger(VertxHotswapPlugin.class);
	private static final String timStampFieldStart = "__timeStamp__239_neverHappen";
	final Map<Class<?>, byte[]> reloadMap = new HashMap<Class<?>, byte[]>();

	private boolean isScheduled = false;

	@Init
	Scheduler scheduler;
	@Init
	ClassLoader appClassLoader;
	@Init
	PluginManager pluginManager;

	ReflectionCommand stopCommand;
	ReflectionCommand runCommand;
	Command hotswapCommand;

	@Init
	public void createHotswapCommand() {
		hotswapCommand = new Command() {
			@Override
			public void executeCommand() {
				pluginManager.hotswap(reloadMap);
			}

			@Override
			public String toString() {
				return "pluginManager.hotswap(" + Arrays.toString(reloadMap.keySet().toArray()) + ")";
			}
		};
	}

	@OnClassLoadEvent(classNameRegexp = "io.vertx.core.Starter")
		public static void transformTestEntityService(CtClass ctClass) throws NotFoundException, CannotCompileException {

			String src = PluginManagerInvoker.buildInitializePlugin(VertxHotswapPlugin.class);
			src += PluginManagerInvoker.buildCallPluginMethod(VertxHotswapPlugin.class, "registerService", "this", "java.lang.Object");
			ctClass.getDeclaredConstructor(new CtClass[0]).insertAfter(src);

			CtMethod shutdownMethod = CtMethod.make("public void shutdown() {log.info(\"Shutting down Vert.x for reload.\"); vertx.close();} ", ctClass);
			ctClass.addMethod(shutdownMethod);

			String restartSrc = "public void restart() \n";
			restartSrc += "{ \n";
			restartSrc += "log.info(\"Restarting Vert.x after reload.\"); \n";
			restartSrc += "String args = String.join(\" \", PROCESS_ARGS);\n";
			restartSrc += "run(args);\n";
			restartSrc += "}";
			CtMethod restartMethod = CtMethod.make(restartSrc, ctClass);
			ctClass.addMethod(restartMethod);

			log.debug("Vert.x Starter has been enhanced.");
		}

	@OnClassLoadEvent(classNameRegexp = ".*", events = {REDEFINE})
		public byte[] classReloaded(Class classBeingRedefined, byte[] classfileBuffer) throws NoSuchFieldException, IllegalAccessException, ClassNotFoundException, NoSuchMethodException, InstantiationException, InvocationTargetException {
			log.info("Class " + classBeingRedefined.getName() + " size before: " + classfileBuffer.length);
			if (classBeingRedefined != null) {
				try {
					Field callSiteArrayField = classBeingRedefined.getDeclaredField("$callSiteArray");
					callSiteArrayField.setAccessible(true);
					callSiteArrayField.set(null, null);
				} catch (Throwable ignored) {
					ignored.printStackTrace();
				}
				byte[] transformed = removeTimestampField(classfileBuffer);
				if (null == transformed) { transformed = classfileBuffer; }
				log.info("Class size after: " + transformed.length);
				scheduleRestart();
				return transformed;
			} else {
				log.info("Class size after: " + classfileBuffer.length);
				return classfileBuffer;
			}
		}

	private void scheduleRestart() {
		if (null == stopCommand) {
			stopCommand = new ReflectionCommand(reloadableStarter, "shutdown", new Object[0] );
			stopCommand.getClassName();
		}
		if (null == runCommand) {
			runCommand = new ReflectionCommand(reloadableStarter, "restart", new Object[0]);
			runCommand.getClassName();
		}
		scheduler.scheduleCommand(runCommand, 250, Scheduler.DuplicateSheduleBehaviour.SKIP);
		scheduler.scheduleCommand(stopCommand, 100, Scheduler.DuplicateSheduleBehaviour.SKIP);
	}

	@OnClassFileEvent(classNameRegexp = ".*", events = {CREATE, MODIFY})
		public void classCreated(String className, CtClass ctClass, URL url) throws NoSuchFieldException, IllegalAccessException, InterruptedException, NoSuchMethodException, InvocationTargetException, IOException, CannotCompileException {
			log.info("Class created: " + className);
			if (!ClassLoaderHelper.isClassLoaded(appClassLoader, ctClass.getName())) {
				log.trace("Class {} not loaded yet, no need for autoHotswap, skipped URL {}", ctClass.getName(), url);
				return;
			}

			log.debug("Class {} will be reloaded from URL {}", ctClass.getName(), url);

			// search for a class to reload
			Class clazz;
			try {
				clazz  = appClassLoader.loadClass(ctClass.getName());
			} catch (ClassNotFoundException e) {
				log.warning("Hotswapper tries to reload class {}, which is not known to application classLoader {}.",
						ctClass.getName(), appClassLoader);
				return;
			}

			synchronized (reloadMap) {
				reloadMap.put(clazz, ctClass.toBytecode());
			}

			scheduler.scheduleCommand(hotswapCommand, 100, Scheduler.DuplicateSheduleBehaviour.SKIP);

		}

	public void registerService(Object reloadableStarter) {
		this.reloadableStarter = reloadableStarter;

	}

	// the service, please note that agentexamples cannot be typed here
	public static Object reloadableStarter;

	private static boolean hasTimestampField(byte[] buffer) {
		try {
			return new String(buffer, "ISO-8859-1").contains(timStampFieldStart);
		} catch (Throwable e) {
			return true;
		}
	}

	private static byte[] removeTimestampField(byte[] newBytes) {
		if (!hasTimestampField(newBytes)) {
			return null;
		}

		final boolean[] changed = new boolean[]{false};
		final ClassWriter writer = new ClassWriter(0);
		new ClassReader(newBytes).accept(new TimestampFieldRemover(writer, changed), 0);
		if (changed[0]) {
			return writer.toByteArray();
		}
		return null;
	}

	private static class TimestampFieldRemover extends ClassAdapter {
		private final boolean[] changed;

		public TimestampFieldRemover(ClassWriter writer, boolean[] changed) {
			super(writer);
			this.changed = changed;
		}

		@Override
		public FieldVisitor visitField(int i, String name, String s1, String s2, Object o) {
			if (name.startsWith(timStampFieldStart)) {
				//remove the field
				changed[0] = true;
				return null;
			}
			return super.visitField(i, name, s1, s2, o);
		}

		@Override
		public MethodVisitor visitMethod(int i, String name, String s1, String s2, String[] strings) {
			final MethodVisitor mw = super.visitMethod(i, name, s1, s2, strings);
			if ("<clinit>".equals(name)) {
				//remove field's static initialization
				return new MethodAdapter(mw) {
					@Override
					public void visitFieldInsn(int opCode, String s, String name, String desc) {
						if (name.startsWith(timStampFieldStart) && opCode == Opcodes.PUTSTATIC) {
							visitInsn(Type.LONG_TYPE.getDescriptor().equals(desc) ? Opcodes.POP2 : Opcodes.POP);
						} else {
							super.visitFieldInsn(opCode, s, name, desc);
						}
					}
				};
			}
			return mw;
		}
	}
}


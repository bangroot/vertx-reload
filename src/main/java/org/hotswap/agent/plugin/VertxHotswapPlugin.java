package org.hotswap.agent.plugin;

import static java.util.concurrent.TimeUnit.*;

import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;

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
import org.hotswap.agent.command.ReflectionCommand;
import org.hotswap.agent.command.Scheduler;
import org.objectweb.asm.*;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.security.ProtectionDomain;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.net.URI;
import java.util.concurrent.CountDownLatch;

import static org.hotswap.agent.annotation.FileEvent.CREATE;
import static org.hotswap.agent.annotation.FileEvent.MODIFY;
import static org.hotswap.agent.annotation.LoadEvent.DEFINE;
import static org.hotswap.agent.annotation.LoadEvent.REDEFINE;

@Plugin(name = "VertxHotswapPlugin", description = "Hotswap agent plugin that adds a restart of the Vert.x environment on class redefinition.",
		testedVersions = "Vert.x 3.0",
		expectedVersions = "Vert.x 3.0+")
public class VertxHotswapPlugin {
	private static AgentLogger log = AgentLogger.getLogger(VertxHotswapPlugin.class);
  private static final String timStampFieldStart = "__timeStamp__239_neverHappen";

	private boolean isScheduled = false;

	@Init
	Scheduler scheduler;
	@Init
	ClassLoader appClassLoader;

	Command stopCommand;
	Command runCommand;

	@OnClassLoadEvent(classNameRegexp = "io.vertx.core.Starter")
		public static void transformTestEntityService(CtClass ctClass) throws NotFoundException, CannotCompileException {

			// You need always find a place from which to initialize the plugin.
			// Initialization will create new plugin instance (notice that transformTestEntityService is
			// a static method), inject agent services (@Inject) and register event listeners (@Transform and @Watch).
			String src = PluginManagerInvoker.buildInitializePlugin(VertxHotswapPlugin.class);

			// If you need to call a plugin method from application context, there are some issues
			// Always think about two different classloaders - application and agent/plugin. The parameter
			// here cannot be of type TestEntityService because the plugin does not know this type at runtime
			// (although agentexamples will compile here!). If you call plugin method, usually only basic java types (java.lang.*)
			// are safe.
			src += PluginManagerInvoker.buildCallPluginMethod(VertxHotswapPlugin.class, "registerService", "this", "java.lang.Object");

			// do enhance default constructor using javaasist. Plugin manager (TransformHandler) will use enhanced class
			// to replace actual bytecode.
			ctClass.getDeclaredConstructor(new CtClass[0]).insertAfter(src);

			CtMethod shutdownMethod = CtMethod.make("public void shutdown() {log.info(\"Shutting down Vert.x for reload.\"); vertx.close();} ", ctClass);
			ctClass.addMethod(shutdownMethod);

			String restartSrc = "public void restart() \n";
			restartSrc += "{ \n";
			restartSrc += "log.info(\"Restarting Vert.x after reload.\"); \n";
			//restartSrc += "Runnable r1 = new Runnable() { \n";
			//restartSrc += "public void run() { \n";
			restartSrc += "String args = String.join(\" \", PROCESS_ARGS);\n";
			restartSrc += "run(args);\n";
			//restartSrc += "}\n";
			//restartSrc += "}; \n";
			//restartSrc += "new Thread(r1).start();\n";
			restartSrc += "}";
			CtMethod restartMethod = CtMethod.make(restartSrc, ctClass);
			ctClass.addMethod(restartMethod);

			log.debug("Vert.x Starter has been enhanced.");
		}

		public void restartVertx() {
			Runnable r1 = new Runnable() {
				public void run() {
					try {
						reloadableStarter.getClass().getDeclaredMethod("restart", new Class[0]).invoke(reloadableStarter, new Object[0]);
					} catch (Exception ex) {

					}
				}
			};
			new Thread(r1).start();
		}

	@OnClassLoadEvent(classNameRegexp = ".*", events = {REDEFINE})
		public byte[] classReloaded(Class classBeingRedefined, byte[] classfileBuffer) throws NoSuchFieldException, IllegalAccessException, ClassNotFoundException, NoSuchMethodException, InstantiationException, InvocationTargetException {
				log.info("Class size before: " + classfileBuffer.length);
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
					return transformed;
        } else {
					log.info("Class size after: " + classfileBuffer.length);
					return classfileBuffer;
				}
		}

	@OnClassFileEvent(classNameRegexp = ".*", events = {CREATE, MODIFY})
		public void classCreated(String className) throws NoSuchFieldException, IllegalAccessException, InterruptedException, NoSuchMethodException, InvocationTargetException {
			log.info("Class created: " + className);
			if (null == stopCommand) {
				stopCommand = new ReflectionCommand(reloadableStarter, "shutdown", new Object[0] );
			}
			if (null == runCommand) {
				runCommand = new ReflectionCommand(this, "restartVertx", new Object[0]);
			}
			//if (!isScheduled) {
				//reloadableStarter.getClass().getDeclaredMethod("shutdown").invoke(reloadableStarter);
				//reloadableStarter.getClass().getDeclaredMethod("restart").invoke(reloadableStarter);
				//reloadableStarter.getClass().getDeclaredMethod("stopAndRestart").invoke(reloadableStarter);
				scheduler.scheduleCommand(runCommand, 2000, Scheduler.DuplicateSheduleBehaviour.SKIP);
				scheduler.scheduleCommand(stopCommand, 100, Scheduler.DuplicateSheduleBehaviour.SKIP);
				//isScheduled = true;
			//}
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


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
import org.hotswap.agent.javassist.NotFoundException;
import org.hotswap.agent.logging.AgentLogger;
import org.hotswap.agent.util.IOUtils;
import org.hotswap.agent.util.PluginManagerInvoker;
import org.hotswap.agent.command.Command;
import org.hotswap.agent.command.ReflectionCommand;
import org.hotswap.agent.command.Scheduler;

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

	private boolean isScheduled = false;

	@Init
	Scheduler scheduler;
	@Init
	ClassLoader appClassLoader;

	Command stopCommand;
	Command runCommand;

	@OnClassLoadEvent(classNameRegexp = "com.github.bangroot.vertx.RestartableStarter")
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

			log.debug("Vert.x Starter has been enhanced.");
		}

	@OnClassLoadEvent(classNameRegexp = ".*", events = REDEFINE)
		public void classReloaded(Class classBeingRedefined) throws NoSuchFieldException, IllegalAccessException, ClassNotFoundException, NoSuchMethodException, InstantiationException, InvocationTargetException {
		}

	@OnClassFileEvent(classNameRegexp = ".*", events = {CREATE, MODIFY})
		public synchronized void classCreated(String className) throws NoSuchFieldException, IllegalAccessException, InterruptedException, NoSuchMethodException, InvocationTargetException {
			log.info("Class created: " + className);
			if (null == stopCommand) {
				stopCommand = new ReflectionCommand(reloadableStarter, "shutdown", new Object[0] );
			}
			if (null == runCommand) {
				runCommand = new ReflectionCommand(reloadableStarter, "restart", new Object[0]);
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

}


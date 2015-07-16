package com.github.bangroot.vertx;

import static java.util.concurrent.TimeUnit.*;

import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import org.springsource.loaded.ReloadEventProcessorPlugin;
import java.security.ProtectionDomain;
import org.springsource.loaded.LoadtimeInstrumentationPlugin;
import org.springsource.loaded.IsReloadableTypePlugin;
import org.springsource.loaded.agent.ReloadDecision;
import org.springsource.loaded.TypeRegistry;
import java.util.logging.Logger;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import org.springsource.loaded.ChildClassLoader;

public class VertxSpringLoadedPlugin implements ReloadEventProcessorPlugin, IsReloadableTypePlugin, LoadtimeInstrumentationPlugin {
	private static Logger log = Logger.getLogger(VertxSpringLoadedPlugin.class.getName());

	static List<ReloadListener> listeners = new ArrayList<ReloadListener>();

	ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

	boolean isScheduled = false;

	Set<String> seenTypes = new HashSet<String>();

	public VertxSpringLoadedPlugin() {
	}

	public boolean shouldRerunStaticInitializer(String typename, Class<?> clazz, String encodedTimestamp) {
		return false;
	}

	public void reloadEvent(String typename, Class<?> clazz, String encodedTimestamp) {
		if (!isScheduled) {
			isScheduled = true;
			final Runnable notifier = new Runnable() {
				public void run() { 
					for(ReloadListener listener: listeners) {
						log.fine("Firing listener!");
						listener.reloadComplete();
					}
					isScheduled = false;
				}
			};
			executor.schedule(notifier, 1, SECONDS);
		}
	}

	public ReloadDecision shouldBeMadeReloadable(TypeRegistry typeRegistry, String typename, ProtectionDomain protectionDomain, byte[] bytes) {
		if (null == typename) {
			return ReloadDecision.NO;
		}
		
		return ReloadDecision.PASS;
	}

	public static void registerListener(ReloadListener listener) {
		log.fine("Got a new listener!");
		listeners.add(listener);
	}

	public boolean accept(String slashedTypeName, ClassLoader classLoader, ProtectionDomain protectionDomain, byte[] bytes) {
		if(null != slashedTypeName && null != classLoader && classLoader instanceof ChildClassLoader) { 
			log.info("ACCEPT CALLED: " + slashedTypeName + " CL: " + classLoader.getClass().getName());
			String simpleType = slashedTypeName.split("\\$")[0];
			if (seenTypes.contains(simpleType) && !isScheduled) {
				for (ReloadListener listener: listeners) {
					listener.reloadStarted();
				}
			} else {
				seenTypes.add(simpleType);
			}
			return false;
		} else {
			return false;
		}
	}

	public byte[] modify(String slashedClassName, ClassLoader classLoader, byte[] bytes) {
		log.info("MODIFY CALLED: " + slashedClassName);
		return bytes;
	}
}


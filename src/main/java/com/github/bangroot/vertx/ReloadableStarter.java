package com.github.bangroot.vertx;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.MalformedURLException;
import java.net.URLClassLoader;
import java.io.File;
import io.vertx.core.Starter;
import io.vertx.core.impl.Args;
import java.util.logging.Logger;
import java.util.logging.Level;
import org.springsource.loaded.agent.SpringLoadedPreProcessor;

public class ReloadableStarter extends Starter implements ReloadListener {
	private static Logger log = Logger.getLogger(ReloadableStarter.class.getName());
	private String[] sargs;

  public static void main(String[] sargs) {
		SpringLoadedPreProcessor.registerGlobalPlugin(new VertxSpringLoadedPlugin());
    Args args = new Args(sargs);

    String extraCP = args.map.get("-cp");
    if (extraCP != null) {
      // If an extra CP is specified (e.g. to provide cp to a jar or cluster.xml) we must create a new classloader
      // and run the starter using that so it's visible to the rest of the code
      String[] parts = extraCP.split(File.pathSeparator);
      URL[] urls = new URL[parts.length];
      for (int p = 0; p < parts.length; p++) {
        String part = parts[p];
        File file = new File(part);
        try {
          URL url = file.toURI().toURL();
          urls[p] = url;
        } catch (MalformedURLException e) {
          throw new IllegalStateException(e);
        }
      }
      ClassLoader icl = new URLClassLoader(urls, Starter.class.getClassLoader());
      ClassLoader oldTCCL = Thread.currentThread().getContextClassLoader();
      Thread.currentThread().setContextClassLoader(icl);
      try {
        Class<?> clazz = icl.loadClass(ReloadableStarter.class.getName());
        Object instance = clazz.newInstance();
				VertxSpringLoadedPlugin.registerListener((ReloadListener) instance);
        Method run = clazz.getMethod("run", Args.class, String[].class);
        run.invoke(instance, args, sargs);
      } catch (Exception e) {
        throw new IllegalStateException(e);
      } finally {
        Thread.currentThread().setContextClassLoader(oldTCCL);
      }
    } else {
      // No extra CP, just invoke directly
			ReloadableStarter starter = new ReloadableStarter();
			VertxSpringLoadedPlugin.registerListener(starter);
      starter.run(args, sargs);
    }
  }

	public void run(Args args, String[] sargs) {
		this.sargs = sargs;
		log.info("Running new Vert.x instance");
		super.run(args, sargs);
	}

	public void reloadComplete() {
		try {
			log.info("Running Vert.x due to class change. With args: " + String.join(" ", sargs));
			run(new Args(this.sargs), this.sargs);
		} catch (Throwable t) {
			log.log(Level.SEVERE, "Error restarting Vert.x.", t);
		}
	}

	public void reloadStarted() {
		try {
			log.info("Stopping Vert.x due to class change.");
			vertx.close();
			vertx = null;
		} catch (Throwable t) {
			log.log(Level.SEVERE, "Error stopping Vert.x.", t);
		}

	}
}

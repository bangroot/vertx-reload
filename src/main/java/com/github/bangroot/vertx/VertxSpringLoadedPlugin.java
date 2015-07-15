package com.github.bangroot.vertx;

import org.springsource.loaded.ReloadEventProcessorPlugin;
import java.security.ProtectionDomain;
import org.springsource.loaded.LoadtimeInstrumentationPlugin;
import org.springsource.loaded.IsReloadableTypePlugin;
import org.springsource.loaded.agent.ReloadDecision;
import org.springsource.loaded.TypeRegistry;

public class VertxSpringLoadedPlugin implements ReloadEventProcessorPlugin, IsReloadableTypePlugin {

	public boolean shouldRerunStaticInitializer(String typename, Class<?> clazz, String encodedTimestamp) {
		return false;
	}

	public void reloadEvent(String typename, Class<?> clazz, String encodedTimestamp) {
	}

	public ReloadDecision shouldBeMadeReloadable(TypeRegistry typeRegistry, String typename, ProtectionDomain protectionDomain, byte[] bytes) {
		if (null == typename) {
			return ReloadDecision.NO;
		}
		
		return ReloadDecision.PASS;
	}
}


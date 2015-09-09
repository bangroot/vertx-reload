package com.github.bangroot.vertx;

import org.objectweb.asm.*;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.security.ProtectionDomain;
import org.hotswap.agent.HotswapAgent;
import org.hotswap.agent.logging.AgentLogger;

public class GroovyResetHotswapAgent {
	private static AgentLogger log;
  private static final String timStampFieldStart = "__timeStamp__239_neverHappen";

  private static boolean initialized;

  public static void premain(String options, Instrumentation inst) {
    // Handle duplicate agents
    if (initialized) {
      return;
    }
    initialized = true;
		HotswapAgent.premain(options, inst);
    inst.addTransformer(new ClassFileTransformer() {
      public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
        if (classBeingRedefined != null) {
          try {
						System.out.println("Clearing call site array for: " + className);
            Field callSiteArrayField = classBeingRedefined.getDeclaredField("$callSiteArray");
            callSiteArrayField.setAccessible(true);
            callSiteArrayField.set(null, null);
          } catch (Throwable ignored) {
          }
        }
        return removeTimestampField(classfileBuffer);
      }

    });
		log = AgentLogger.getLogger(GroovyResetHotswapAgent.class);
  }

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

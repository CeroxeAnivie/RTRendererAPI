package top.ceroxe.rt.renderer.api;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

public final class RendererApiAbiSnapshot {
   private static final String PACKAGE_PREFIX = "top.ceroxe.rt.renderer.";

   private RendererApiAbiSnapshot() {
   }

   public static void main(String[] arguments) throws Exception {
      boolean write = arguments.length == 3 && "--write".equals(arguments[0]);
      if ((write || arguments.length == 2) && (!write || arguments.length == 3)) {
         int offset = write ? 1 : 0;
         Path jar = Path.of(arguments[offset]).toAbsolutePath().normalize();
         Path baseline = Path.of(arguments[offset + 1]).toAbsolutePath().normalize();
         String snapshot = snapshot(jar);
         if (write) {
            Files.createDirectories(baseline.getParent());
            Files.writeString(baseline, snapshot, StandardCharsets.UTF_8);
            System.out.println("Updated renderer API ABI baseline: " + String.valueOf(baseline));
         } else if (!Files.isRegularFile(baseline)) {
            throw new AssertionError("renderer API ABI baseline is missing: " + String.valueOf(baseline));
         } else {
            String expected = Files.readString(baseline, StandardCharsets.UTF_8).replace("\r\n", "\n");
            if (!expected.equals(snapshot)) {
               throw new AssertionError("renderer API/ABI changed without an explicit baseline update; run :renderer-api:updateRendererApiAbiBaseline after reviewing compatibility");
            } else {
               System.out.println("RendererApiAbiSnapshot passed: " + String.valueOf(baseline));
            }
         }
      } else {
         throw new IllegalArgumentException("expected [--write] <renderer-api.jar> <baseline>");
      }
   }

   private static String snapshot(Path jar) throws Exception {
      List<String> classNames = classNames(jar);
      ArrayList<String> lines = new ArrayList<>();
      try (URLClassLoader loader = new URLClassLoader(
              new URL[]{jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
         for(String className : classNames) {
            Class<?> type = Class.forName(className, false, loader);
            if (publicOrProtected(type.getModifiers())) {
               describe(type, lines);
            }
         }
      }
      return String.join("\n", lines) + "\n";
   }

   private static List<String> classNames(Path jar) throws IOException {
      ArrayList<String> names = new ArrayList<>();
      try (JarFile archive = new JarFile(jar.toFile())) {
         archive.stream()
                 .map(ZipEntry::getName)
                 .filter(name -> name.endsWith(".class") && !name.equals("module-info.class"))
                 .map(name -> name.substring(0, name.length() - 6).replace('/', '.'))
                 .filter(name -> name.startsWith(PACKAGE_PREFIX))
                 .sorted()
                 .forEach(names::add);
      }
      return List.copyOf(names);
   }

   private static void describe(Class<?> type, List<String> lines) {
      lines.add("TYPE " + declaration(type));
      if (type.isEnum()) {
         lines.add("  ENUM " + String.join(",", Arrays.stream(type.getEnumConstants())
                 .map(Object::toString)
                 .toList()));
      }

      if (type.isRecord()) {
         RecordComponent[] components = type.getRecordComponents();

         for(RecordComponent component : components) {
            String details10001 = component.getName();
            lines.add("  RECORD " + details10001 + ":" + component.getGenericType().getTypeName());
         }
      }

      Arrays.stream(type.getPermittedSubclasses() == null
                      ? new Class<?>[0]
                      : type.getPermittedSubclasses())
              .map(Class::getName)
              .sorted()
              .forEach(name -> lines.add("  PERMITS " + name));
      Arrays.stream(type.getDeclaredFields()).filter((field) -> publicOrProtected(field.getModifiers()) && !field.isSynthetic()).sorted(Comparator.comparing(Field::getName).thenComparing((field) -> field.getGenericType().getTypeName())).map(RendererApiAbiSnapshot::fieldSignature).forEach((signature) -> lines.add("  FIELD " + signature));
      Arrays.stream(type.getDeclaredConstructors()).filter((constructor) -> publicOrProtected(constructor.getModifiers()) && !constructor.isSynthetic()).map(RendererApiAbiSnapshot::constructorSignature).sorted().forEach((signature) -> lines.add("  CTOR " + signature));
      Arrays.stream(type.getDeclaredMethods()).filter((method) -> publicOrProtected(method.getModifiers()) && !method.isSynthetic() && !method.isBridge()).map(RendererApiAbiSnapshot::methodSignature).sorted().forEach((signature) -> lines.add("  METHOD " + signature));
   }

   private static String declaration(Class<?> type) {
      StringBuilder result = (new StringBuilder(Modifier.toString(type.getModifiers()))).append(' ');
      if (type.isAnnotation()) {
         result.append("@interface ");
      } else if (type.isEnum()) {
         result.append("enum ");
      } else if (type.isRecord()) {
         result.append("record ");
      } else if (type.isInterface()) {
         result.append("interface ");
      } else {
         result.append("class ");
      }

      result.append(type.getName()).append(typeVariables(type.getTypeParameters()));
      Type superclass = type.getGenericSuperclass();
      if (superclass != null && superclass != Object.class && !type.isEnum() && !type.isRecord()) {
         result.append(" extends ").append(superclass.getTypeName());
      }

      Type[] interfaces = type.getGenericInterfaces();
      if (interfaces.length > 0) {
         result.append(type.isInterface() ? " extends " : " implements ").append(joinTypes(interfaces));
      }

      return result.toString();
   }

   private static String fieldSignature(Field field) {
      String details10000 = Modifier.toString(field.getModifiers());
      String signature = details10000 + " " + field.getGenericType().getTypeName() + " " + field.getName();
      if (Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers()) && (field.getType().isPrimitive() || field.getType() == String.class)) {
         try {
            signature = signature + "=" + Objects.toString(field.get(null));
         } catch (ReflectiveOperationException inaccessible) {
            throw new IllegalStateException("cannot read public API constant " + String.valueOf(field), inaccessible);
         }
      }

      return signature;
   }

   private static String constructorSignature(Constructor<?> constructor) {
      String details10000 = Modifier.toString(constructor.getModifiers());
      return details10000 + " " + typeVariables(constructor.getTypeParameters()) + constructor.getDeclaringClass().getName() + "(" + joinTypes(constructor.getGenericParameterTypes()) + ")" + throwsTypes(constructor.getGenericExceptionTypes());
   }

   private static String methodSignature(Method method) {
      String details10000 = Modifier.toString(method.getModifiers());
      return details10000 + " " + typeVariables(method.getTypeParameters()) + method.getGenericReturnType().getTypeName() + " " + method.getName() + "(" + joinTypes(method.getGenericParameterTypes()) + ")" + throwsTypes(method.getGenericExceptionTypes());
   }

   private static String typeVariables(TypeVariable<?>[] variables) {
      if (variables.length == 0) {
         return "";
      } else {
         ArrayList<String> declarations = new ArrayList<>(variables.length);

         for(TypeVariable<?> variable : variables) {
            Type[] bounds = variable.getBounds();
            String declaration = variable.getName();
            if (bounds.length != 1 || bounds[0] != Object.class) {
               declaration = declaration + " extends " + joinTypes(bounds, " & ");
            }

            declarations.add(declaration);
         }

         return "<" + String.join(",", declarations) + "> ";
      }
   }

   private static String throwsTypes(Type[] types) {
      return types.length == 0 ? "" : " throws " + joinTypes(types);
   }

   private static String joinTypes(Type[] types) {
      return joinTypes(types, ",");
   }

   private static String joinTypes(Type[] types, String delimiter) {
      return String.join(delimiter, Arrays.stream(types).map(Type::getTypeName).toList());
   }

   private static boolean publicOrProtected(int modifiers) {
      return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
   }
}

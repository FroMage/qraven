package io.github.fromage.qraven.tests;

import io.github.fromage.qraven.hardcoded.ModuleInfo;

import org.apache.maven.model.Model;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Modules with a value in every field, found by reflection so that fields added later are covered too. */
final class ModuleInfoFixtures {

    private ModuleInfoFixtures() {
    }

    private static final AtomicInteger COUNTER = new AtomicInteger();

    /** A module with a non-default value in every field, found by reflection so new fields are covered. */
    static ModuleInfo populated(String artifactId) throws Exception {
        ModuleInfo module = new ModuleInfo();
        for (Field f : declaredFields()) {
            f.setAccessible(true);
            f.set(module, value(f.getGenericType(), f.getName()));
        }
        module.setGroupId("com.test");
        module.setArtifactId(artifactId);
        return module;
    }

    static List<Field> declaredFields() {
        return Arrays.stream(ModuleInfo.class.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers())).toList();
    }

    static Object value(Type type, String name) throws Exception {
        Class<?> raw = type instanceof ParameterizedType p ? (Class<?>) p.getRawType() : (Class<?>) type;
        String unique = name + "-" + COUNTER.incrementAndGet();
        if (raw == String.class) {
            return unique;
        }
        if (raw == boolean.class) {
            return true;
        }
        if (Path.class.isAssignableFrom(raw)) {
            return Path.of("/tmp/" + unique);
        }
        if (raw == Model.class) {
            Model model = new Model();
            model.setGroupId("g-" + unique);
            model.setArtifactId("a-" + unique);
            model.setVersion("1.0");
            return model;
        }
        Type[] args = type instanceof ParameterizedType p ? p.getActualTypeArguments() : new Type[0];
        if (List.class.isAssignableFrom(raw)) {
            return new ArrayList<>(List.of(value(args[0], name + "-a"), value(args[0], name + "-b")));
        }
        if (java.util.Set.class.isAssignableFrom(raw)) {
            return new LinkedHashSet<>(List.of(value(args[0], name + "-a"), value(args[0], name + "-b")));
        }
        if (Map.class.isAssignableFrom(raw)) {
            Map<Object, Object> map = new LinkedHashMap<>();
            map.put("k1-" + unique, value(args[1], name + "-a"));
            map.put("k2-" + unique, value(args[1], name + "-b"));
            return map;
        }
        if (raw.isRecord()) {
            RecordComponent[] components = raw.getRecordComponents();
            Object[] values = new Object[components.length];
            Class<?>[] types = new Class<?>[components.length];
            for (int i = 0; i < components.length; i++) {
                values[i] = value(components[i].getGenericType(), components[i].getName());
                types[i] = components[i].getType();
            }
            return raw.getDeclaredConstructor(types).newInstance(values);
        }
        throw new AssertionError("this test does not know how to populate " + type);
    }


    /** A copy that shares the field values with the original, so replacing one field leaves the others as they were. */
    static ModuleInfo copyOf(ModuleInfo module) throws Exception {
        ModuleInfo copy = new ModuleInfo();
        for (Field f : declaredFields()) {
            f.setAccessible(true);
            f.set(copy, f.get(module));
        }
        return copy;
    }
}

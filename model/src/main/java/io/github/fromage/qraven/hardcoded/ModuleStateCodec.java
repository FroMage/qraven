package io.github.fromage.qraven.hardcoded;

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;

import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Encodes a {@link ModuleInfo} as CBOR by walking its fields reflectively, so a field added to ModuleInfo is
 * stored without touching this class. The encoding is driven by the declared types, which keeps it compact:
 * <ul>
 * <li>a module (and each nested record) is an array of its fields, in field-name order, with no field names;</li>
 * <li>every string is a reference into a table shared by all modules, since the same jar paths and
 * coordinates appear in hundreds of modules;</li>
 * <li>lists and sets are arrays, maps are maps, null is null.</li>
 * </ul>
 * Because the layout depends on the fields, {@link #schemaHash()} identifies it: stored data is only valid for the
 * schema that wrote it. Supported types are String, boolean, Path, Maven's Model (stored as its XML), List, Set,
 * Map with String keys, and records of those; anything else fails fast.
 */
final class ModuleStateCodec {

    private ModuleStateCodec() {
    }

    /** Strings in first-use order, so the table is deterministic for a deterministic input. */
    static final class Strings {
        private final Map<String, Integer> index = new LinkedHashMap<>();

        int of(String s) {
            Integer i = index.get(s);
            if (i == null) {
                i = index.size();
                index.put(s, i);
            }
            return i;
        }

        List<String> list() {
            return new ArrayList<>(index.keySet());
        }
    }

    private static final Map<Class<?>, List<Field>> FIELDS = new ConcurrentHashMap<>();

    /** The persisted fields of a class, sorted by name so the layout doesn't depend on declaration order. */
    private static List<Field> fields(Class<?> cls) {
        return FIELDS.computeIfAbsent(cls, c -> {
            List<Field> fields = new ArrayList<>();
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && !Modifier.isTransient(f.getModifiers())) {
                    f.setAccessible(true);
                    fields.add(f);
                }
            }
            fields.sort(Comparator.comparing(Field::getName));
            return fields;
        });
    }

    // ------------------------------------------------------------------ schema

    static String schemaHash() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(describe(ModuleInfo.class).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A description of the layout: field names and types, recursively. */
    static String describe(Type type) {
        Class<?> raw = raw(type);
        if (raw == String.class) {
            return "S";
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return "Z";
        }
        if (Path.class.isAssignableFrom(raw)) {
            return "P";
        }
        if (raw == Model.class) {
            return "M";
        }
        if (List.class.isAssignableFrom(raw)) {
            return "L<" + describe(typeArgument(type, 0)) + ">";
        }
        if (Set.class.isAssignableFrom(raw)) {
            return "E<" + describe(typeArgument(type, 0)) + ">";
        }
        if (Map.class.isAssignableFrom(raw)) {
            if (raw(typeArgument(type, 0)) != String.class) {
                throw unsupported(type);
            }
            return "D<" + describe(typeArgument(type, 1)) + ">";
        }
        if (raw.isRecord()) {
            StringBuilder sb = new StringBuilder("R{");
            for (RecordComponent c : raw.getRecordComponents()) {
                sb.append(c.getName()).append(':').append(describe(c.getGenericType())).append(',');
            }
            return sb.append('}').toString();
        }
        if (raw == ModuleInfo.class) {
            StringBuilder sb = new StringBuilder("O{");
            for (Field f : fields(raw)) {
                sb.append(f.getName()).append(':').append(describe(f.getGenericType())).append(',');
            }
            return sb.append('}').toString();
        }
        throw unsupported(type);
    }

    // ------------------------------------------------------------------ encoding

    static byte[] encode(ModuleInfo module, Strings strings) {
        Cbor.Writer w = new Cbor.Writer();
        encode(ModuleInfo.class, module, w, strings);
        return w.toByteArray();
    }

    private static void encode(Type type, Object value, Cbor.Writer w, Strings strings) {
        if (value == null) {
            w.nul();
            return;
        }
        Class<?> raw = raw(type);
        if (raw == String.class) {
            w.uint(strings.of((String) value));
        } else if (raw == boolean.class || raw == Boolean.class) {
            w.bool((Boolean) value);
        } else if (Path.class.isAssignableFrom(raw)) {
            w.uint(strings.of(value.toString()));
        } else if (raw == Model.class) {
            w.uint(strings.of(toXml((Model) value)));
        } else if (List.class.isAssignableFrom(raw) || Set.class.isAssignableFrom(raw)) {
            Collection<?> c = (Collection<?>) value;
            w.array(c.size());
            Type element = typeArgument(type, 0);
            for (Object o : c) {
                encode(element, o, w, strings);
            }
        } else if (Map.class.isAssignableFrom(raw)) {
            Map<?, ?> m = (Map<?, ?>) value;
            w.map(m.size());
            Type valueType = typeArgument(type, 1);
            for (Map.Entry<?, ?> e : m.entrySet()) {
                w.uint(strings.of((String) e.getKey()));
                encode(valueType, e.getValue(), w, strings);
            }
        } else if (raw.isRecord()) {
            RecordComponent[] components = raw.getRecordComponents();
            w.array(components.length);
            for (RecordComponent c : components) {
                encode(c.getGenericType(), get(c, value), w, strings);
            }
        } else if (raw == ModuleInfo.class) {
            List<Field> fields = fields(raw);
            w.array(fields.size());
            for (Field f : fields) {
                encode(f.getGenericType(), get(f, value), w, strings);
            }
        } else {
            throw unsupported(type);
        }
    }

    // ------------------------------------------------------------------ decoding

    static ModuleInfo decode(Cbor.Reader r, String[] strings) {
        return (ModuleInfo) decode(ModuleInfo.class, r, strings);
    }

    private static Object decode(Type type, Cbor.Reader r, String[] strings) {
        if (r.peekNull()) {
            r.nul();
            return null;
        }
        Class<?> raw = raw(type);
        if (raw == String.class) {
            return string(r, strings);
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return r.bool();
        }
        if (Path.class.isAssignableFrom(raw)) {
            return Path.of(string(r, strings));
        }
        if (raw == Model.class) {
            return fromXml(string(r, strings));
        }
        if (List.class.isAssignableFrom(raw) || Set.class.isAssignableFrom(raw)) {
            int n = r.array();
            Collection<Object> c = List.class.isAssignableFrom(raw) ? new ArrayList<>(n) : new LinkedHashSet<>();
            Type element = typeArgument(type, 0);
            for (int i = 0; i < n; i++) {
                c.add(decode(element, r, strings));
            }
            return c;
        }
        if (Map.class.isAssignableFrom(raw)) {
            int n = r.map();
            Map<String, Object> m = new LinkedHashMap<>();
            Type valueType = typeArgument(type, 1);
            for (int i = 0; i < n; i++) {
                String key = string(r, strings);
                m.put(key, decode(valueType, r, strings));
            }
            return m;
        }
        if (raw.isRecord()) {
            RecordComponent[] components = raw.getRecordComponents();
            expectLength(r.array(), components.length, raw);
            Object[] args = new Object[components.length];
            Class<?>[] types = new Class<?>[components.length];
            for (int i = 0; i < components.length; i++) {
                args[i] = decode(components[i].getGenericType(), r, strings);
                types[i] = components[i].getType();
            }
            try {
                var constructor = raw.getDeclaredConstructor(types);
                constructor.setAccessible(true);
                return constructor.newInstance(args);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("cannot rebuild " + raw.getName(), e);
            }
        }
        if (raw == ModuleInfo.class) {
            List<Field> fields = fields(raw);
            expectLength(r.array(), fields.size(), raw);
            ModuleInfo module = new ModuleInfo();
            for (Field f : fields) {
                set(f, module, decode(f.getGenericType(), r, strings));
            }
            return module;
        }
        throw unsupported(type);
    }

    private static String string(Cbor.Reader r, String[] strings) {
        long index = r.uint();
        if (index >= strings.length) {
            throw new IllegalStateException("string reference " + index + " is outside the table");
        }
        return strings[(int) index];
    }

    private static void expectLength(int actual, int expected, Class<?> cls) {
        if (actual != expected) {
            throw new IllegalStateException(cls.getSimpleName() + " has " + actual + " fields, expected " + expected);
        }
    }

    // ------------------------------------------------------------------ text dump

    /** A readable JSON rendering of a module, for debugging. */
    static String toJson(ModuleInfo module) {
        StringBuilder sb = new StringBuilder();
        json(module, sb, 0);
        return sb.append('\n').toString();
    }

    private static void json(Object value, StringBuilder sb, int indent) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            quote(s, sb);
        } else if (value instanceof Boolean || value instanceof Number) {
            sb.append(value);
        } else if (value instanceof Path p) {
            quote(p.toString(), sb);
        } else if (value instanceof Model m) {
            quote(toXml(m), sb);
        } else if (value instanceof Collection<?> c) {
            if (c.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append("[\n");
            int i = 0;
            for (Object o : c) {
                pad(sb, indent + 1);
                json(o, sb, indent + 1);
                sb.append(++i < c.size() ? ",\n" : "\n");
            }
            pad(sb, indent);
            sb.append(']');
        } else if (value instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                pad(sb, indent + 1);
                quote(String.valueOf(e.getKey()), sb);
                sb.append(": ");
                json(e.getValue(), sb, indent + 1);
                sb.append(++i < m.size() ? ",\n" : "\n");
            }
            pad(sb, indent);
            sb.append('}');
        } else if (value.getClass().isRecord()) {
            RecordComponent[] components = value.getClass().getRecordComponents();
            sb.append("{\n");
            for (int i = 0; i < components.length; i++) {
                pad(sb, indent + 1);
                quote(components[i].getName(), sb);
                sb.append(": ");
                json(get(components[i], value), sb, indent + 1);
                sb.append(i + 1 < components.length ? ",\n" : "\n");
            }
            pad(sb, indent);
            sb.append('}');
        } else if (value instanceof ModuleInfo) {
            List<Field> fields = fields(ModuleInfo.class);
            sb.append("{\n");
            for (int i = 0; i < fields.size(); i++) {
                pad(sb, indent + 1);
                quote(fields.get(i).getName(), sb);
                sb.append(": ");
                json(get(fields.get(i), value), sb, indent + 1);
                sb.append(i + 1 < fields.size() ? ",\n" : "\n");
            }
            pad(sb, indent);
            sb.append('}');
        } else {
            throw unsupported(value.getClass());
        }
    }

    private static void pad(StringBuilder sb, int indent) {
        sb.append("  ".repeat(indent));
    }

    private static void quote(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------ reflection helpers

    private static Class<?> raw(Type type) {
        if (type instanceof Class<?> c) {
            return c;
        }
        if (type instanceof ParameterizedType p) {
            return (Class<?>) p.getRawType();
        }
        throw unsupported(type);
    }

    private static Type typeArgument(Type type, int index) {
        if (type instanceof ParameterizedType p) {
            return p.getActualTypeArguments()[index];
        }
        throw new IllegalArgumentException("raw collection type without type arguments: " + type);
    }

    private static Object get(Field f, Object target) {
        try {
            return f.get(target);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object get(RecordComponent c, Object target) {
        try {
            var accessor = c.getAccessor();
            accessor.setAccessible(true);
            return accessor.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void set(Field f, Object target, Object value) {
        try {
            f.set(target, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static IllegalArgumentException unsupported(Type type) {
        return new IllegalArgumentException("unsupported type in the module state: " + type
                + " (supported: String, boolean, Path, Model, List, Set, Map<String, ?> and records of those)");
    }

    private static String toXml(Model model) {
        try {
            StringWriter w = new StringWriter();
            new MavenXpp3Writer().write(w, model);
            return w.toString();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Model fromXml(String xml) {
        try {
            return new MavenXpp3Reader().read(new StringReader(xml));
        } catch (Exception e) {
            throw new IllegalStateException("invalid stored model", e);
        }
    }
}

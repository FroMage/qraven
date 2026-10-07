package io.github.fromage.qraven.hardcoded;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.CRC32;

/**
 * The generation state: every module's {@link ModuleInfo}, in one CBOR file.
 * <p>
 * The state is a cache of what can be recomputed from the poms, so it is never migrated: a file that is missing,
 * corrupt, or written by another format version, qraven version, project or {@code ModuleInfo} layout is simply
 * treated as absent, and the caller regenerates.
 * <p>
 * Layout, as CBOR: {@code [payload (byte string), crc32 of the payload]}, where the payload is
 * {@code [format version, qraven version, schema hash, project root, strings, module ids, records]} and each record
 * is a byte string holding one encoded module. Strings are shared by all modules (see {@link ModuleStateCodec}),
 * and records can be decoded one at a time without decoding the others.
 */
public final class StateStore {

    public static final int FORMAT_VERSION = 1;

    private StateStore() {
    }

    /** Writes the state atomically: readers see the previous file or the new one, never a partial one. */
    public static void write(Path file, String qravenVersion, Path projectRoot, List<ModuleInfo> modules)
            throws IOException {
        ModuleStateCodec.Strings strings = new ModuleStateCodec.Strings();
        List<byte[]> records = new ArrayList<>(modules.size());
        List<String> ids = new ArrayList<>(modules.size());
        for (ModuleInfo module : modules) {
            records.add(ModuleStateCodec.encode(module, strings));
            ids.add(module.getGroupId() + ":" + module.getArtifactId());
        }

        Cbor.Writer payload = new Cbor.Writer();
        payload.array(7)
                .uint(FORMAT_VERSION)
                .text(qravenVersion == null ? "" : qravenVersion)
                .text(ModuleStateCodec.schemaHash())
                .text(projectRoot.toString());
        List<String> table = strings.list();
        payload.array(table.size());
        for (String s : table) {
            payload.text(s);
        }
        payload.array(ids.size());
        for (String id : ids) {
            payload.text(id);
        }
        payload.array(records.size());
        for (byte[] record : records) {
            payload.bytes(record);
        }
        byte[] body = payload.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(body);
        byte[] content = new Cbor.Writer().array(2).bytes(body).uint(crc.getValue()).toByteArray();

        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path temp = Files.createTempFile(dir, file.getFileName().toString(), ".tmp");
        try {
            Files.write(temp, content);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * Reads the state, or returns empty if there is none or it can't be trusted. A null {@code qravenVersion} or
     * {@code projectRoot} skips that check (for inspecting a state file); the layout is always checked.
     */
    public static Optional<State> read(Path file, String qravenVersion, Path projectRoot) {
        try {
            byte[] data = Files.readAllBytes(file);
            Cbor.Reader top = new Cbor.Reader(data);
            if (top.array() != 2) {
                return Optional.empty();
            }
            Cbor.Range body = top.bytes();
            long expectedCrc = top.uint();
            if (!top.atEnd()) {
                return Optional.empty();
            }
            CRC32 crc = new CRC32();
            crc.update(data, body.offset(), body.length());
            if (crc.getValue() != expectedCrc) {
                return Optional.empty();
            }

            Cbor.Reader r = new Cbor.Reader(data, body.offset(), body.length());
            if (r.array() != 7 || r.uint() != FORMAT_VERSION) {
                return Optional.empty();
            }
            String writtenBy = r.text();
            String schema = r.text();
            String root = r.text();
            if ((qravenVersion != null && !qravenVersion.equals(writtenBy))
                    || !ModuleStateCodec.schemaHash().equals(schema)
                    || (projectRoot != null && !projectRoot.toString().equals(root))) {
                return Optional.empty();
            }

            String[] strings = new String[r.array()];
            for (int i = 0; i < strings.length; i++) {
                strings[i] = r.text();
            }
            int count = r.array();
            List<String> ids = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                ids.add(r.text());
            }
            if (r.array() != count) {
                return Optional.empty();
            }
            Cbor.Range[] records = new Cbor.Range[count];
            for (int i = 0; i < count; i++) {
                records[i] = r.bytes();
            }
            if (!r.atEnd()) {
                return Optional.empty();
            }
            return Optional.of(new State(data, strings, ids, records));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** A readable JSON rendering of a module, for debugging. */
    public static String toJson(ModuleInfo module) {
        return ModuleStateCodec.toJson(module);
    }

    /** The stored modules. A module is only decoded when asked for. */
    public static final class State {
        private final byte[] data;
        private final String[] strings;
        private final List<String> ids;
        private final Cbor.Range[] records;

        private State(byte[] data, String[] strings, List<String> ids, Cbor.Range[] records) {
            this.data = data;
            this.strings = strings;
            this.ids = ids;
            this.records = records;
        }

        public int size() {
            return records.length;
        }

        /** {@code groupId:artifactId}, in the order the modules were written. */
        public List<String> moduleIds() {
            return ids;
        }

        public ModuleInfo module(int index) {
            Cbor.Range range = records[index];
            return ModuleStateCodec.decode(new Cbor.Reader(data, range.offset(), range.length()), strings);
        }

        public List<ModuleInfo> modules() {
            List<ModuleInfo> modules = new ArrayList<>(size());
            for (int i = 0; i < size(); i++) {
                modules.add(module(i));
            }
            return modules;
        }

        /** Finds a module by {@code groupId:artifactId}, or by artifactId alone. */
        public Optional<ModuleInfo> find(String idOrArtifactId) {
            int index = ids.indexOf(idOrArtifactId);
            for (int i = 0; index < 0 && i < ids.size(); i++) {
                if (ids.get(i).endsWith(":" + idOrArtifactId)) {
                    index = i;
                }
            }
            return index < 0 ? Optional.empty() : Optional.of(module(index));
        }
    }
}

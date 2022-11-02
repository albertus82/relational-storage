package io.github.albertus82.filestore.jdbc;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DescriptiveResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;

import com.thedeanda.lorem.LoremIpsum;

import io.github.albertus82.filestore.Compression;
import io.github.albertus82.filestore.SimpleFileStore;
import io.github.albertus82.filestore.TestConfig;
import io.github.albertus82.filestore.jdbc.SimpleJdbcFileStore.DatabaseResource;
import io.github.albertus82.filestore.jdbc.extractor.BlobExtractor;
import io.github.albertus82.filestore.jdbc.extractor.DirectBlobExtractor;
import io.github.albertus82.filestore.jdbc.extractor.FileBufferedBlobExtractor;
import io.github.albertus82.filestore.jdbc.extractor.MemoryBufferedBlobExtractor;

@SpringJUnitConfig(TestConfig.class)
class SimpleJdbcFileStoreTest {

	private static final Logger log = Logger.getLogger(SimpleJdbcFileStoreTest.class.getName());

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeAll
	static void beforeAll() {
		Logger.getLogger("").setLevel(Level.FINE);
		for (final Handler h : Logger.getLogger("").getHandlers()) {
			h.setLevel(Level.FINE);
		}
	}

	@BeforeEach
	void beforeEach() {
		new ResourceDatabasePopulator(new ClassPathResource(getClass().getPackageName().replace('.', '/') + "/table.sql")).execute(jdbcTemplate.getDataSource());
	}

	@AfterEach
	void afterEach() {
		jdbcTemplate.execute("DROP TABLE storage");
	}

	@Test
	void testDatabase1() {
		jdbcTemplate.update("INSERT INTO storage (filename, content_length, file_contents, last_modified, compressed) VALUES (?, ?, ?, ?, ?)", "a", 1, "x".getBytes(), new Date(), false);
		Assertions.assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM storage", int.class));
	}

	@Test
	void testDatabase2() {
		jdbcTemplate.update("INSERT INTO storage (filename, content_length, file_contents, last_modified, compressed) VALUES (?, ?, ?, ?, ?)", "b", 2, "yz".getBytes(), new Date(), false);
		Assertions.assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM storage", int.class));
	}

	@Test
	void testApiBehaviour() throws IOException {
		final FileBufferedBlobExtractor fbbe = new FileBufferedBlobExtractor();

		Assertions.assertThrows(NullPointerException.class, () -> new SimpleJdbcFileStore(null, null, null));
		Assertions.assertThrows(NullPointerException.class, () -> new SimpleJdbcFileStore(jdbcTemplate, null, null));
		Assertions.assertThrows(NullPointerException.class, () -> new SimpleJdbcFileStore(jdbcTemplate, "STORAGE", null));
		Assertions.assertThrows(NullPointerException.class, () -> new SimpleJdbcFileStore(jdbcTemplate, null, fbbe));
		Assertions.assertThrows(NullPointerException.class, () -> new SimpleJdbcFileStore(null, "STORAGE", null));
		Assertions.assertThrows(NullPointerException.class, () -> new SimpleJdbcFileStore(null, null, fbbe));
		Assertions.assertThrows(IllegalArgumentException.class, () -> new SimpleJdbcFileStore(jdbcTemplate, "", fbbe));

		final SimpleJdbcFileStore s1 = new SimpleJdbcFileStore(jdbcTemplate, "STORAGE", fbbe);
		Assertions.assertThrows(NullPointerException.class, () -> s1.withCompression(null));
		Assertions.assertThrows(NullPointerException.class, () -> s1.withEncryption(null));
		Assertions.assertThrows(NullPointerException.class, () -> s1.withSchema(null));
		Assertions.assertThrows(IllegalArgumentException.class, () -> s1.withEncryption(new char[0]));
		Assertions.assertThrows(IllegalArgumentException.class, () -> s1.withSchema(""));
		Assertions.assertDoesNotThrow(() -> s1.withSchema("SCHEMA"));

		final SimpleFileStore s2 = new SimpleJdbcFileStore(jdbcTemplate, "STORAGE", fbbe).withCompression(Compression.MEDIUM);
		Assertions.assertDoesNotThrow(() -> s2.list());
		Assertions.assertNotNull(s2.list());
		Assertions.assertThrows(NullPointerException.class, () -> s2.delete(null));
		Assertions.assertThrows(NullPointerException.class, () -> s2.get(null));
		Assertions.assertThrows(NullPointerException.class, () -> s2.rename("a", null));
		Assertions.assertThrows(NullPointerException.class, () -> s2.rename(null, "b"));
		Assertions.assertThrows(NullPointerException.class, () -> s2.rename(null, null));

		Assertions.assertThrows(NullPointerException.class, () -> new FileBufferedBlobExtractor(null));

		final DescriptiveResource dr = new DescriptiveResource("x");
		Assertions.assertThrows(NullPointerException.class, () -> s2.store(null, "y"));
		Assertions.assertThrows(NullPointerException.class, () -> s2.store(dr, null));
		Assertions.assertThrows(NullPointerException.class, () -> s2.store(null, null));
	}

	@Test
	void testList() throws IOException {
		final SimpleFileStore store = new SimpleJdbcFileStore(jdbcTemplate, "STORAGE", new FileBufferedBlobExtractor()).withCompression(Compression.MEDIUM);
		Assertions.assertEquals(0, store.list().size());
		final Resource toSave = new InputStreamResource(getClass().getResourceAsStream("10b.txt"));
		store.store(toSave, "myfile.txt");
		Assertions.assertEquals(1, store.list().size());
	}

	@Test
	void testListWithFilters() throws IOException {
		final SimpleFileStore store = new SimpleJdbcFileStore(jdbcTemplate, "STORAGE", new FileBufferedBlobExtractor()).withCompression(Compression.MEDIUM);
		Assertions.assertEquals(0, store.list().size());
		store.store(new InputStreamResource(getClass().getResourceAsStream("10b.txt")), "foo.txt");
		store.store(new InputStreamResource(getClass().getResourceAsStream("10b.txt")), "bar.txt");
		store.store(new InputStreamResource(getClass().getResourceAsStream("10b.txt")), "test1.txt");
		store.store(new InputStreamResource(getClass().getResourceAsStream("10b.txt")), "test2.txt");
		store.store(new InputStreamResource(getClass().getResourceAsStream("10b.txt")), "tax%.txt");
		store.store(new InputStreamResource(getClass().getResourceAsStream("10b.txt")), "taxi.txt");
		store.store(new InputStreamResource(getClass().getResourceAsStream("10b.txt")), "file.dat");
		Assertions.assertEquals(7, store.list().size());
		Assertions.assertEquals(7, store.list("*").size());
		Assertions.assertEquals(4, store.list("t*").size());
		Assertions.assertEquals(2, store.list("te*").size());
		Assertions.assertEquals(2, store.list("tes*").size());
		Assertions.assertEquals(2, store.list("test*").size());
		Assertions.assertEquals(1, store.list("tax%*").size());
		Assertions.assertEquals(1, store.list("tax%.txt").size());
		Assertions.assertEquals(2, store.list("tax*.txt").size());
		Assertions.assertEquals(2, store.list("tax?.txt").size());
		Assertions.assertEquals(2, store.list("?ax*.txt").size());
		Assertions.assertEquals(2, store.list("*ax*").size());
		Assertions.assertEquals(1, store.list("foo.txt").size());
		Assertions.assertEquals(2, store.list("foo.txt", "bar.txt").size());
		Assertions.assertEquals(2, store.list("*oo.txt", "bar.txt").size());
		Assertions.assertEquals(6, store.list("*.txt").size());
		Assertions.assertEquals(2, store.list("*oo.txt", "bar.txt").size());
		Assertions.assertEquals(6, store.list("*.txt", "bar.txt").size());
		Assertions.assertEquals(1, store.list("*.d?t").size());

	}

	@Test
	void testRename() throws IOException {
		final SimpleFileStore store = new SimpleJdbcFileStore(jdbcTemplate, "STORAGE", new FileBufferedBlobExtractor()).withCompression(Compression.MEDIUM);
		final Resource toSave1 = new InputStreamResource(getClass().getResourceAsStream("10b.txt"));
		store.store(toSave1, "foo.txt");
		Assertions.assertTrue(store.get("foo.txt").exists());
		store.rename("foo.txt", "bar.txt");
		Assertions.assertTrue(store.get("bar.txt").exists());
		Assertions.assertEquals(1, store.list().size());
		Assertions.assertThrows(NoSuchFileException.class, () -> store.get("foo.txt"));
		Assertions.assertThrows(NoSuchFileException.class, () -> store.rename("foo.txt", "baz.txt"));
		final Resource toSave2 = new InputStreamResource(getClass().getResourceAsStream("10b.txt"));
		store.store(toSave2, "foo.txt");
		Assertions.assertEquals(2, store.list().size());
		Assertions.assertThrows(FileAlreadyExistsException.class, () -> store.rename("foo.txt", "bar.txt"));
	}

	@Test
	void testStoreListGetDeleteFromStream() throws IOException {
		for (final BlobExtractor be : new BlobExtractor[] { new FileBufferedBlobExtractor(), new MemoryBufferedBlobExtractor() }) {
			for (final Compression compression : Compression.values()) {
				final SimpleFileStore store = new SimpleJdbcFileStore(jdbcTemplate, "STORAGE", be).withCompression(compression);
				try (final InputStream is = getClass().getResourceAsStream("10b.txt")) {
					store.store(new InputStreamResource(is), "myfile.txt");
				}
				final long timeAfter = System.currentTimeMillis();
				final List<Resource> list = store.list();
				Assertions.assertEquals(1, list.size());
				final Resource r1 = list.get(0);
				Assertions.assertEquals("qwertyuiop".length(), r1.contentLength());
				Assertions.assertTrue(r1.exists());
				try (final InputStream is = r1.getInputStream()) {
					Assertions.assertArrayEquals("qwertyuiop".getBytes(), is.readAllBytes());
					Assertions.assertEquals("myfile.txt", r1.getFilename());
					Assertions.assertTrue(timeAfter - r1.lastModified() < TimeUnit.SECONDS.toMillis(10));
				}
				final Resource r2 = store.get(r1.getFilename());
				Assertions.assertEquals(r1.contentLength(), r2.contentLength());
				Assertions.assertTrue(r2.exists());
				try (final InputStream is = r2.getInputStream()) {
					Assertions.assertArrayEquals("qwertyuiop".getBytes(), is.readAllBytes());
					Assertions.assertEquals(r1.getFilename(), r2.getFilename());
					Assertions.assertEquals(r1.lastModified(), r2.lastModified());
				}
				store.delete("myfile.txt");
				Assertions.assertFalse(r2.exists());
				Assertions.assertThrows(NoSuchFileException.class, () -> r2.getInputStream());
				Assertions.assertThrows(NoSuchFileException.class, () -> store.get("myfile.txt"));
				Assertions.assertThrows(NoSuchFileException.class, () -> store.delete("myfile.txt"));
			}
		}
	}

	@Test
	void testEncryptedStoreListGetDeleteFromStream() throws IOException {
		for (final BlobExtractor be : new BlobExtractor[] { new FileBufferedBlobExtractor(), new MemoryBufferedBlobExtractor() }) {
			for (final Compression compression : Compression.values()) {
				final SimpleJdbcFileStore store = new SimpleJdbcFileStore(jdbcTemplate, "STORAGE", be).withCompression(compression).withEncryption("TestPassword0$".toCharArray());
				try (final InputStream is = getClass().getResourceAsStream("10b.txt")) {
					store.store(new InputStreamResource(is), "myfile.txt");
				}
				final long timeAfter = System.currentTimeMillis();
				final List<Resource> list = store.list();
				Assertions.assertEquals(1, list.size());
				final Resource r1 = list.get(0);
				Assertions.assertEquals("qwertyuiop".length(), r1.contentLength());
				Assertions.assertTrue(r1.exists());
				try (final InputStream is = r1.getInputStream()) {
					Assertions.assertArrayEquals("qwertyuiop".getBytes(), is.readAllBytes());
					Assertions.assertEquals("myfile.txt", r1.getFilename());
					Assertions.assertTrue(timeAfter - r1.lastModified() < TimeUnit.SECONDS.toMillis(10));
				}
				final Resource r2 = store.get(r1.getFilename());
				Assertions.assertEquals(r1.contentLength(), r2.contentLength());
				Assertions.assertTrue(r2.exists());
				try (final InputStream is = r2.getInputStream()) {
					Assertions.assertArrayEquals("qwertyuiop".getBytes(), is.readAllBytes());
					Assertions.assertEquals(r1.getFilename(), r2.getFilename());
					Assertions.assertEquals(r1.lastModified(), r2.lastModified());
				}
				store.delete("myfile.txt");
				Assertions.assertFalse(r2.exists());
				Assertions.assertThrows(NoSuchFileException.class, () -> r2.getInputStream());
				Assertions.assertThrows(NoSuchFileException.class, () -> store.get("myfile.txt"));
				Assertions.assertThrows(NoSuchFileException.class, () -> store.delete("myfile.txt"));
			}
		}
	}

	@Test
	void testStoreListGetDeleteFromFile() throws IOException {
		for (final BlobExtractor be : new BlobExtractor[] { new FileBufferedBlobExtractor(), new MemoryBufferedBlobExtractor() }) {
			for (final Compression compression : Compression.values()) {
				final SimpleFileStore store = new SimpleJdbcFileStore(jdbcTemplate, "STORAGE", be).withCompression(compression);
				Path tempFile = null;
				try {
					tempFile = Files.createTempFile(null, null);
					tempFile.toFile().deleteOnExit();
					Files.writeString(tempFile, "asdfghjkl");
					final BasicFileAttributes tempFileAttr = Files.readAttributes(tempFile, BasicFileAttributes.class);
					store.store(new FileSystemResource(tempFile), "myfile.txt");
					final List<Resource> list = store.list();
					Assertions.assertEquals(1, list.size());
					final Resource r1 = list.get(0);
					Assertions.assertEquals("asdfghjkl".length(), r1.contentLength());
					Assertions.assertTrue(r1.exists());
					try (final InputStream is = r1.getInputStream()) {
						Assertions.assertArrayEquals("asdfghjkl".getBytes(), is.readAllBytes());
						Assertions.assertEquals("myfile.txt", r1.getFilename());
						Assertions.assertEquals(tempFileAttr.lastModifiedTime().toMillis(), r1.lastModified());
					}
					final Resource r2 = store.get(r1.getFilename());
					Assertions.assertEquals(r1.contentLength(), r2.contentLength());
					Assertions.assertTrue(r2.exists());
					try (final InputStream is = r2.getInputStream()) {
						Assertions.assertArrayEquals("asdfghjkl".getBytes(), is.readAllBytes());
						Assertions.assertEquals(r1.getFilename(), r2.getFilename());
						Assertions.assertEquals(r1.lastModified(), r2.lastModified());
					}
					store.delete("myfile.txt");
					Assertions.assertFalse(r2.exists());
					Assertions.assertThrows(NoSuchFileException.class, () -> r2.getInputStream());
					Assertions.assertThrows(NoSuchFileException.class, () -> store.get("myfile.txt"));
					Assertions.assertThrows(NoSuchFileException.class, () -> store.delete("myfile.txt"));
				}
				finally {
					Files.deleteIfExists(tempFile);
				}
			}
		}
	}

	@Test
	void testStore() throws IOException {
		final SimpleFileStore store = new SimpleJdbcFileStore(jdbcTemplate, "STORAGE", new FileBufferedBlobExtractor()).withCompression(Compression.LOW);
		try (final InputStream is = new ByteArrayInputStream(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8))) {
			Assertions.assertDoesNotThrow(() -> store.store(new InputStreamResource(is), "myfile.txt"));
		}
		Assertions.assertEquals(1, store.list().size());
	}

	@Test
	void testStoreLarge() throws Exception {
		Path tempFile = null;
		try {
			tempFile = createDummyFile(DataSize.ofMegabytes(32));
			final Path f = tempFile;
			List.of(new FileBufferedBlobExtractor(), new MemoryBufferedBlobExtractor()).parallelStream().forEach(be -> {
				try {
					for (final Compression compression : Compression.values()) {
						final String fileName = UUID.randomUUID().toString();
						final SimpleJdbcFileStore store = new SimpleJdbcFileStore(jdbcTemplate, "STORAGE", be).withCompression(compression);
						try (final InputStream is = Files.newInputStream(f)) {
							Assertions.assertDoesNotThrow(() -> store.store(new InputStreamResource(is), fileName));
						}

						final byte[] buffer = new byte[8192];
						final MessageDigest digestSource = MessageDigest.getInstance("SHA-256");
						try (final InputStream is = Files.newInputStream(f)) {
							int bytesCount = 0;
							while ((bytesCount = is.read(buffer)) != -1) {
								digestSource.update(buffer, 0, bytesCount);
							}
						}
						final MessageDigest digestStored = MessageDigest.getInstance("SHA-256");
						final DatabaseResource dr = store.get(fileName);
						try (final InputStream stored = dr.getInputStream()) {
							int bytesCount = 0;
							while ((bytesCount = stored.read(buffer)) != -1) {
								digestStored.update(buffer, 0, bytesCount);
							}
						}
						final byte[] sha256Source = digestSource.digest();
						final byte[] sha256Stored = digestStored.digest();
						Assertions.assertArrayEquals(sha256Source, sha256Stored);
						Assertions.assertArrayEquals(sha256Source, hexToBytes(dr.getSha256Hex()));
					}
				}
				catch (IOException e) {
					throw new UncheckedIOException(e);
				}
				catch (final NoSuchAlgorithmException e) {
					throw new RuntimeException(e);
				}
			});
		}
		finally {
			deleteIfExists(tempFile);
		}
	}

	@Test
	@Transactional
	void testStoreLargeTransactional() throws Exception {
		Path tempFile = null;
		try {
			tempFile = createDummyFile(DataSize.ofMegabytes(32));
			for (final Compression compression : Compression.values()) {
				final String fileName = UUID.randomUUID().toString();
				final SimpleJdbcFileStore store = new SimpleJdbcFileStore(jdbcTemplate, "STORAGE", new DirectBlobExtractor()).withCompression(compression);
				try (final InputStream is = Files.newInputStream(tempFile)) {
					Assertions.assertDoesNotThrow(() -> store.store(new InputStreamResource(is), fileName));
				}

				final byte[] buffer = new byte[8192];
				final MessageDigest digestSource = MessageDigest.getInstance("SHA-256");
				try (final InputStream is = Files.newInputStream(tempFile)) {
					int bytesCount = 0;
					while ((bytesCount = is.read(buffer)) != -1) {
						digestSource.update(buffer, 0, bytesCount);
					}
				}
				final MessageDigest digestStored = MessageDigest.getInstance("SHA-256");
				final DatabaseResource dr = store.get(fileName);
				try (final InputStream stored = dr.getInputStream()) {
					int bytesCount = 0;
					while ((bytesCount = stored.read(buffer)) != -1) {
						digestStored.update(buffer, 0, bytesCount);
					}
				}
				final byte[] sha256Source = digestSource.digest();
				final byte[] sha256Stored = digestStored.digest();
				Assertions.assertArrayEquals(sha256Source, sha256Stored);
				Assertions.assertArrayEquals(sha256Source, hexToBytes(dr.getSha256Hex()));
			}
		}
		finally {
			deleteIfExists(tempFile);
		}
	}

	@Test
	void testDuplicate() throws IOException {
		final SimpleFileStore store = new SimpleJdbcFileStore(jdbcTemplate, "STORAGE", new FileBufferedBlobExtractor()).withCompression(Compression.HIGH);
		try (final InputStream is = getClass().getResourceAsStream("10b.txt")) {
			store.store(new InputStreamResource(is), "myfile.txt");
		}
		final List<Resource> list = store.list();
		Assertions.assertEquals(1, list.size());
		try (final InputStream is = getClass().getResourceAsStream("10b.txt")) {
			Assertions.assertThrows(FileAlreadyExistsException.class, () -> store.store(new InputStreamResource(is), "myfile.txt"));
		}
	}

	@Test
	void testHash() throws IOException {
		final SimpleJdbcFileStore store = new SimpleJdbcFileStore(jdbcTemplate, "STORAGE", new FileBufferedBlobExtractor()).withCompression(Compression.HIGH);
		try (final InputStream is = getClass().getResourceAsStream("10b.txt")) {
			store.store(new InputStreamResource(is), "myfile.txt");
		}
		final DatabaseResource r = store.get("myfile.txt");
		Assertions.assertEquals("9a900403ac313ba27a1bc81f0932652b8020dac92c234d98fa0b06bf0040ecfd", r.getSha256Hex());
	}

	private static Path createDummyFile(final DataSize size) throws IOException {
		if (size == null) {
			throw new NullPointerException();
		}
		if (size.toBytes() < 0) {
			throw new IllegalArgumentException(size.toString());
		}
		log.log(Level.FINE, "Creating {0} dummy file...", size);
		long currSize = 0;
		final Path tmp = Files.createTempFile(null, null);
		tmp.toFile().deleteOnExit();
		try (final Writer w = Files.newBufferedWriter(tmp, StandardCharsets.US_ASCII, StandardOpenOption.TRUNCATE_EXISTING)) {
			while (currSize < size.toBytes()) {
				final String s = LoremIpsum.getInstance().getWords(100);
				currSize += s.length();
				w.append(s).append(System.lineSeparator());
			}
		}
		try (final RandomAccessFile raf = new RandomAccessFile(tmp.toFile(), "rw"); final FileChannel fc = raf.getChannel()) {
			fc.truncate(size.toBytes());
		}
		if (tmp.toFile().length() != size.toBytes()) {
			throw new IllegalStateException();
		}
		log.log(Level.FINE, "Dummy file created: \"{0}\".", tmp);
		return tmp;
	}

	private static void deleteIfExists(final Path file) {
		if (file != null) {
			try {
				Files.deleteIfExists(file);
			}
			catch (final IOException e) {
				log.log(Level.FINE, e, () -> "Cannot delete file \"" + file + "\":");
				file.toFile().deleteOnExit();
			}
		}
	}

	private static byte[] hexToBytes(final String hex) {
		final int len = hex.length();
		final byte[] data = new byte[len / 2];
		for (int i = 0; i < len; i += 2) {
			data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4) + Character.digit(hex.charAt(i + 1), 16));
		}
		return data;
	}

}
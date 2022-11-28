package io.github.albertus82.filestore.jdbc.read;

import java.io.InputStream;
import java.sql.SQLException;
import java.util.Objects;

import io.github.albertus82.filestore.jdbc.read.decode.DirectStreamDecoder;
import io.github.albertus82.filestore.jdbc.read.decode.zip.ZipStreamDecoder;
import io.github.albertus82.filestore.jdbc.write.BinaryStreamProvider;

/**
 * BLOB extraction strategy that directly returns the {@link InputStream}
 * created by the JDBC driver.
 */
public class DirectBlobExtractor implements BlobExtractor {

	private final DirectStreamDecoder decoder;

	/**
	 * Creates a new instance of this extractor that uses {@link ZipStreamDecoder}
	 * to decode the BLOB contents.
	 */
	public DirectBlobExtractor() {
		this(new ZipStreamDecoder());
	}

	/**
	 * Creates a new instance of this extractor that uses the provided stream
	 * decoder. Note that in order to successfully decode the BLOB contents, the
	 * decoder format must match the one of the encoder chosen for
	 * {@link BinaryStreamProvider}.
	 *
	 * @param decoder the stream decoder
	 */
	public DirectBlobExtractor(final DirectStreamDecoder decoder) {
		Objects.requireNonNull(decoder, "decoder must not be null");
		this.decoder = decoder;
	}

	@Override
	public InputStream getInputStream(final BlobAccessor blobAccessor) throws SQLException {
		Objects.requireNonNull(blobAccessor, "blobAccessor must not be null");
		return decoder.decodeStream(blobAccessor.getBinaryStream(), blobAccessor);
	}

}

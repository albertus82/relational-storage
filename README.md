Simple JDBC Filestore
=====================
[![Build](https://github.com/albertus82/simple-jdbc-filestore/actions/workflows/build.yml/badge.svg)](https://github.com/albertus82/simple-jdbc-filestore/actions)
[![Known Vulnerabilities](https://snyk.io/test/github/albertus82/simple-jdbc-filestore/badge.svg?targetFile=pom.xml)](https://snyk.io/test/github/albertus82/simple-jdbc-filestore?targetFile=pom.xml)

### Basic RDBMS-based filestore with compression and encryption support.

* The files are always stored internally in ZIP format in order to get CRC-32 check and AES encryption for free.
   * The compression level is customizable from [`NONE`](src/main/java/io/github/albertus82/filestore/io/Compression.java#L9) to [`HIGH`](src/main/java/io/github/albertus82/filestore/io/Compression.java#L18).
   * The internal ZIP encoding is transparent for the client, so no manual *unzip* is needed.
   * The `CONTENT_LENGTH` value represents the *original uncompressed size* of the object, not the BLOB size.
* This store has a flat structure instead of a hierarchy, so there is no direct support for things like directories or folders, but being `FILENAME` a simple key string with no constraint but unicity, you can use common prefixes to organize your files simulating a hierarchical structure. For more info, you can check the [Amazon S3 documentation](https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-keys.html) because the semantics are similar.
* This library requires JDK 11 and has a [Spring](https://spring.io) dependency but no Spring Context is actually needed.

| FILENAME | CONTENT_LENGTH | LAST_MODIFIED           | COMPRESSED | ENCRYPTED | FILE_CONTENTS | CREATION_TIME           |
| -------- | -------------: | ----------------------- | ---------: | --------: | ------------- | ----------------------- |
| foo.txt  |            123 | 2022-10-31 23:10:22,607 |          1 |         0 | (BLOB)        | 2022-10-31 23:10:22,610 |
| bar.png  |           4567 | 2022-10-31 23:10:49,669 |          0 |         0 | (BLOB)        | 2022-10-31 23:10:49,672 |
| baz.zip  |          89012 | 2022-10-31 23:11:02,607 |          0 |         1 | (BLOB)        | 2022-10-31 23:11:02,610 |

## Usage

### Add the Maven dependency

```xml
<dependency>
    <groupId>io.github.albertus82</groupId>
    <artifactId>simple-jdbc-filestore</artifactId>
    <version>0.1.0</version>
</dependency>
```

### Create the database table

```sql
CREATE TABLE storage (
    filename         VARCHAR(255) NOT NULL PRIMARY KEY,
    content_length   NUMERIC(19, 0) /* NOT NULL DEFERRABLE INITIALLY DEFERRED */ CHECK (content_length >= 0),
    last_modified    TIMESTAMP NOT NULL,
    compressed       NUMERIC(1, 0) NOT NULL CHECK (compressed IN (0, 1)),
    encrypted        NUMERIC(1, 0) NOT NULL CHECK (encrypted IN (0, 1)),
    file_contents    BLOB NOT NULL,
    creation_time    TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);
```

### Sample Java code

```java
DataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1"); // replace with your connection string or connection pool object
SimpleFileStore storage = new SimpleJdbcFileStore(new JdbcTemplate(dataSource), "MY_DB_TABLE", new FileBufferedBlobExtractor()); // can be customized, see Javadoc
storage.store(new PathResource("path/to/myFile.ext", "myStoredFile.ext"));
Resource resource = storage.get("myStoredFile.ext");
byte[] bytes = resource.getInputStream().readAllBytes(); // not intended for reading input streams with large amounts of data!
```

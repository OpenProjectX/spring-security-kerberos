/*
 * Copyright 2009-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.security.kerberos.test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.io.Charsets;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.text.StrSubstitutor;
import org.apache.directory.api.ldap.model.entry.DefaultEntry;
import org.apache.directory.api.ldap.model.entry.Entry;
import org.apache.directory.api.ldap.model.ldif.LdifEntry;
import org.apache.directory.api.ldap.model.ldif.LdifReader;
import org.apache.directory.api.ldap.model.name.Dn;
import org.apache.directory.api.ldap.model.schema.SchemaManager;
import org.apache.directory.api.ldap.model.schema.registries.SchemaLoader;
import org.apache.directory.api.ldap.schemaextractor.SchemaLdifExtractor;
import org.apache.directory.api.ldap.schemaextractor.impl.DefaultSchemaLdifExtractor;
import org.apache.directory.api.ldap.schemaloader.LdifSchemaLoader;
import org.apache.directory.api.ldap.schemamanager.impl.DefaultSchemaManager;
import org.apache.directory.server.constants.ServerDNConstants;
import org.apache.directory.server.core.DefaultDirectoryService;
import org.apache.directory.server.core.api.CacheService;
import org.apache.directory.server.core.api.DirectoryService;
import org.apache.directory.server.core.api.InstanceLayout;
import org.apache.directory.server.core.api.schema.SchemaPartition;
import org.apache.directory.server.core.kerberos.KeyDerivationInterceptor;
import org.apache.directory.server.core.partition.impl.btree.jdbm.JdbmIndex;
import org.apache.directory.server.core.partition.impl.btree.jdbm.JdbmPartition;
import org.apache.directory.server.core.partition.ldif.LdifPartition;
import org.apache.directory.server.kerberos.kdc.KdcServer;
import org.apache.directory.server.kerberos.shared.crypto.encryption.KerberosKeyFactory;
import org.apache.directory.server.kerberos.shared.keytab.Keytab;
import org.apache.directory.server.kerberos.shared.keytab.KeytabEntry;
import org.apache.directory.server.protocol.shared.transport.TcpTransport;
import org.apache.directory.server.protocol.shared.transport.UdpTransport;
import org.apache.directory.server.xdbm.Index;
import org.apache.directory.shared.kerberos.KerberosTime;
import org.apache.directory.shared.kerberos.codec.types.EncryptionType;
import org.apache.directory.shared.kerberos.components.EncryptionKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>
 * Mini KDC based on Apache Directory Server that can be embedded in testcases or used
 * from command line as a standalone KDC.
 * </p>
 * <b>From within testcases:</b>
 * <p>
 * MiniKdc sets 2 System properties when started and un-sets them when stopped:
 * </p>
 * <ul>
 * <li>java.security.krb5.conf: set to the MiniKDC real/host/port</li>
 * <li>sun.security.krb5.debug: set to the debug value provided in the configuration</li>
 * </ul>
 * <p>
 * Because of this, multiple MiniKdc instances cannot be started in parallel. For example,
 * running testcases in parallel that start a KDC each. To accomplish this a single
 * MiniKdc should be used for all testcases running in parallel.
 * </p>
 * <p>
 * MiniKdc default configuration values are:
 * <ul>
 * <li>org.name=EXAMPLE (used to create the REALM)</li>
 * <li>org.domain=COM (used to create the REALM)</li>
 * <li>kdc.bind.address=localhost</li>
 * <li>kdc.port=0 (ephemeral port)</li>
 * <li>instance=DefaultKrbServer</li>
 * <li>max.ticket.lifetime=86400000 (1 day)</li>
 * <li>max.renewable.lifetime=604800000 (7 days)</li>
 * <li>transport=TCP</li>
 * <li>debug=false</li>
 * </ul>
 * The generated krb5.conf forces TCP connections.
 *
 * @author Original Hadoop MiniKdc Authors
 * @author Janne Valkealahti
 * @author Bogdan Mustiata
 */
public class MiniKdc {

	public static void main(String[] args) throws Exception {
		if (args.length < 4) {
			System.out.println("Arguments: <WORKDIR> <MINIKDCPROPERTIES> " + "<KEYTABFILE> [<PRINCIPALS>]+");
			System.exit(1);
		}
		File workDir = new File(args[0]);
		if (!workDir.exists()) {
			throw new RuntimeException("Specified work directory does not exists: " + workDir.getAbsolutePath());
		}
		Properties conf = createConf();
		File file = new File(args[1]);
		if (!file.exists()) {
			throw new RuntimeException("Specified configuration does not exists: " + file.getAbsolutePath());
		}
		Properties userConf = new Properties();
		InputStreamReader r = null;
		try {
			r = new InputStreamReader(new FileInputStream(file), Charsets.UTF_8);
			userConf.load(r);
		}
		finally {
			if (r != null) {
				r.close();
			}
		}
		for (Map.Entry<?, ?> entry : userConf.entrySet()) {
			conf.put(entry.getKey(), entry.getValue());
		}
		final MiniKdc miniKdc = new MiniKdc(conf, workDir);
		miniKdc.start();
		File krb5conf = new File(workDir, "krb5.conf");
		if (miniKdc.getKrb5conf().renameTo(krb5conf)) {
			File keytabFile = new File(args[2]).getAbsoluteFile();
			String[] principals = new String[args.length - 3];
			System.arraycopy(args, 3, principals, 0, args.length - 3);
			miniKdc.createPrincipal(keytabFile, principals);
			System.out.println();
			System.out.println("Standalone MiniKdc Running");
			System.out.println("---------------------------------------------------");
			System.out.println("  Realm           : " + miniKdc.getRealm());
			System.out.println("  Running at      : " + miniKdc.getHost() + ":" + miniKdc.getHost());
			System.out.println("  krb5conf        : " + krb5conf);
			System.out.println();
			System.out.println("  created keytab  : " + keytabFile);
			System.out.println("  with principals : " + Arrays.asList(principals));
			System.out.println();
			System.out.println(" Do <CTRL-C> or kill <PID> to stop it");
			System.out.println("---------------------------------------------------");
			System.out.println();
			Runtime.getRuntime().addShutdownHook(new Thread() {
				@Override
				public void run() {
					miniKdc.stop();
				}
			});
		}
		else {
			throw new RuntimeException("Cannot rename KDC's krb5conf to " + krb5conf.getAbsolutePath());
		}
	}

	private static final Logger LOG = LoggerFactory.getLogger(MiniKdc.class);

	public static final String ORG_NAME = "org.name";

	public static final String ORG_DOMAIN = "org.domain";

	public static final String KDC_BIND_ADDRESS = "kdc.bind.address";

	public static final String KDC_PORT = "kdc.port";

	public static final String INSTANCE = "instance";

	public static final String MAX_TICKET_LIFETIME = "max.ticket.lifetime";

	public static final String MAX_RENEWABLE_LIFETIME = "max.renewable.lifetime";

	public static final String TRANSPORT = "transport";

	public static final String DEBUG = "debug";

	private static final Set<String> PROPERTIES = new HashSet<String>();

	private static final Properties DEFAULT_CONFIG = new Properties();

	static {
		PROPERTIES.add(ORG_NAME);
		PROPERTIES.add(ORG_DOMAIN);
		PROPERTIES.add(KDC_BIND_ADDRESS);
		PROPERTIES.add(KDC_BIND_ADDRESS);
		PROPERTIES.add(KDC_PORT);
		PROPERTIES.add(INSTANCE);
		PROPERTIES.add(TRANSPORT);
		PROPERTIES.add(MAX_TICKET_LIFETIME);
		PROPERTIES.add(MAX_RENEWABLE_LIFETIME);

		DEFAULT_CONFIG.setProperty(KDC_BIND_ADDRESS, "localhost");
		DEFAULT_CONFIG.setProperty(KDC_PORT, "0");
		DEFAULT_CONFIG.setProperty(INSTANCE, "DefaultKrbServer");
		DEFAULT_CONFIG.setProperty(ORG_NAME, "EXAMPLE");
		DEFAULT_CONFIG.setProperty(ORG_DOMAIN, "COM");
		DEFAULT_CONFIG.setProperty(TRANSPORT, "TCP");
		DEFAULT_CONFIG.setProperty(MAX_TICKET_LIFETIME, "86400000");
		DEFAULT_CONFIG.setProperty(MAX_RENEWABLE_LIFETIME, "604800000");
		DEFAULT_CONFIG.setProperty(DEBUG, "false");
	}

	/**
	 * <p>
	 * Convenience method that returns MiniKdc default configuration.
	 * </p>
	 * <p>
	 * The returned configuration is a copy, it can be customized before using it to
	 * create a MiniKdc.
	 * </p>
	 * @return a MiniKdc default configuration.
	 */
	public static Properties createConf() {
		return (Properties) DEFAULT_CONFIG.clone();
	}

	private Properties conf;

	private DirectoryService ds;

	private KdcServer kdc;

	private int port;

	private String realm;

	private File workDir;

	private File krb5conf;

	/**
	 * Creates a MiniKdc.
	 * @param conf MiniKdc configuration.
	 * @param workDir working directory, it should be the build directory. Under this
	 * directory an ApacheDS working directory will be created, this directory will be
	 * deleted when the MiniKdc stops.
	 * @throws Exception thrown if the MiniKdc could not be created.
	 */
	public MiniKdc(Properties conf, File workDir) throws Exception {
		if (!conf.keySet().containsAll(PROPERTIES)) {
			Set<String> missingProperties = new HashSet<String>(PROPERTIES);
			missingProperties.removeAll(conf.keySet());
			throw new IllegalArgumentException("Missing configuration properties: " + missingProperties);
		}
		this.workDir = new File(workDir, Long.toString(System.currentTimeMillis()));
		if (!workDir.exists() && !workDir.mkdirs()) {
			throw new RuntimeException("Cannot create directory " + workDir);
		}
		LOG.info("Configuration:");
		LOG.info("---------------------------------------------------------------");
		for (Map.Entry<?, ?> entry : conf.entrySet()) {
			LOG.info("  {}: {}", entry.getKey(), entry.getValue());
		}
		LOG.info("  localhost hostname: {}", InetAddress.getLocalHost().getHostName());
		LOG.info("  localhost canonical hostname: {}", InetAddress.getLocalHost().getCanonicalHostName());
		LOG.info("---------------------------------------------------------------");
		this.conf = conf;
		this.port = Integer.parseInt(conf.getProperty(KDC_PORT));
		if (this.port == 0) {
			ServerSocket ss = new ServerSocket(0, 1, InetAddress.getByName(this.conf.getProperty(KDC_BIND_ADDRESS)));
			this.port = ss.getLocalPort();
			ss.close();
		}
		String orgName = conf.getProperty(ORG_NAME);
		String orgDomain = conf.getProperty(ORG_DOMAIN);
		this.realm = orgName.toUpperCase() + "." + orgDomain.toUpperCase();
	}

	/**
	 * Returns the port of the MiniKdc.
	 * @return the port of the MiniKdc.
	 */
	public int getPort() {
		return this.port;
	}

	/**
	 * Returns the host of the MiniKdc.
	 * @return the host of the MiniKdc.
	 */
	public String getHost() {
		return this.conf.getProperty(KDC_BIND_ADDRESS);
	}

	/**
	 * Returns the realm of the MiniKdc.
	 * @return the realm of the MiniKdc.
	 */
	public String getRealm() {
		return this.realm;
	}

	public File getKrb5conf() {
		return this.krb5conf;
	}

	/**
	 * Starts the MiniKdc.
	 * @throws Exception thrown if the MiniKdc could not be started.
	 */
	public synchronized void start() throws Exception {
		if (this.kdc != null) {
			throw new RuntimeException("Already started");
		}
		initDirectoryService();
		initKDCServer();
	}

	private void initDirectoryService() throws Exception {
		this.ds = new DefaultDirectoryService();
		this.ds.setInstanceLayout(new InstanceLayout(this.workDir));

		CacheService cacheService = new CacheService();
		this.ds.setCacheService(cacheService);

		// first load the schema
		InstanceLayout instanceLayout = this.ds.getInstanceLayout();
		File schemaPartitionDirectory = new File(instanceLayout.getPartitionsDirectory(), "schema");
		SchemaLdifExtractor extractor = new DefaultSchemaLdifExtractor(instanceLayout.getPartitionsDirectory());
		extractor.extractOrCopy();

		SchemaLoader loader = new LdifSchemaLoader(schemaPartitionDirectory);
		SchemaManager schemaManager = new DefaultSchemaManager(loader);
		schemaManager.loadAllEnabled();
		this.ds.setSchemaManager(schemaManager);
		// Init the LdifPartition with schema
		LdifPartition schemaLdifPartition = new LdifPartition(schemaManager);
		schemaLdifPartition.setPartitionPath(schemaPartitionDirectory.toURI());

		// The schema partition
		SchemaPartition schemaPartition = new SchemaPartition(schemaManager);
		schemaPartition.setWrappedPartition(schemaLdifPartition);
		this.ds.setSchemaPartition(schemaPartition);

		JdbmPartition systemPartition = new JdbmPartition(this.ds.getSchemaManager());
		systemPartition.setId("system");
		systemPartition.setPartitionPath(
				new File(this.ds.getInstanceLayout().getPartitionsDirectory(), systemPartition.getId()).toURI());
		systemPartition.setSuffixDn(new Dn(ServerDNConstants.SYSTEM_DN));
		systemPartition.setSchemaManager(this.ds.getSchemaManager());
		this.ds.setSystemPartition(systemPartition);

		this.ds.getChangeLog().setEnabled(false);
		this.ds.setDenormalizeOpAttrsEnabled(true);
		this.ds.addLast(new KeyDerivationInterceptor());

		// create one partition
		String orgName = this.conf.getProperty(ORG_NAME).toLowerCase();
		String orgDomain = this.conf.getProperty(ORG_DOMAIN).toLowerCase();

		JdbmPartition partition = new JdbmPartition(this.ds.getSchemaManager());
		partition.setId(orgName);
		partition.setPartitionPath(new File(this.ds.getInstanceLayout().getPartitionsDirectory(), orgName).toURI());
		partition.setSuffixDn(new Dn("dc=" + orgName + ",dc=" + orgDomain));
		this.ds.addPartition(partition);
		// indexes
		Set<Index<?, ?, String>> indexedAttributes = new HashSet<Index<?, ?, String>>();
		indexedAttributes.add(new JdbmIndex<String, Entry>("objectClass", false));
		indexedAttributes.add(new JdbmIndex<String, Entry>("dc", false));
		indexedAttributes.add(new JdbmIndex<String, Entry>("ou", false));
		partition.setIndexedAttributes(indexedAttributes);

		// And start the ds
		this.ds.setInstanceId(this.conf.getProperty(INSTANCE));
		this.ds.startup();
		// context entry, after ds.startup()
		Dn dn = new Dn("dc=" + orgName + ",dc=" + orgDomain);
		Entry entry = this.ds.newEntry(dn);
		entry.add("objectClass", "top", "domain");
		entry.add("dc", orgName);
		this.ds.getAdminSession().add(entry);
	}

	private void initKDCServer() throws Exception {
		String orgName = this.conf.getProperty(ORG_NAME);
		String orgDomain = this.conf.getProperty(ORG_DOMAIN);
		String bindAddress = this.conf.getProperty(KDC_BIND_ADDRESS);
		final Map<String, String> map = new HashMap<String, String>();
		map.put("0", orgName.toLowerCase());
		map.put("1", orgDomain.toLowerCase());
		map.put("2", orgName.toUpperCase());
		map.put("3", orgDomain.toUpperCase());
		map.put("4", bindAddress);

		ClassLoader cl = Thread.currentThread().getContextClassLoader();
		InputStream is1 = cl.getResourceAsStream("minikdc.ldiff");

		SchemaManager schemaManager = this.ds.getSchemaManager();
		LdifReader reader = null;

		try {
			final String content = StrSubstitutor.replace(IOUtils.toString(is1), map);
			reader = new LdifReader(new StringReader(content));

			for (LdifEntry ldifEntry : reader) {
				this.ds.getAdminSession().add(new DefaultEntry(schemaManager, ldifEntry.getEntry()));
			}
		}
		finally {
			IOUtils.closeQuietly(reader);
			IOUtils.closeQuietly(is1);
		}

		this.kdc = new KdcServer();
		this.kdc.setDirectoryService(this.ds);

		// transport
		String transport = this.conf.getProperty(TRANSPORT);
		if (transport.trim().equals("TCP")) {
			this.kdc.addTransports(new TcpTransport(bindAddress, this.port, 3, 50));
		}
		else if (transport.trim().equals("UDP")) {
			this.kdc.addTransports(new UdpTransport(this.port));
		}
		else {
			throw new IllegalArgumentException("Invalid transport: " + transport);
		}
		this.kdc.setServiceName(this.conf.getProperty(INSTANCE));
		this.kdc.getConfig().setMaximumRenewableLifetime(Long.parseLong(this.conf.getProperty(MAX_RENEWABLE_LIFETIME)));
		this.kdc.getConfig().setMaximumTicketLifetime(Long.parseLong(this.conf.getProperty(MAX_TICKET_LIFETIME)));

		this.kdc.getConfig().setPaEncTimestampRequired(false);
		this.kdc.getConfig().setBodyChecksumVerified(false);
		this.kdc.start();

		StringBuilder sb = new StringBuilder();
		InputStream is2 = cl.getResourceAsStream("minikdc-krb5.conf");

		BufferedReader r = null;

		try {
			r = new BufferedReader(new InputStreamReader(is2, Charsets.UTF_8));
			String line = r.readLine();

			while (line != null) {
				sb.append(line).append("{3}");
				line = r.readLine();
			}
		}
		finally {
			IOUtils.closeQuietly(r);
			IOUtils.closeQuietly(is2);
		}

		this.krb5conf = new File(this.workDir, "krb5.conf").getAbsoluteFile();
		FileUtils.writeStringToFile(this.krb5conf, MessageFormat.format(sb.toString(), getRealm(), getHost(),
				Integer.toString(getPort()), System.getProperty("line.separator")));
		System.setProperty("java.security.krb5.conf", this.krb5conf.getAbsolutePath());

		System.setProperty("sun.security.krb5.debug", this.conf.getProperty(DEBUG, "false"));

		// refresh the config
		Class<?> classRef;
		if (System.getProperty("java.vendor").contains("IBM")) {
			classRef = Class.forName("com.ibm.security.krb5.internal.Config");
		}
		else {
			classRef = Class.forName("sun.security.krb5.Config");
		}
		Method refreshMethod = classRef.getMethod("refresh", new Class[0]);
		refreshMethod.invoke(classRef, new Object[0]);

		LOG.info("MiniKdc listening at port: {}", getPort());
		LOG.info("MiniKdc setting JVM krb5.conf to: {}", this.krb5conf.getAbsolutePath());
	}

	/**
	 * Stops the MiniKdc
	 */
	public synchronized void stop() {
		if (this.kdc != null) {
			System.getProperties().remove("java.security.krb5.conf");
			System.getProperties().remove("sun.security.krb5.debug");
			this.kdc.stop();
			try {
				this.ds.shutdown();
			}
			catch (Exception ex) {
				LOG.error("Could not shutdown ApacheDS properly: {}", ex.toString(), ex);
			}
		}
		delete(this.workDir);
	}

	private void delete(File f) {
		if (f.isFile()) {
			if (!f.delete()) {
				LOG.warn("WARNING: cannot delete file " + f.getAbsolutePath());
			}
		}
		else {
			for (File c : f.listFiles()) {
				delete(c);
			}
			if (!f.delete()) {
				LOG.warn("WARNING: cannot delete directory " + f.getAbsolutePath());
			}
		}
	}

	/**
	 * Creates a principal in the KDC with the specified user and password.
	 * @param principal principal name, do not include the domain.
	 * @param password password.
	 * @throws Exception thrown if the principal could not be created.
	 */
	public synchronized void createPrincipal(String principal, String password) throws Exception {
		String orgName = this.conf.getProperty(ORG_NAME);
		String orgDomain = this.conf.getProperty(ORG_DOMAIN);
		String baseDn = "ou=users,dc=" + orgName.toLowerCase() + ",dc=" + orgDomain.toLowerCase();
		String content = "dn: uid=" + principal + "," + baseDn + "\n" + "objectClass: top\n" + "objectClass: person\n"
				+ "objectClass: inetOrgPerson\n" + "objectClass: krb5principal\n" + "objectClass: krb5kdcentry\n"
				+ "cn: " + principal + "\n" + "sn: " + principal + "\n" + "uid: " + principal + "\n" + "userPassword: "
				+ password + "\n" + "krb5PrincipalName: " + principal + "@" + getRealm() + "\n"
				+ "krb5KeyVersionNumber: 0";

		for (LdifEntry ldifEntry : new LdifReader(new StringReader(content))) {
			this.ds.getAdminSession().add(new DefaultEntry(this.ds.getSchemaManager(), ldifEntry.getEntry()));
		}
	}

	/**
	 * Creates multiple principals in the KDC and adds them to a keytab file.
	 * @param keytabFile keytab file to add the created principal.s
	 * @param principals principals to add to the KDC, do not include the domain.
	 * @throws Exception thrown if the principals or the keytab file could not be created.
	 */
	public void createPrincipal(File keytabFile, String... principals) throws Exception {
		String generatedPassword = UUID.randomUUID().toString();
		Keytab keytab = new Keytab();
		List<KeytabEntry> entries = new ArrayList<KeytabEntry>();
		for (String principal : principals) {
			createPrincipal(principal, generatedPassword);
			principal = principal + "@" + getRealm();
			KerberosTime timestamp = new KerberosTime();
			for (Map.Entry<EncryptionType, EncryptionKey> entry : KerberosKeyFactory
					.getKerberosKeys(principal, generatedPassword).entrySet()) {
				EncryptionKey ekey = entry.getValue();
				byte keyVersion = (byte) ekey.getKeyVersion();
				entries.add(new KeytabEntry(principal, 1L, timestamp, keyVersion, ekey));
			}
		}
		keytab.setEntries(entries);
		keytab.write(keytabFile);
	}

	/**
	 * Creates multiple principals in the KDC and adds them to a keytab file.
	 * @param keytabFile keytab file to add the created principal.
	 * @param principal The principal to store in the keytab file.
	 * @param password The password for the principal.
	 * @throws Exception thrown if the principals or the keytab file could not be created.
	 */
	public void createKeyabFile(File keytabFile, String principal, String password) throws Exception {
		Keytab keytab = new Keytab();
		List<KeytabEntry> entries = new ArrayList<KeytabEntry>();

		KerberosTime timestamp = new KerberosTime();
		for (Map.Entry<EncryptionType, EncryptionKey> entry : KerberosKeyFactory.getKerberosKeys(principal, password)
				.entrySet()) {
			EncryptionKey ekey = entry.getValue();
			byte keyVersion = (byte) ekey.getKeyVersion();
			entries.add(new KeytabEntry(principal, 1L, timestamp, keyVersion, ekey));
		}

		keytab.setEntries(entries);
		keytab.write(keytabFile);
	}

}

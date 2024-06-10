/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ghidra;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.AccessMode;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;

import db.DBHandle;
import generic.jar.ResourceFile;
import ghidra.app.analyzers.RelocationTableSynthesizerAnalyzer;
import ghidra.app.util.DomainObjectService;
import ghidra.app.util.Option;
import ghidra.app.util.bin.ByteProvider;
import ghidra.app.util.bin.FileByteProvider;
import ghidra.app.util.bin.format.coff.CoffFileHeader;
import ghidra.app.util.bin.format.coff.CoffSectionHeader;
import ghidra.app.util.bin.format.elf.ElfException;
import ghidra.app.util.bin.format.elf.ElfHeader;
import ghidra.app.util.exporter.Exporter;
import ghidra.app.util.importer.MessageLog;
import ghidra.framework.GModule;
import ghidra.framework.data.OpenMode;
import ghidra.framework.model.DomainObject;
import ghidra.framework.store.db.PrivateDatabase;
import ghidra.program.database.ProgramDB;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressFactory;
import ghidra.program.model.address.AddressSet;
import ghidra.program.model.address.AddressSetView;
import ghidra.program.model.listing.Program;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.test.AbstractProgramBasedTest;
import ghidra.test.TestProgramManager;
import ghidra.util.NamingUtilities;
import ghidra.util.exception.VersionException;
import ghidra.util.task.TaskMonitor;
import utility.application.ApplicationLayout;

public abstract class DelinkerIntegrationTest extends AbstractProgramBasedTest {
	private static DBHandle dbHandle = null;
	private static Program program = null;

	public interface ObjectFile {
		public byte[] getSectionBytes(String name) throws IOException;

		public default void compareSectionBytes(String referenceSectionName,
				ObjectFile exportedFile, String exportedSectionName) throws Exception {
			compareSectionBytes(referenceSectionName, exportedFile, exportedSectionName,
				Collections.emptyMap());
		}

		public default void compareSectionBytes(String referenceSectionName,
				ObjectFile exportedFile, String exportedSectionName, Map<Integer, byte[]> patches)
				throws Exception {
			byte[] expectedBytes = getSectionBytes(referenceSectionName);
			byte[] actualBytes = exportedFile.getSectionBytes(exportedSectionName);

			for (Map.Entry<Integer, byte[]> entry : patches.entrySet()) {
				byte[] patch = entry.getValue();
				System.arraycopy(patch, 0, expectedBytes, entry.getKey(), patch.length);
			}

			assertArrayEquals(expectedBytes, actualBytes);
		}

		public default void compareSectionSizes(String referenceSectionName,
				ObjectFile exportedFile, String exportedSectionName) throws Exception {
			byte[] expectedBytes = getSectionBytes(referenceSectionName);
			byte[] actualBytes = exportedFile.getSectionBytes(exportedSectionName);

			assertEquals(expectedBytes.length, actualBytes.length);
		}
	}

	public class ElfObjectFile implements ObjectFile {
		private final ByteProvider byteProvider;
		private final ElfHeader header;

		public ElfObjectFile(File file) throws ElfException, IOException {
			this.byteProvider = new FileByteProvider(file, null, AccessMode.READ);
			this.header = new ElfHeader(byteProvider, s -> {
			});
			this.header.parse();
		}

		@Override
		public byte[] getSectionBytes(String name) throws IOException {
			return header.getSection(name).getRawInputStream().readAllBytes();
		}
	}

	public class CoffObjectFile implements ObjectFile {
		private final Program program;
		private final ByteProvider byteProvider;
		private final CoffFileHeader header;

		public CoffObjectFile(Program program, File file) throws IOException {
			this.program = program;
			this.byteProvider = new FileByteProvider(file, null, AccessMode.READ);
			this.header = new CoffFileHeader(byteProvider);
			this.header.parse(byteProvider, TaskMonitor.DUMMY);
		}

		@Override
		public byte[] getSectionBytes(String name) throws IOException {
			CoffSectionHeader section = header.getSections()
					.stream()
					.filter(s -> s.getName().equals(name))
					.findFirst()
					.get();
			return byteProvider.readBytes(section.getPointerToRawData(),
				section.getSize(program.getLanguage()));
		}
	}

	public static class IntegrationTestApplicationLayout extends GhidraTestApplicationLayout {
		public IntegrationTestApplicationLayout(File userSettingsDir)
				throws FileNotFoundException, IOException {
			super(userSettingsDir);
		}

		@Override
		protected Map<String, GModule> findGhidraModules() throws IOException {
			Map<String, GModule> modules = new HashMap<>(super.findGhidraModules());
			modules.put("Delinker",
				new GModule(applicationRootDirs, new ResourceFile(System.getProperty("user.dir"))));
			return Collections.unmodifiableMap(modules);
		}
	}

	@Before
	public void setUp() throws Exception {
		TestProgramManager.cleanDbTestDir();

		initialize();
	}

	@Override
	@After
	public void tearDown() throws Exception {
		dbHandle.close();
		dbHandle = null;
		program = null;

		TestProgramManager.cleanDbTestDir();
	}

	@Override
	protected Program getProgram() throws Exception {
		if (program != null) {
			return program;
		}

		File dbDir = new File(TestProgramManager.getDbTestDir(),
			NamingUtilities.mangle(getProgramName()) + ".db");
		File gzf = new File(getProgramName());

		PrivateDatabase pdb = new PrivateDatabase(dbDir, gzf, TaskMonitor.DUMMY);

		try {
			dbHandle = pdb.open(TaskMonitor.DUMMY);
			program = new ProgramDB(dbHandle, OpenMode.UPDATE, TaskMonitor.DUMMY, this);
		}
		catch (VersionException e) {
			if (!e.isUpgradable()) {
				throw e;
			}

			dbHandle = pdb.openForUpdate(TaskMonitor.DUMMY);
			program = new ProgramDB(dbHandle, OpenMode.UPGRADE, TaskMonitor.DUMMY, this);
			dbHandle.save(null, null, TaskMonitor.DUMMY);
			program.release(this);

			dbHandle = pdb.open(TaskMonitor.DUMMY);
			program = new ProgramDB(dbHandle, OpenMode.UPDATE, TaskMonitor.DUMMY, this);
		}

		return program;
	}

	@Override
	protected ApplicationLayout createApplicationLayout() throws IOException {
		return new IntegrationTestApplicationLayout(new File(getTestDirectoryPath()));
	}

	public static AddressSetView getAddressSetOfMemoryBlocks(Program program,
			List<String> memoryBlockNames) {
		AddressFactory addressFactory = program.getAddressFactory();
		AddressSet set = addressFactory.getAddressSet();
		set.clear();

		List<MemoryBlock> memoryBlocks =
			memoryBlockNames.stream().map(n -> program.getMemory().getBlock(n)).toList();
		for (MemoryBlock memoryBlock : memoryBlocks) {
			Address start = memoryBlock.getStart();
			Address end = memoryBlock.getEnd();

			set.add(addressFactory.getAddressSet(start, end));
		}

		return set;
	}

	public File exportObjectFile(AddressSetView set, Exporter exporter, List<Option> options)
			throws Exception {
		Program program = getProgram();
		MessageLog log = new MessageLog();
		RelocationTableSynthesizerAnalyzer analyzer = new RelocationTableSynthesizerAnalyzer();

		assertTrue(analyzer.added(program, set, TaskMonitor.DUMMY, log));

		if (options == null) {
			options = exporter.getOptions(new DomainObjectService() {
				@Override
				public DomainObject getDomainObject() {
					return program;
				}
			});
		}
		exporter.setOptions(options);

		File exportedFile = createTempFileForTest(".o");
		assertTrue(exporter.export(exportedFile, program, set, TaskMonitor.DUMMY));

		return exportedFile;
	}
}

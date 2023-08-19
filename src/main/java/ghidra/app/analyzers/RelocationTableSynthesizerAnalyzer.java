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
package ghidra.app.analyzers;

import java.util.List;
import java.util.stream.Collectors;

import ghidra.app.services.AbstractAnalyzer;
import ghidra.app.services.AnalysisPriority;
import ghidra.app.services.AnalyzerType;
import ghidra.app.util.importer.MessageLog;
import ghidra.program.model.address.AddressSetView;
import ghidra.program.model.listing.Data;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.listing.Program;
import ghidra.program.model.mem.MemoryAccessException;
import ghidra.program.model.relocobj.CodeRelocationSynthesizer;
import ghidra.program.model.relocobj.DataRelocationSynthesizer;
import ghidra.program.model.relocobj.RelocationTable;
import ghidra.util.classfinder.ClassSearcher;
import ghidra.util.exception.CancelledException;
import ghidra.util.task.TaskMonitor;

public class RelocationTableSynthesizerAnalyzer extends AbstractAnalyzer {
	private final static String NAME = "Relocation table synthesizer";
	private final static String DESCRIPTION =
		"Synthesize a relocation table for this program";

	public RelocationTableSynthesizerAnalyzer() {
		super(NAME, DESCRIPTION, AnalyzerType.BYTE_ANALYZER);
		setDefaultEnablement(false);
		setPriority(AnalysisPriority.LOW_PRIORITY);
		setPrototype();
		setSupportsOneTimeAnalysis();
	}

	public static List<CodeRelocationSynthesizer> getCodeSynthesizers(Program program) {
		return ClassSearcher.getInstances(CodeRelocationSynthesizer.class)
				.stream()
				.filter(s -> s.canAnalyze(program))
				.collect(Collectors.toList());
	}

	public static List<DataRelocationSynthesizer> getDataSynthesizers(Program program) {
		return ClassSearcher.getInstances(DataRelocationSynthesizer.class)
				.stream()
				.filter(s -> s.canAnalyze(program))
				.collect(Collectors.toList());
	}

	@Override
	public boolean added(Program program, AddressSetView set, TaskMonitor monitor, MessageLog log)
			throws CancelledException {
		RelocationTable relocationTable = RelocationTable.get(program);
		relocationTable.clear(set);

		Listing listing = program.getListing();
		FunctionManager functionManager = program.getFunctionManager();

		List<CodeRelocationSynthesizer> codeSynthesizers = getCodeSynthesizers(program);
		if (codeSynthesizers.isEmpty()) {
			log.appendMsg(getClass().getSimpleName(),
				"No code relocation synthesizers found for this processor!");
		}
		List<DataRelocationSynthesizer> dataSynthesizers = getDataSynthesizers(program);
		if (dataSynthesizers.isEmpty()) {
			log.appendMsg(getClass().getSimpleName(),
				"No data relocation synthesizers found for this processor!");
		}

		for (Function function : functionManager.getFunctions(set, true)) {
			monitor.setMessage("Relocation table synthesizer: " + function.getName(true));
			monitor.checkCancelled();

			processFunction(codeSynthesizers, function, relocationTable, log);
		}

		for (Data data : listing.getDefinedData(set, true)) {
			monitor.setMessage(
				"Relocation table synthesizer: " + data.getAddressString(true, true));
			monitor.checkCancelled();

			processData(dataSynthesizers, data, relocationTable, log);
		}

		return true;
	}

	private static void processFunction(List<CodeRelocationSynthesizer> synthesizers,
			Function function,
			RelocationTable relocationTable, MessageLog log) {
		for (CodeRelocationSynthesizer synthesizer : synthesizers) {
			try {
				synthesizer.processFunction(function.getProgram(), function, relocationTable, log);
			}
			catch (MemoryAccessException e) {
				log.appendException(e);
			}
		}
	}

	private static void processData(List<DataRelocationSynthesizer> synthesizers, Data parent,
			RelocationTable relocationTable, MessageLog log) {
		if (parent.isPointer()) {
			for (DataRelocationSynthesizer synthesizer : synthesizers) {
				try {
					synthesizer.processPointer(parent.getProgram(), parent, relocationTable, log);
				}
				catch (MemoryAccessException e) {
					log.appendException(e);
				}
			}
		}
		else if (parent.isArray() && parent.getNumComponents() >= 1) {
			Data data = parent.getComponent(0);

			if (data.isPointer() || data.isArray() || data.isStructure()) {
				for (int i = 0; i < parent.getNumComponents(); i++) {
					processData(synthesizers, parent.getComponent(i), relocationTable, log);
				}
			}
		}
		else if (parent.isStructure()) {
			for (int i = 0; i < parent.getNumComponents(); i++) {
				processData(synthesizers, parent.getComponent(i), relocationTable, log);
			}
		}
	}

	@Override
	public boolean canAnalyze(Program program) {
		return !getCodeSynthesizers(program).isEmpty() || !getDataSynthesizers(program).isEmpty();
	}
}

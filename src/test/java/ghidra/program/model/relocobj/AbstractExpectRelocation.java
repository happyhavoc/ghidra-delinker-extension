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
package ghidra.program.model.relocobj;

import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSetView;
import ghidra.util.DataConverter;

public abstract class AbstractExpectRelocation implements Relocation {
	@Override
	public RelocationTable getRelocationTable() {
		throw new UnsupportedOperationException("Unimplemented method 'getRelocationTable'");
	}

	@Override
	public Address getAddress() {
		throw new UnsupportedOperationException("Unimplemented method 'getAddress'");
	}

	@Override
	public String getSymbolName() {
		throw new UnsupportedOperationException("Unimplemented method 'getSymbolName'");
	}

	@Override
	public long getAddend() {
		throw new UnsupportedOperationException("Unimplemented method 'getAddend'");
	}

	@Override
	public void delete() {
		throw new UnsupportedOperationException("Unimplemented method 'delete'");
	}

	@Override
	public void unapply(byte[] buffer, AddressSetView bufferAddressSet, DataConverter dc,
			boolean encodeAddend, boolean adjustRelativeWithTargetSize) {
		throw new UnsupportedOperationException("Unimplemented method 'unapply'");
	}
}

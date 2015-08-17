/**
 * (C) Copyright IBM Corp. 2010, 2015
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */

package com.ibm.bi.dml.runtime.instructions.mr;

import com.ibm.bi.dml.runtime.DMLRuntimeException;
import com.ibm.bi.dml.runtime.DMLUnsupportedOperationException;
import com.ibm.bi.dml.runtime.instructions.Instruction;
import com.ibm.bi.dml.runtime.instructions.InstructionUtils;
import com.ibm.bi.dml.runtime.matrix.data.MatrixValue;
import com.ibm.bi.dml.runtime.matrix.mapred.CachedValueMap;
import com.ibm.bi.dml.runtime.matrix.mapred.IndexedMatrixValue;
import com.ibm.bi.dml.runtime.matrix.operators.Operator;


public class CombineBinaryInstruction extends BinaryMRInstructionBase
{
	
	/*
	 * combinebinary:::0:DOUBLE:::1:INT:::2:INT
	 */
	private boolean secondInputIsWeight=true;
	public CombineBinaryInstruction(Operator op, boolean isWeight, byte in1, byte in2, byte out, String istr) {
		super(op, in1, in2, out);
		secondInputIsWeight=isWeight;
		mrtype = MRINSTRUCTION_TYPE.CombineBinary;
		instString = istr;
	}

	public static Instruction parseInstruction ( String str ) throws DMLRuntimeException {
		
		InstructionUtils.checkNumFields ( str, 4 );
		
		String[] parts = InstructionUtils.getInstructionParts ( str );
		
		byte in1, in2, out;
		String opcode = parts[0];
		boolean isWeight=Boolean.parseBoolean(parts[1]);
		in1 = Byte.parseByte(parts[2]);
		in2 = Byte.parseByte(parts[3]);
		out = Byte.parseByte(parts[4]);
		
		if ( opcode.equalsIgnoreCase("combinebinary") ) {
			return new CombineBinaryInstruction(null, isWeight, in1, in2, out, str);
		}else
			return null;
	}
	
	public boolean isSecondInputWeight()
	{
		return secondInputIsWeight;
	}

	@Override
	public void processInstruction(Class<? extends MatrixValue> valueClass,
			CachedValueMap cachedValues, IndexedMatrixValue tempValue,
			IndexedMatrixValue zeroInput, int blockRowFactor, int blockColFactor)
			throws DMLUnsupportedOperationException, DMLRuntimeException {
		throw new DMLRuntimeException("CombineInstruction.processInstruction should never be called!");
		
	}
	
}

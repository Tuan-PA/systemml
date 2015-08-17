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

import java.util.ArrayList;

import com.ibm.bi.dml.runtime.DMLRuntimeException;
import com.ibm.bi.dml.runtime.DMLUnsupportedOperationException;
import com.ibm.bi.dml.runtime.instructions.Instruction;
import com.ibm.bi.dml.runtime.instructions.InstructionUtils;
import com.ibm.bi.dml.runtime.matrix.data.MatrixIndexes;
import com.ibm.bi.dml.runtime.matrix.data.MatrixValue;
import com.ibm.bi.dml.runtime.matrix.mapred.CachedValueMap;
import com.ibm.bi.dml.runtime.matrix.mapred.IndexedMatrixValue;
import com.ibm.bi.dml.runtime.matrix.operators.Operator;


/**
 * Supported optcodes: replace.
 * 
 */
public class ParameterizedBuiltinMRInstruction extends UnaryInstruction
{	
	
	private double _pattern;
	private double _replace;
	
	public ParameterizedBuiltinMRInstruction(Operator op, byte in, double pattern, double replace, byte out, String istr)
	{
		super(op, in, out, istr);
		instString = istr;
		
		_pattern = pattern;
		_replace = replace;
	}
	
	/**
	 * 
	 * @param str
	 * @return
	 * @throws DMLRuntimeException
	 */
	public static Instruction parseInstruction ( String str ) 
		throws DMLRuntimeException 
	{
		InstructionUtils.checkNumFields ( str, 4 );
		
		String[] parts = InstructionUtils.getInstructionParts(str);
		String opcode = parts[0];
		
		if(!opcode.equalsIgnoreCase("replace"))
			throw new DMLRuntimeException("Unknown opcode while parsing an ParameterizedBuiltinMRInstruction: " + str);
		
		byte in = Byte.parseByte(parts[1]);
		double pattern = Double.parseDouble(parts[2]);
		double replace = Double.parseDouble(parts[3]);
		byte out = Byte.parseByte(parts[4]);
		
		return new ParameterizedBuiltinMRInstruction(new Operator(true), in, pattern, replace, out, str);
	}
	
	@Override
	public void processInstruction(Class<? extends MatrixValue> valueClass,
			CachedValueMap cachedValues, IndexedMatrixValue tempValue,
			IndexedMatrixValue zeroInput, int blockRowFactor, int blockColFactor)
		throws DMLUnsupportedOperationException, DMLRuntimeException 
	{		
		ArrayList<IndexedMatrixValue> blkList = cachedValues.get(input);
		if( blkList !=null )
			for(IndexedMatrixValue imv : blkList)
			{
				if(imv==null)
					continue;
				MatrixValue in = imv.getValue();
				MatrixIndexes inIX = imv.getIndexes();
				
				//allocate space for the output value
				IndexedMatrixValue iout = null;
				if(output==input)
					iout=tempValue;
				else
					iout=cachedValues.holdPlace(output, valueClass);
				iout.getIndexes().setIndexes(inIX);
				MatrixValue out = iout.getValue();
				
				//process instruction
				in.replaceOperations(out, _pattern, _replace);
				
				//put the output value in the cache
				if(iout==tempValue)
					cachedValues.add(output, iout);
			}
	}
}

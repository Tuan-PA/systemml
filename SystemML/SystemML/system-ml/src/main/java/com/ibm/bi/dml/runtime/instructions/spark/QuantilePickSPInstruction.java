/**
 * IBM Confidential
 * OCO Source Materials
 * (C) Copyright IBM Corp. 2010, 2015
 * The source code for this program is not published or otherwise divested of its trade secrets, irrespective of what has been deposited with the U.S. Copyright Office.
 */

package com.ibm.bi.dml.runtime.instructions.spark;

import java.util.List;

import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.PairFunction;

import scala.Tuple2;

import com.ibm.bi.dml.lops.PickByCount.OperationTypes;
import com.ibm.bi.dml.runtime.DMLRuntimeException;
import com.ibm.bi.dml.runtime.DMLUnsupportedOperationException;
import com.ibm.bi.dml.runtime.controlprogram.context.ExecutionContext;
import com.ibm.bi.dml.runtime.controlprogram.context.SparkExecutionContext;
import com.ibm.bi.dml.runtime.instructions.Instruction;
import com.ibm.bi.dml.runtime.instructions.InstructionUtils;
import com.ibm.bi.dml.runtime.instructions.cp.CPOperand;
import com.ibm.bi.dml.runtime.instructions.cp.DoubleObject;
import com.ibm.bi.dml.runtime.instructions.cp.ScalarObject;
import com.ibm.bi.dml.runtime.instructions.spark.functions.RDDAggregateUtils;
import com.ibm.bi.dml.runtime.matrix.MatrixCharacteristics;
import com.ibm.bi.dml.runtime.matrix.data.MatrixBlock;
import com.ibm.bi.dml.runtime.matrix.data.MatrixIndexes;
import com.ibm.bi.dml.runtime.matrix.operators.Operator;
import com.ibm.bi.dml.runtime.util.UtilFunctions;

public class QuantilePickSPInstruction extends BinarySPInstruction
{
	@SuppressWarnings("unused")
	private static final String _COPYRIGHT = "Licensed Materials - Property of IBM\n(C) Copyright IBM Corp. 2010, 2015\n" +
                                             "US Government Users Restricted Rights - Use, duplication  disclosure restricted by GSA ADP Schedule Contract with IBM Corp.";
	private OperationTypes _type = null;
	
	public QuantilePickSPInstruction(Operator op, CPOperand in, CPOperand out, OperationTypes type, boolean inmem, String opcode, String istr){
		this(op, in, null, out, type, inmem, opcode, istr);
	}
	
	public QuantilePickSPInstruction(Operator op, CPOperand in, CPOperand in2, CPOperand out,  OperationTypes type, boolean inmem, String opcode, String istr){
		super(op, in, in2, out, opcode, istr);
		_sptype = SPINSTRUCTION_TYPE.QPick;
		
		_type = type;
		//inmem ignored here
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
		String[] parts = InstructionUtils.getInstructionPartsWithValueType(str);
		String opcode = parts[0];
		
		//sanity check opcode
		if ( !opcode.equalsIgnoreCase("qpick") ) {
			throw new DMLRuntimeException("Unknown opcode while parsing a QuantilePickCPInstruction: " + str);
		}
		
		//instruction parsing
		if( parts.length == 4 )
		{
			//instructions of length 4 originate from unary - mr-iqm
			//TODO this should be refactored to use pickvaluecount lops
			CPOperand in1 = new CPOperand(parts[1]);
			CPOperand in2 = new CPOperand(parts[2]);
			CPOperand out = new CPOperand(parts[3]);
			OperationTypes ptype = OperationTypes.IQM;
			boolean inmem = false;
			return new QuantilePickSPInstruction(null, in1, in2, out, ptype, inmem, opcode, str);			
		}
		else if( parts.length == 5 )
		{
			CPOperand in1 = new CPOperand(parts[1]);
			CPOperand out = new CPOperand(parts[2]);
			OperationTypes ptype = OperationTypes.valueOf(parts[3]);
			boolean inmem = Boolean.parseBoolean(parts[4]);
			return new QuantilePickSPInstruction(null, in1, out, ptype, inmem, opcode, str);
		}
		else if( parts.length == 6 )
		{
			CPOperand in1 = new CPOperand(parts[1]);
			CPOperand in2 = new CPOperand(parts[2]);
			CPOperand out = new CPOperand(parts[3]);
			OperationTypes ptype = OperationTypes.valueOf(parts[4]);
			boolean inmem = Boolean.parseBoolean(parts[5]);
			return new QuantilePickSPInstruction(null, in1, in2, out, ptype, inmem, opcode, str);
		}
		
		return null;
	}
	
	@Override
	public void processInstruction(ExecutionContext ec)
			throws DMLUnsupportedOperationException, DMLRuntimeException 
	{
		SparkExecutionContext sec = (SparkExecutionContext)ec;
		
		MatrixCharacteristics mc = sec.getMatrixCharacteristics(input1.getName());
		boolean weighted = (mc.getCols()==2);
		
		//get input rdds
		JavaPairRDD<MatrixIndexes,MatrixBlock> in = sec.getBinaryBlockRDDHandleForVariable( input1.getName() );
		
		//NOTE: no difference between inmem/mr pick (see related cp instruction), but wrt w/ w/o weights
		//(in contrast to cp instructions, w/o weights does not materializes weights of 1)
		switch( _type ) 
		{
			case VALUEPICK: {
				double sum_wt = weighted ? sumWeights(in) : mc.getRows();
				ScalarObject quantile = ec.getScalarInput(input2.getName(), input2.getValueType(), input2.isLiteral());
				long key = (long)Math.ceil(quantile.getDoubleValue()*sum_wt);
				double val = lookupKey(in, key, mc.getRowsPerBlock());
				ec.setScalarOutput(output.getName(), new DoubleObject(val));
				
				break;
			}
			
			case MEDIAN: {
				double sum_wt = weighted ? sumWeights(in) : mc.getRows();
				long key = (long)Math.ceil(0.5*sum_wt);
				double val = lookupKey(in, key, mc.getRowsPerBlock());
				ec.setScalarOutput(output.getName(), new DoubleObject(val));
								
				break;
			}
			
			case IQM: {
				double sum_wt = weighted ? sumWeights(in) : mc.getRows();
				long key25 = (long)Math.ceil(0.25*sum_wt);
				long key75 = (long)Math.ceil(0.75*sum_wt);	
				double val25 = lookupKey(in, key25, mc.getRowsPerBlock());
				double val75 = lookupKey(in, key75, mc.getRowsPerBlock());
				JavaPairRDD<MatrixIndexes,MatrixBlock> out = in
						.filter(new FilterFunction(key25+1,key75,mc.getRowsPerBlock()))
						.mapToPair(new ExtractAndSumFunction(key25+1, key75, mc.getRowsPerBlock()));
				MatrixBlock mb = RDDAggregateUtils.sumStable(out);					
				double val = (mb.getValue(0, 0) 
				             + (key25-0.25*sum_wt)*val25
				             - (key75-0.75*sum_wt)*val75)
				             / (0.5*sum_wt);
				ec.setScalarOutput(output.getName(), new DoubleObject(val));
				
				break;
			}
		
			default:
				throw new DMLRuntimeException("Unsupported qpick operation type: "+_type);
		}
	}
	
	/**
	 * 
	 * @param in
	 * @param key
	 * @param brlen
	 * @return
	 */
	private double lookupKey(JavaPairRDD<MatrixIndexes,MatrixBlock> in, long key, int brlen)
	{
		long rix = UtilFunctions.blockIndexCalculation(key, brlen);
		long pos = UtilFunctions.cellInBlockCalculation(key, brlen);		
				
		List<MatrixBlock> val = in.lookup(new MatrixIndexes(rix,1));
		return val.get(0).quickGetValue((int)pos, 0);
	}
	
	/**
	 * 
	 * @param in
	 * @return
	 */
	private double sumWeights(JavaPairRDD<MatrixIndexes,MatrixBlock> in)
	{
		JavaPairRDD<MatrixIndexes,MatrixBlock> tmp = in
				.mapValues(new ExtractAndSumWeightsFunction());
		MatrixBlock val = RDDAggregateUtils.sumStable(tmp);
		
		return val.quickGetValue(0, 0);
	}

	/**
	 * 
	 */
	private static class FilterFunction implements Function<Tuple2<MatrixIndexes,MatrixBlock>, Boolean> 
	{
		private static final long serialVersionUID = -8249102381116157388L;

		//boundary keys (inclusive)
		private long _minRowIndex;
		private long _maxRowIndex;
		
		public FilterFunction(long key25, long key75, int brlen)
		{
			_minRowIndex = UtilFunctions.blockIndexCalculation(key25, brlen);
			_maxRowIndex = UtilFunctions.blockIndexCalculation(key75, brlen);
		}

		@Override
		public Boolean call(Tuple2<MatrixIndexes, MatrixBlock> arg0)
			throws Exception 
		{
			long rowIndex = arg0._1().getRowIndex();
			return (rowIndex>=_minRowIndex && rowIndex<=_maxRowIndex);
		}
	}
	
	/**
	 * 
	 */
	private static class ExtractAndSumFunction implements PairFunction<Tuple2<MatrixIndexes,MatrixBlock>,MatrixIndexes,MatrixBlock> 
	{
		private static final long serialVersionUID = -584044441055250489L;
		
		//boundary keys (inclusive)
		private long _minRowIndex;
		private long _maxRowIndex;
		private long _minPos;
		private long _maxPos;
		
		public ExtractAndSumFunction(long key25, long key75, int brlen)
		{
			_minRowIndex = UtilFunctions.blockIndexCalculation(key25, brlen);
			_maxRowIndex = UtilFunctions.blockIndexCalculation(key75, brlen);
			_minPos = UtilFunctions.cellInBlockCalculation(key25, brlen);
			_maxPos = UtilFunctions.cellInBlockCalculation(key75, brlen);
		}
		
		@Override
		public Tuple2<MatrixIndexes, MatrixBlock> call(Tuple2<MatrixIndexes, MatrixBlock> arg0) 
			throws Exception 
		{
			MatrixIndexes ix = arg0._1();
			MatrixBlock mb = arg0._2();
			
			if( _minRowIndex==_maxRowIndex ){
				mb = mb.sliceOperations(_minPos, _maxPos, 1, 1, new MatrixBlock());
			}
			else if( ix.getRowIndex() == _minRowIndex ) {
				mb = mb.sliceOperations(_minPos+1, mb.getNumRows(), 1, 1, new MatrixBlock());	
			}
			else if( ix.getRowIndex() == _maxRowIndex ) {
				mb = mb.sliceOperations(1, _maxPos+1, 1, 1, new MatrixBlock());	
			}
			
			//create output (with correction)
			MatrixBlock ret = new MatrixBlock(1,2,false);
			ret.setValue(0, 0, mb.sum());
			
			return new Tuple2<MatrixIndexes,MatrixBlock>(new MatrixIndexes(1,1), ret);
		}
	}
	
	/**
	 * 
	 */
	private static class ExtractAndSumWeightsFunction implements Function<MatrixBlock,MatrixBlock> 
	{
		private static final long serialVersionUID = 7169831202450745373L;

		@Override
		public MatrixBlock call(MatrixBlock arg0) 
			throws Exception 
		{
			//slice operation
			 MatrixBlock mb = arg0.sliceOperations(1, arg0.getNumRows(), 2, 2, new MatrixBlock());
			
			//create output (with correction)
			MatrixBlock ret = new MatrixBlock(1,2,false);
			ret.setValue(0, 0, mb.sum());
			
			return ret;
		}
	}
}

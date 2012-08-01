package com.ibm.bi.dml.parser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;

import com.ibm.bi.dml.api.DMLScript;
import com.ibm.bi.dml.parser.Expression.BinaryOp;
import com.ibm.bi.dml.parser.Expression.DataType;
import com.ibm.bi.dml.runtime.controlprogram.ParForProgramBlock.PExecMode;
import com.ibm.bi.dml.runtime.controlprogram.ParForProgramBlock.POptMode;
import com.ibm.bi.dml.runtime.controlprogram.ParForProgramBlock.PTaskPartitioner;
import com.ibm.bi.dml.runtime.controlprogram.parfor.stat.InfrastructureAnalyzer;
import com.ibm.bi.dml.runtime.controlprogram.parfor.stat.Timing;
import com.ibm.bi.dml.runtime.controlprogram.parfor.util.IDSequence;
import com.ibm.bi.dml.utils.LanguageException;

/**
 * This ParForStatementBlock is essentially identical to a ForStatementBlock, except an extended validate
 * for checking optional parfor parameters and running the loop dependency analysis.
 * 
 * NOTE: related test suite: dml.test.components.parser.ParForDependencyAnalysisTest 
 * 
 *
 */
public class ParForStatementBlock extends ForStatementBlock 
{
	//external parameter names 
	private static HashSet<String> _paramNames;
	public static final String CHECK            = "check";       //run loop dependency analysis
	public static final String PAR              = "par";         //number of parallel workers
	public static final String TASK_SIZE        = "tasksize";    //number of tasks 
	public static final String TASK_PARTITIONER = "partitioner"; //task partitioner 
	public static final String EXEC_MODE        = "mode";        //runtime execution mode	
	public static final String OPT_MODE         = "opt";        //runtime execution mode	
	
	//default external parameter values
	private static HashMap<String, String> _paramDefaults;
	
	//internal parameter values
	private static final boolean NORMALIZE                 = false; //normalize FOR from to incr
	private static final boolean USE_FN_CACHE              = true; //useful for larger scripts (due to O(n^2))
	private static final boolean ABORT_ON_FIRST_DEPENDENCY = true;
	
	public static final String INTERAL_FN_INDEX_ROW       = "__ixr"; //pseudo index for range indexing row
	public static final String INTERAL_FN_INDEX_COL       = "__ixc"; //pseudo index for range indexing col 
	
	//class members
	private static IDSequence _idSeq = null;
	
	private static HashMap<String, LinearFunction> _fncache; //slower for most (small cases) cases
	
	//instance members
	private long 		      _ID         = -1;
	private VariableSet       _vsParent   = null;  
	private ArrayList<String> _resultVars = null;
	private Bounds            _bounds     = null;
	
	static
	{
		// populate parameter name lookup-table
		_paramNames = new HashSet<String>();
		_paramNames.add( CHECK ); 
		_paramNames.add( PAR ); 
		_paramNames.add( TASK_SIZE ); 
		_paramNames.add( TASK_PARTITIONER ); 
		_paramNames.add( EXEC_MODE ); 
		_paramNames.add( OPT_MODE ); 
		
		// populate defaults lookup-table
		_paramDefaults = new HashMap<String, String>();
		_paramDefaults.put( CHECK,            "1" );
		_paramDefaults.put( PAR,              String.valueOf(InfrastructureAnalyzer.getLocalParallelism()) );
		_paramDefaults.put( TASK_SIZE,        "1" );
		_paramDefaults.put( TASK_PARTITIONER, String.valueOf(PTaskPartitioner.FIXED) );
		_paramDefaults.put( EXEC_MODE,        String.valueOf(PExecMode.LOCAL) );
		_paramDefaults.put( OPT_MODE,         String.valueOf(POptMode.NONE) );
		
		_idSeq = new IDSequence();
	}
	
	public ParForStatementBlock()
	{
		_ID         = _idSeq.getNextID();
		_resultVars = new ArrayList<String>();
		
		if( USE_FN_CACHE )
			_fncache = new HashMap<String, LinearFunction>();
		
		if( DMLScript.DEBUG )
			System.out.println("PARFOR("+_ID+"): ParForStatementBlock instance created");
	}
	
	public long getID()
	{
		return _ID;
	}

	public ArrayList<String> getResultVariables()
	{
		return _resultVars;
	}
	
	private void addToResultVariablesNoDup( String var )
	{
		if( !_resultVars.contains( var ) )
			_resultVars.add( var );
	}
	
	@Override
	@SuppressWarnings("unchecked")
	public VariableSet validate(DMLProgram dmlProg, VariableSet ids, HashMap<String,ConstIdentifier> constVars)
		throws LanguageException, ParseException, IOException 
	{	
		//DMLScript.DEBUG=true;
		
		if( DMLScript.DEBUG )
			System.out.println("PARFOR("+_ID+"): validating ParForStatementBlock.");		
		
		//create parent variable set via cloning
		_vsParent = new VariableSet();
		_vsParent._variables = (HashMap<String, DataIdentifier>) ids.getVariables().clone();
				
		if(DMLScript.DEBUG) //note: A is matrix, and A[i,1] is scalar  
			for( DataIdentifier di : _vsParent._variables.values() )
				System.out.println("PARFOR: non-local "+di._name+": "+di.getDataType().toString()+" with rowDim = "+di.getDim1()); 
		
		//normal validate via ForStatement (sequential)
		//NOTE: validate/dependency checking of nested parfor-loops happens at this point
		VariableSet vs = super.validate(dmlProg, ids, constVars);
		
		//check of correctness of specified parfor parameter names and 
		//set default parameter values for all not specified parameters 
		ParForStatement pfs = (ParForStatement) _statements.get(0);
		IterablePredicate predicate = pfs.getIterablePredicate();
		HashMap<String, String> params = predicate.getParForParams();
		if( params != null ) //if parameter specified
		{
			for( String key : params.keySet() )
				if( !_paramNames.contains(key) )
					throw new LanguageException("PARFOR: The specified parameter '"+key+"' is no valid parfor parameter.");
			
			//set defaults for all non-specified values
			for( String key : _paramNames )
				if( !params.containsKey(key) )
					params.put(key, _paramDefaults.get(key));
		}
		else
		{
			//set all defaults
			params = new HashMap<String, String>();
			params.putAll( _paramDefaults );
			predicate.setParForParams(params);
		}	
		
		//start time measurement for normalization and dependency analysis
		Timing time = new Timing();
		time.start();
		
		// LOOP DEPENDENCY ANALYSIS (test for dependency existence)
		// no false negative guaranteed, but possibly false positives
		
		/* Basic intuition: WRITES to NON-local variables are only permitted iff
		 *   - no data dep (no read other than own iteration w i < r j)
		 *   - no anti dep (no read other than own iteration w i > r j)
		 *   - no output dep (no write other than own iteration)
		 *   
		 * ALGORITHM:
		 * 1) Determine candidates C (writes to non-local variables)
		 * 2) Prune all c from C where no dependencies --> C'
		 * 3) Raise an exception/warning if C' not the empty set 
		 * 
		 * RESTRICTIONS:
		 * - array subscripts of non-local variables must be linear functions of the form 
		 *   a0+ a1*i + ... + a2*j, where i and j are for or parfor indexes 
		 *   (This implies that no local or non-local variables can be used in subscripts of 
		 *    non-local variables. However, for local variables arbitrary expressions are possible).
		 * - for and parfor increments must be integer values 
		 * - only static (integer lower, upper bounds) range indexing
		 * - only input variables considered as potential candidates for checking 
		 *   (TODO: in order to remove the last restriction, dependencies must be checked again after 
		 *   live variable analysis against LIVEOUT)
		 * 
		 * NOTE: validity is only checked during compilation, i.e., for dynamic from, to, incr MIN MAX values assumed.        
		 */ 
		
		if( DMLScript.DEBUG )
			System.out.println("PARFOR: running loop dependency analysis ...");

		//### Step 1 ###: determine candidate set C
		HashSet<Candidate> C = new HashSet<Candidate>(); 
		HashSet<Candidate> C2 = new HashSet<Candidate>(); 
		Integer sCount = 0; //object for call by ref 
		rDetermineCandidates(pfs.getBody(), C, sCount);

		
		boolean check = (Integer.parseInt(params.get(CHECK))==1);
		if( check ) 
		{			
			//### Step 2 ###: prune c without dependencies
			_bounds = new Bounds();
			rDetermineBounds( dmlProg.getBlocks() ); //writes to _bounds
			
			for( Candidate c : C )
			{
				DataType cdt = _vsParent.getVariables().get(c._var).getDataType(); //might be different in DataIdentifier
				
				//assume no dependency
				sCount = 0; 				
				boolean[] dep = new boolean[]{false,false,false}; //ouput, data, anti
				rCheckCandidates(c, cdt, pfs.getBody(), sCount, dep);
				

				if( DMLScript.DEBUG )
				{
					if( dep[0] ) System.out.println("PARFOR: output dependency detected for var '"+c._var+"'.");
					if( dep[1] ) System.out.println("PARFOR: data dependency detected for var '"+c._var+"'.");
					if( dep[2] ) System.out.println("PARFOR: anti dependency detected for var '"+c._var+"'.");
				}
				
				if( dep[0] || dep[1] || dep[2] )
				{
					C2.add(c);
					if( ABORT_ON_FIRST_DEPENDENCY )
						break;
				}
			}

			
			//### Step 3 ###: raise an exception / warning
			if( C2.size() > 0 )
			{
				if( DMLScript.DEBUG )
					System.out.println("PARFOR: loop dependencies detected.");

				StringBuffer depVars = new StringBuffer();
				for( Candidate c : C2 )
				{
					if( depVars.length()>0 )
						depVars.append(", ");
					depVars.append(c._var);
				}
				
				throw new LanguageException( "PARFOR loop dependency analysis: " +
						                     "inter-iteration (loop-carried) dependencies detected for variable(s): " +
						                     depVars.toString() +".\n " +
						                     "Please ensure independence of iterations." );				
			}
			else
			{
				if( DMLScript.DEBUG )
					System.out.println("PARFOR: no loop dependencies detected.");
			}
			
		}
		else
		{
			if( DMLScript.DEBUG )
				System.out.println("PARFOR: loop dependency analysis skipped.");
		}
		
		//if successful, prepare result variables (all distinct vars in all candidates)
		//add own candidates
		for( Candidate var : C )
			addToResultVariablesNoDup( var._var );
		//get and add child result vars
		ArrayList<String> tmp = new ArrayList<String>();
		rConsolidateResultVars(pfs.getBody(), tmp);
		for( String var : tmp )
			addToResultVariablesNoDup( var );
		
		System.out.println("INFO: PARFOR("+_ID+"): validate successfully finished (no dependencies) in "+time.stop()+"ms.");
		
		return vs;
	}

	/**
	 * This method recursively determines candidates for output,data,anti dependencies. 
	 * Candidates are defined as writes to non-local variables.
	 * 
	 * @param asb
	 * @param C
	 * @param sCount
	 * @throws LanguageException 
	 */
	private void rDetermineCandidates(ArrayList<StatementBlock> asb, HashSet<Candidate> C, Integer sCount) 
		throws LanguageException 
	{
		for(StatementBlock sb : asb ) // foreach statementblock in parforbody
			for( Statement s : sb._statements ) // foreach statement in statement block
			{
				sCount++;
			
				if( s instanceof ForStatement && !(s instanceof ParForStatement) )
				{
					rDetermineCandidates(((ForStatement)s).getBody(), C, sCount);
				}
				else if( s instanceof WhileStatement ) 
				{
					rDetermineCandidates(((WhileStatement)s).getBody(), C, sCount);
				}
				else if( s instanceof IfStatement ) 
				{
					rDetermineCandidates(((IfStatement)s).getIfBody(), C, sCount);
					rDetermineCandidates(((IfStatement)s).getElseBody(), C, sCount);
				}
				else if( s instanceof FunctionStatement ) 
				{
					rDetermineCandidates(((FunctionStatement)s).getBody(), C, sCount);
				}
				else
				{
					VariableSet vsUpdated = s.variablesUpdated();
					for(String write : vsUpdated.getVariableNames())
					{						 						
						//add writes to non-local variables to candidate set
						if( _vsParent.containsVariable(write) )
						{
							Collection<DataIdentifier> dats = getDataIdentifiers( s, true );
							for( DataIdentifier dat : dats )
							{
								Candidate c = new Candidate();
								c._var = write; 
								c._dat = dat; 
								C.add( c );
							}
							
							if( DMLScript.DEBUG )
								System.out.println("PARFOR: dependency candidate: var '"+write+"'");
						}
					}
				}
			}
	}
	
	private void rConsolidateResultVars(ArrayList<StatementBlock> asb, ArrayList<String> vars) 
		throws LanguageException 
	{
		for(StatementBlock sb : asb ) // foreach statementblock in parforbody
		{
			if( sb instanceof ParForStatementBlock )
			{
				vars.addAll(((ParForStatementBlock)sb).getResultVariables());
			}
			
			for( Statement s : sb._statements ) // foreach statement in statement block
			{
				if( s instanceof ForStatement || s instanceof ParForStatement )
				{
					rConsolidateResultVars(((ForStatement)s).getBody(), vars);
				}
				else if( s instanceof WhileStatement ) 
				{
					rConsolidateResultVars(((WhileStatement)s).getBody(), vars);
				}
				else if( s instanceof IfStatement ) 
				{
					rConsolidateResultVars(((IfStatement)s).getIfBody(), vars);
					rConsolidateResultVars(((IfStatement)s).getElseBody(), vars);
				}
				else if( s instanceof FunctionStatement ) 
				{
					rConsolidateResultVars(((FunctionStatement)s).getBody(), vars);
				}
			}
		}
	}

	/**
	 * This method recursively checks a candidate against StatementBlocks for anti, data and output dependencies.
	 * A LanguageException is raised if at least one dependency is found, where it is guaranteed that no false negatives 
	 * (undetected dependency) but potentially false positives (misdetected dependency) can appear.  
	 * 
	 * 
	 * @param c
	 * @param cdt
	 * @param asb
	 * @param sCount
	 * @param dep
	 * @throws LanguageException
	 */
	private void rCheckCandidates(Candidate c, DataType cdt, ArrayList<StatementBlock> asb, 
			                      Integer sCount, boolean[] dep) 
		throws LanguageException 
	{	
		// check candidate only (output dependency if scalar or constant matrix subscript)
		if(    cdt == DataType.SCALAR 
			|| cdt == DataType.OBJECT  ) //dat2 checked for other candidate 
		{
			//every write to a scalar or complete data object is an output dependency
			dep[0] = true;
			if( ABORT_ON_FIRST_DEPENDENCY )
				return;
		}
		else if( cdt == DataType.MATRIX ) 
		{
			if( runConstantCheck(c._dat) )
			{
				if( DMLScript.DEBUG )
					System.out.println("PARFOR: Possible output dependency detected via constant self-check: var '"+c._var+"'.");
				dep[0] = true;
				if( ABORT_ON_FIRST_DEPENDENCY )
					return;
			}
		}
		
		// check candidate against all statements
		for(StatementBlock sb : asb )
			for( Statement s : sb._statements )
			{
				sCount++; 
			
				if( s instanceof ForStatement && !(s instanceof ParForStatement) )
				{
					rCheckCandidates(c, cdt, ((ForStatement)s).getBody(), sCount, dep);
				}
				else if( s instanceof WhileStatement ) 
				{
					rCheckCandidates(c, cdt, ((WhileStatement)s).getBody(), sCount, dep);
				}				
				else if( s instanceof IfStatement ) 
				{
					rCheckCandidates(c, cdt, ((IfStatement)s).getIfBody(), sCount, dep);
					rCheckCandidates(c, cdt, ((IfStatement)s).getElseBody(), sCount, dep);
				}
				else if( s instanceof FunctionStatement ) 
				{
					rCheckCandidates(c, cdt, ((FunctionStatement)s).getBody(), sCount, dep);
				}
				else
				{
					//CHECK output dependencies
					Collection<DataIdentifier> datsUpdated = getDataIdentifiers(s, true);
					
					if( datsUpdated != null )
						for(DataIdentifier write : datsUpdated)	
						{ 
							String writeStr = write.getName();
							if( c._var.equals( writeStr )  ) 
							{
								DataIdentifier dat2 = write; 
		
								if( cdt == DataType.MATRIX ) 
								{
									if( c._dat != dat2 ) //omit self-check
									{
										if( runEqualsCheck(c._dat, dat2) )
										{
											//intra-iteration output dependencies (same index function) are OK
										}
										else if(runBanerjeeGCDTest( c._dat, dat2 ))
										{
											if( DMLScript.DEBUG )
												System.out.println("PARFOR: Possible output dependency detected via GCD/Banerjee: var '"+write+"'.");
											dep[0] = true;
											if( ABORT_ON_FIRST_DEPENDENCY )
												return;
										}
									}
								}
								else // at least one type UNKNOWN
								{
									//cannot infer type, need to exit (conservative approach)
									throw new LanguageException("PARFOR loop dependency analysis: cannot check for dependencies " +
															   "due to unknown datatype of var '"+c._var+"'.");
								}
							}
						}
					
					Collection<DataIdentifier> datsRead = getDataIdentifiers(s, false);
					
					//check data and anti dependencies
					if( datsRead != null )
						for(DataIdentifier read : datsRead)
						{ 
							String readStr = read.getName();
							
							if( c._var.equals( readStr )  ) 
							{
								DataIdentifier dat2 = read;
								DataType dat2dt = _vsParent.getVariables().get(readStr).getDataType(); //vs.getVariables().get(read).getDataType();
							
								if(    cdt == DataType.SCALAR 
									|| cdt == DataType.OBJECT
									|| dat2dt == DataType.SCALAR 
									|| dat2dt == DataType.OBJECT )  
								{
									//every write, read combination involving a scalar is a data dependency
									dep[1] = true;
									if( ABORT_ON_FIRST_DEPENDENCY )
										return;
									
									//if(!output) //no write before read in iteration body
									//data = true;
									
								}
								else if(   cdt == DataType.MATRIX 
										&& dat2dt == DataType.MATRIX  )
								{
									if( /*c._pos < sCount &&*/ runEqualsCheck(c._dat, dat2) )
									{
										//read after write on same index, and not constant (checked for output) 
										//is OK
									}
									else if( runBanerjeeGCDTest( c._dat, dat2 ) )
									{
										if( DMLScript.DEBUG )
											System.out.println("PARFOR: Possible data/anti dependency detected via GCD/Banerjee: var '"+read+"'.");
										dep[1] = true;
										dep[2] = true;
										if( ABORT_ON_FIRST_DEPENDENCY )
											return;
									}
								}
								else //if( c._dat.getDataType() == DataType.UNKNOWN )
								{
									//cannot infer type, need to exit (conservative approach)
									throw new LanguageException("PARFOR loop dependency analysis: cannot check for dependencies " +
															   "due to unknown datatype of var '"+c._var+"'.");
								}
							}
						}
				}
			}				
	}
	
	/**
	 * Get all target/source DataIdentifiers of the given statement.
	 * 
	 * @param s
	 * @param target 
	 * @return
	 */
	private Collection<DataIdentifier> getDataIdentifiers(Statement s, boolean target) 
	{
		Collection<DataIdentifier> ret = null;
		
		if( s instanceof AssignmentStatement )
		{
			AssignmentStatement s2 = (AssignmentStatement)s;
			if(target)
				ret = s2.getTargetList();
			else
				ret = rGetDataIdentifiers(s2.getSource());
		}
		else if (s instanceof FunctionStatement)
		{
			FunctionStatement s2 = (FunctionStatement)s;
			if(target)
				ret = s2.getOutputParams();
			else
				ret = s2.getInputParams();
		}
		else if (s instanceof MultiAssignmentStatement)
		{
			MultiAssignmentStatement s2 = (MultiAssignmentStatement)s;
			if(target)
				ret = s2.getTargetList();
			else
				ret = rGetDataIdentifiers(s2.getSource());
		}
		//potentially extend this list with other Statements if required
		//(e.g., IOStatement, PrintStatement, RandStatement)
		
		return ret;
	}

	private Collection<DataIdentifier> rGetDataIdentifiers(Expression e)
	{
		Collection<DataIdentifier> ret = new ArrayList<DataIdentifier>();
		
		if( e instanceof DataIdentifier && !(e instanceof FunctionCallIdentifier) )
		{
			ret.add( (DataIdentifier)e );
		}
		else if( e instanceof FunctionCallIdentifier )
		{
			FunctionCallIdentifier fci = (FunctionCallIdentifier)e;
			for( Expression ee : fci.getParamExpressions() )
				ret.addAll(rGetDataIdentifiers( ee ));
		}
		else if(e instanceof BinaryExpression)
		{
			BinaryExpression be = (BinaryExpression) e;
			ret.addAll( rGetDataIdentifiers(be.getLeft()) );
			ret.addAll( rGetDataIdentifiers(be.getRight()) );
		}
		else if(e instanceof BooleanExpression)
		{
			BooleanExpression be = (BooleanExpression) e;
			ret.addAll( rGetDataIdentifiers(be.getLeft()) );
			ret.addAll( rGetDataIdentifiers(be.getRight()) );
		}
		else if(e instanceof BuiltinFunctionExpression)
		{
			BuiltinFunctionExpression be = (BuiltinFunctionExpression) e;
			ret.addAll( rGetDataIdentifiers(be.getFirstExpr()) );
			ret.addAll( rGetDataIdentifiers(be.getSecondExpr()) );
			ret.addAll( rGetDataIdentifiers(be.getThirdExpr()) );
		}
		else if(e instanceof ParameterizedBuiltinFunctionExpression)
		{
			ParameterizedBuiltinFunctionExpression be = (ParameterizedBuiltinFunctionExpression) e;
			for( Expression ee : be.getVarParams().values() )
				ret.addAll( rGetDataIdentifiers(ee) );
		}
		else if(e instanceof RelationalExpression)
		{
			RelationalExpression re = (RelationalExpression) e;
			ret.addAll( rGetDataIdentifiers(re.getLeft()) );
			ret.addAll( rGetDataIdentifiers(re.getRight()) );
		}

		return ret;
	}
	/**
	 * Determines the lower/upper bounds of all nested for/parfor indexes.
	 * 
	 * @param sbs
	 * @return
	 * @throws LanguageException 
	 */
	private void rDetermineBounds( ArrayList<StatementBlock> sbs ) 
		throws LanguageException
	{
		// catch all known for/ parfor bounds 
		// (all unkown bounds are assumed to be +-infinty)
		
		for( StatementBlock sb : sbs )
			for( Statement s : sb._statements )
			{
				if( s instanceof ForStatement ) // for, parfor
				{
					ForStatement fs = (ForStatement)s;
					IterablePredicate ip = fs._predicate;
			
					//check for internal names
					if(   ip.getIterVar()._name.equals( INTERAL_FN_INDEX_ROW )
					   || ip.getIterVar()._name.equals( INTERAL_FN_INDEX_COL ))
					{
						throw new LanguageException(" The iteration variable must not use the " +
								"internal iteration variable name prefix '"+ip.getIterVar()._name+"'.");
					}
					
					long low = Integer.MIN_VALUE;
					long up = Integer.MAX_VALUE;
					long incr = -1;
					
					if( ip.getFromExpr()instanceof IntIdentifier)
						low = ((IntIdentifier)ip.getFromExpr()).getValue();
					if( ip.getToExpr()instanceof IntIdentifier)
						up = ((IntIdentifier)ip.getToExpr()).getValue();
					
					//NOTE: conservative approach: include all index variables (also from for)
					if( ip.getIncrementExpr() instanceof IntIdentifier )
						incr = ((IntIdentifier)ip.getIncrementExpr()).getValue();
					else
						throw new LanguageException("PARFOR loop dependency analysis: cannot check for dependencies " +
								                    "because increment expression '"+ip.getIncrementExpr().toString()+"' cannot be normalized.");
				
					_bounds._lower.put(ip.getIterVar()._name, low);
					_bounds._upper.put(ip.getIterVar()._name, up);
					_bounds._increment.put(ip.getIterVar()._name, incr);
					
					//recursive invocation
					if( fs._body!=null )
					{
						rDetermineBounds(fs._body);
					}
				}
			}
	}

	/**
	 * Runs a combination of GCD and Banerjee test for a two potentially conflicting
	 * data identifiers. See below for a detailed explanation.
	 * 
	 * NOTE: simply enumerating all combinations of iteration variable values and probing for
	 * duplicates is not applicable due to (1) arbitrary nested program blocks with potentially
	 * dynamic lower, upper, and increment expressions, and (2) therefore potentially large 
	 * overheads in the general case.
	 * 
	 * @param dat1
	 * @param dat2
	 * @return
	 * @throws LanguageException
	 */
	private boolean runBanerjeeGCDTest(DataIdentifier dat1, DataIdentifier dat2) 
		throws LanguageException 
	{
		/* The GCD (greatest common denominator) and the Banerjee test are two commonly used tests
		 * for determining loop-carried dependencies. Both rely on (1) linear index expressions of the
		 * form y = a + bx, where x is the loop index variable, and (2) conservative approaches that
		 * guarantee no false negatives (no missed dependencies) but possibly false positives. The GCD
		 * test probes for integer solutions without bounds, while the Banerjee test probes for real
		 * solutions with bounds. 
		 * 
		 * We use a combination of both:
		 * - the GCD test checks if dependencies are possible
		 * - the Banerjee test checks if those dependencies may arise within the given bounds
		 * 
		 * NOTES: 
		 * - #1 possible false positives may arise if there is a real solution within the bounds
		 * and an integer solution outside the bounds. This will lead to a detected dependencies
		 * although no integer solution within the bounds exists.
		 * - #2 for the sake of simplicity, we do not distinguish between anti and data dependencies,
		 * although possible in general
		 * - more advanced tests than GCD and Banerjee available (e.g., with symbolic checking for
		 *   non-linear functions) but this is a tradeoff between number of false positives and overhead
		 */
		
		if( DMLScript.DEBUG )
			System.out.println("PARFOR: runBanerjeeGCDCheck.");
		
		boolean ret = true; //anti or data dependency
		
		//Step 1: analyze index expressions and transform them into linear functions		
		LinearFunction f1 = getLinearFunction(dat1); 
		LinearFunction f2 = getLinearFunction(dat2);		
		forceConsistency(f1,f2);
		
		if( DMLScript.DEBUG )
		{
			System.out.println("PARFOR: f1: "+f1.toString());
			System.out.println("PARFOR: f2: "+f2.toString());
		}
			
		///////
		//Step 2: run GCD Test 
		///////		
		long lgcd = f1._b[0];
		for( int i=1; i<f1._b.length; i++ )
			lgcd = determineGCD( lgcd, f1._b[i] );
		for( int i=0; i<f2._b.length; i++ )
			lgcd = determineGCD( lgcd, f2._b[i] );
		
		if( (Math.abs(f1._a-f2._a) % lgcd) != 0 ) //if GCD divides the intercepts
		{
			//no integer solution exists -> no dependency
			ret = false;
		}	
		
		if( DMLScript.DEBUG )
			System.out.println("PARFOR: GCD result: "+ret);

		///////
		//Step 3: run Banerjee Test		
		///////
		if( ret ) //only if GCD found possible dependencies
		{
			long lintercept = f2._a - f1._a;
			
			//determining anti/data dependencies
			long lmax=0;
			long lmin=0;

			//min/max bound 
			int len = Math.max(f1._b.length, f2._b.length);
			for( int i=0; i<len; i++ ) 
			{
				String var=(f1._b.length>i) ? f1._vars[i] : f2._vars[i];
				
				long lower = _bounds._lower.get(var); //bounds equal for f1 and f2
				long upper = _bounds._upper.get(var);
				
				//max bound
				if( f1._b.length>i )
				{	
					if( f1._b[i]>0 ) lmax += f1._b[i]*upper;
					else             lmax += f1._b[i]*lower;								
				}
				if( f2._b.length>i )
				{
					if( f2._b[i]>0 ) lmax -= f2._b[i]*lower; 
					else             lmax -= f2._b[i]*upper; 
				}
				
				//min bound (unequal indexes)
				if( f1._b.length>i )
				{
					if( f1._b[i]>0 ) lmin += f1._b[i]*lower;
					else             lmin += f1._b[i]*upper;				
				}
				if( f2._b.length>i )
				{
					if( f2._b[i]>0 ) lmin -= f2._b[i]*upper; 
					else             lmin -= f2._b[i]*lower;
				}
			}			

			if( DMLScript.DEBUG )
			{
				System.out.println("PARFOR: Banerjee lintercept "+lintercept);
				System.out.println("PARFOR: Banerjee lmax "+lmax);
				System.out.println("PARFOR: Banerjee lmin "+lmin);
			}
			
			if( !(lmin <= lintercept && lintercept <= lmax) || lmin==lmax )
			{
				//dependency not within the bounds of the arrays
				ret = false;
			}
			
			if( DMLScript.DEBUG )
				System.out.println("PARFOR: Banerjee result: "+ret);
		}
	
		return ret;
	}

	/**
	 * Runs a constant check for a single data identifier (target of assignment). If constant, then every
	 * iteration writes to the same cell. 
	 * 
	 * @param dat1
	 * @return
	 * @throws LanguageException
	 */
	private boolean runConstantCheck(DataIdentifier dat1) 
		throws LanguageException 
	{
		if( DMLScript.DEBUG )
			System.out.println("PARFOR: runConstantCheck.");
		
		boolean ret = true; //data dependency to itself		
		LinearFunction f1 = getLinearFunction(dat1);

		if( DMLScript.DEBUG )
			System.out.println("PARFOR: f1: "+f1.toString());
		
		// no output dependency to itself if no index access will happen twice
		// hence we check for: (all surrounding indexes are used by f1 and all intercepts != 0 )
		boolean gcheck=true;
		for( String var : _bounds._lower.keySet() )
		{
			if(   var.startsWith(INTERAL_FN_INDEX_ROW) 
			   || var.startsWith(INTERAL_FN_INDEX_COL)) 
			{
				continue; //skip internal vars for range indexing 
			}
			
			boolean lcheck=false;
			for( int i=0; i<f1._vars.length; i++ )
				if( var.equals(f1._vars[i]) )
					if( f1._b[i] != 0 )
						lcheck = true;
			if( !lcheck )	
			{
				gcheck=false;
				break;
			}
		}
		
		if( gcheck ) // output dependencies impossible
			ret = false;
		
		return ret;
	}
	
	/**
	 * Runs an equality check for two data identifiers. If equal, there there are no
	 * inter-iteration (loop-carried) but only intra-iteration dependencies.
	 * 
	 * @param dat1
	 * @param dat2
	 * @return
	 * @throws LanguageException
	 */
	private boolean runEqualsCheck(DataIdentifier dat1, DataIdentifier dat2) 
		throws LanguageException 
	{
		if( DMLScript.DEBUG )
			System.out.println("PARFOR: runEqualsCheck.");
		
		boolean ret = true; //true if equal index functions
		LinearFunction f1 = getLinearFunction(dat1);
		LinearFunction f2 = getLinearFunction(dat2);
		forceConsistency(f1, f2);
		
		if( DMLScript.DEBUG )
		{
			System.out.println("PARFOR: f1: "+f1.toString());
			System.out.println("PARFOR: f2: "+f2.toString());
		}
		
		ret = f1.equals(f2);
		
		return ret;
	}
	
	/**
	 * This is the Euclid's algorithm for GCD (greatest common denominator), 
	 * required for the GCD test.
	 * 
	 * @param a
	 * @param b
	 * @return
	 */
	private long determineGCD(long a, long b) 
	{
	   if (b==0) 
	     return a;
	   else
	     return determineGCD(b, a % b);
	}

	/**
	 * Creates or reuses a linear function for a given data identifier, where identifiers with equal
	 * names and matrix subscripts result in exactly the same linear function.
	 * 
	 * @param dat
	 * @return
	 * @throws LanguageException
	 */
	private LinearFunction getLinearFunction(DataIdentifier dat)
		throws LanguageException
	{
		/* Notes:
		 * - Currently, this function supports 2dim matrix subscripts with arbitrary linear functions
		 *   however, this could be extended to d-dim if necessary
		 * - Trick for range indexing: introduce a pseudo index variable with lower and upper according to 
		 *   the index range (e.g., [1:4,...]) or matrix dimensionality (e.g., [:,...]). This allows us to
		 *   apply existing tests even for range indexing (multi-value instead of single-value functions)
		 */

		LinearFunction out = null;
		IndexedIdentifier idat = (IndexedIdentifier) dat;
		
		if( USE_FN_CACHE )
		{
			out = _fncache.get( getFunctionID(idat) );
			if( out != null ) return out; 
		}
		
		Expression sub1 = idat.getRowLowerBound();
		Expression sub2 = idat.getColLowerBound();
		
		//parse row expressions
		try
		{
			//loop index or constant (default case)
			if( idat.getRowLowerBound()!=null && idat.getRowUpperBound()!=null &&
					idat.getRowLowerBound() == idat.getRowUpperBound()         ) 
			{
				if( sub1 instanceof IntIdentifier )
					out = new LinearFunction((int)((IntIdentifier)sub1).getValue(), 0, null);
				else if( sub1 instanceof DataIdentifier )
					out = new LinearFunction(0, 1, ((DataIdentifier)sub1)._name);
				else
					out = rParseBinaryExpression((BinaryExpression)sub1);			
			}
			else //range indexing
			{
				Expression sub1a = sub1;
				Expression sub1b = idat.getRowUpperBound();
				
				String id = INTERAL_FN_INDEX_ROW;
				out = new LinearFunction(0, 1l, id);
				
				if( sub1a == null && sub1b == null ) //: operator
				{
					_bounds._lower.put(id, 1l);
					_bounds._upper.put(id, _vsParent.getVariable(idat._name).getDim1()); //row dim
					_bounds._increment.put(id, 1l);					
				}
				else if( sub1a instanceof IntIdentifier && sub1b instanceof IntIdentifier )
				{
					_bounds._lower.put(id, ((IntIdentifier)sub1a).getValue());
					_bounds._upper.put(id, ((IntIdentifier)sub1b).getValue()); 
					_bounds._increment.put(id, 1l);
				}
				else
				{
					out = null;
				}
			}
			
			//scale row function 'out' with col dimensionality	
			long colDim = _vsParent.getVariable(idat._name).getDim2();
			if( colDim > 0 )
			{
				out.scale( colDim ); 
			}
			else
			{
				//TODO: mark for deferred validation and evaluate on execute
				
				System.out.println("PARFOR: WARNING - matrix dimensionality unknown, cannot scale linear functions.");				
			}
		}
		catch(Exception ex)
		{
			if(DMLScript.DEBUG)
			{
				System.out.println("PARFOR: Unable to parse MATRIX subscript expression for '"+String.valueOf(sub1)+"'.");
				//ex.printStackTrace();
			}
			
			out = null; //let dependency analysis fail
		}
		
		//parse col expression and merge functions
		if( out!=null )
		{
			try
			{
				//loop index or constant (default case)
				if( idat.getColLowerBound()!=null && idat.getColUpperBound()!=null &&
						idat.getColLowerBound() == idat.getColUpperBound()             ) 
				{
					if( sub2 instanceof IntIdentifier )
						out.addConstant( ((IntIdentifier)sub2).getValue() );
					else if( sub2 instanceof DataIdentifier )
						out.addFunction( new LinearFunction(0, 1, ((DataIdentifier)sub2)._name) );
					else
						out.addFunction( rParseBinaryExpression((BinaryExpression)sub2) );	
				}
				else //range indexing
				{
					Expression sub2a = sub2;
					Expression sub2b = idat.getColUpperBound();
					
					String id = INTERAL_FN_INDEX_COL;
					out.addFunction( new LinearFunction(0, 1l, id) );
					
					if( sub2a == null && sub2b == null ) //: operator
					{
						_bounds._lower.put(id, 1l);
						_bounds._upper.put(id, _vsParent.getVariable(idat._name).getDim2()); //col dim
						_bounds._increment.put(id, 1l);					
					}
					else if( sub2a instanceof IntIdentifier && sub2b instanceof IntIdentifier )
					{
						_bounds._lower.put(id, ((IntIdentifier)sub2a).getValue());
						_bounds._upper.put(id, ((IntIdentifier)sub2b).getValue()); 
						_bounds._increment.put(id, 1l);
					}
					else
					{
						out = null;
					}
				}
			}
			catch(Exception ex)
			{
				if(DMLScript.DEBUG)
				{
					System.out.println("PARFOR: Unable to parse MATRIX subscript expression for '"+String.valueOf(sub2)+"'.");
					//ex.printStackTrace();
				}
				
				out = null; //let dependency analysis fail
			}
		}
		
		//post processing after creation
		if( out != null )
		{
			//cleanup and verify created function; raise exceptions if needed
			cleanupFunction(out);
			verifyFunction(out);
			
			// pseudo loop normalization of functions (incr=1, from=1 not necessary due to Banerjee) 
			// (precondition for GCD test)
			if( NORMALIZE ) 
			{
				int index=0;
				for( String var : out._vars )
				{
					long low  = _bounds._lower.get(var);
					long up   = _bounds._upper.get(var);
					long incr = _bounds._increment.get(var);
					if( incr < 0 || 1 < incr ) 
					{
						out.normalize(index,low,incr); // normalize linear functions
						_bounds._upper.put(var,(long)Math.ceil(((double)up)/incr)); // normalize upper bound
					}
					index++;
				}
			}
			
			//put into cache
			if( USE_FN_CACHE )
			{
				_fncache.put( getFunctionID(idat), out );
			}
		}
		
		return out;
	}
	
	/**
	 * Creates a functionID for a given data identifier (mainly used for caching purposes),
	 * where data identifiers with equal name and matrix subscripts results in equal
	 * functionIDs.
	 * 
	 * @param dat
	 * @return
	 */
	private String getFunctionID( IndexedIdentifier dat)
	{
		/* note: using dat.hashCode can be different for same functions, 
		 *       hence, we use a custom String ID
	     */
		
		IndexedIdentifier idat = (IndexedIdentifier) dat;		
		Expression ex1a = idat.getRowLowerBound();
		Expression ex1b = idat.getRowUpperBound();
		Expression ex2a = idat.getColLowerBound();
		Expression ex2b = idat.getColUpperBound();
		
		StringBuffer sb = new StringBuffer();
		sb.append(String.valueOf(ex1a));
		sb.append(',');
		sb.append(String.valueOf(ex1b));
		sb.append(',');
		sb.append(String.valueOf(ex2a));
		sb.append(',');
		sb.append(String.valueOf(ex2b));
		
		return sb.toString();
	}
	
	/**
	 * Removes all zero intercepts created by recursive computation.
	 * 
	 * @param f1
	 */
	private void cleanupFunction( LinearFunction f1 )
	{
		for( int i=0; i<f1._b.length; i++ )
		{
			if( f1._vars[i]==null )
			{
				f1.removeVar(i);
				i--; continue;
			}	
		}
	}
	
	/**
	 * Simply verification check of created linear functions, mainly used for
	 * robustness purposes.
	 * 
	 * @param f1
	 * @throws LanguageException
	 */
	private void verifyFunction(LinearFunction f1)
		throws LanguageException
	{
		//check for required form of linear functions
		if( f1 == null || f1._b.length != f1._vars.length )
		{
			if( DMLScript.DEBUG )
			{
				if( f1!=null ) 
					System.out.println("PARFOR: F1: "+f1.toString());
			}
			
			throw new LanguageException("PARFOR loop dependency analysis: " +
										"MATRIX subscripts are not in linear form (a0 + a1*x).");
		}
		
		//check all function variables to be index variables
		for( String var : f1._vars )
		{
			if( !_bounds._lower.containsKey(var) )
			{
				if( DMLScript.DEBUG )
				{
					System.out.println("PARFOR: not allowed variable in matrix subscript: "+var);
				}
				
				throw new LanguageException("PARFOR loop dependency analysis: " +
						                    "MATRIX subscripts use non-index variables."); 
			}
		}
	}
	
	/**
	 * Tries to obtain consistent linear functions by forcing the same variable ordering for
	 * efficient comparison: f2 is modified in a way that it matches the sequence of variables in f1.		
	 * 
	 * @param f1
	 * @param f2
	 */
	private void forceConsistency(LinearFunction f1, LinearFunction f2) 
	{
		boolean warn = false;

		for( int i=0; i<f1._b.length; i++ )
		{
			if( f2._b.length<(i+1) )
				break;
			
			if( !f1._vars[i].equals(f2._vars[i]) )
			{
				boolean exchange = false;
				//scan 
				for( int j=i+1; j<f2._b.length; j++ )
					if( f1._vars[i].equals(f2._vars[j]) )
					{
						//exchange
						long btmp = f2._b[i];
						String vartmp = f2._vars[i];
						f2._b[i] = f2._b[j];
						f2._vars[i] = f2._vars[j];
						f2._b[j] = btmp;
						f2._vars[j] = vartmp;
						exchange = true;
					}
				if( !exchange )
					warn = true;
			}
		}

		
		if( warn )
			System.out.println( "PARFOR: WARNING - index functions f1 and f2 cannot be made consistent." );
	}
	
	/**
	 * Recursively creates a linear function for a single BinaryExpression, where PLUS, MINUS, MULT
	 * are allowed as operators.
	 * 
	 * @param be
	 * @return
	 * @throws LanguageException
	 */
	private LinearFunction rParseBinaryExpression(BinaryExpression be) 
		throws LanguageException
	{
		LinearFunction ret = null;
		Expression l = be.getLeft();
		Expression r = be.getRight();
				
		if( be.getOpCode() == BinaryOp.PLUS )
		{			
			//parse binary expressions
			if( l instanceof BinaryExpression)
			{
				ret = rParseBinaryExpression((BinaryExpression) l);		
				ret.addConstant(((IntIdentifier) r).getValue());
			}
			else if (r instanceof BinaryExpression)
			{
				ret = rParseBinaryExpression((BinaryExpression) r);	
				ret.addConstant(((IntIdentifier) l).getValue());
			}
			else // atomic case
			{
				if( l instanceof IntIdentifier )
					ret = new LinearFunction(((IntIdentifier) l).getValue(),1,((DataIdentifier)r)._name);	
				else if(r instanceof IntIdentifier)
					ret = new LinearFunction(((IntIdentifier) r).getValue(),1,((DataIdentifier)l)._name);
				else
					return null; //let dependency analysis fail
			}
		}
		else if( be.getOpCode() == BinaryOp.MINUS ) 
		{			
			//parse binary expressions
			if( l instanceof BinaryExpression)
			{
				ret = rParseBinaryExpression((BinaryExpression) l);		
				//change to plus
				ret.addConstant(((IntIdentifier) r).getValue()*(-1));
			}
			else if (r instanceof BinaryExpression)
			{
				ret = rParseBinaryExpression((BinaryExpression) r);
				//change to plus
				ret._a*=(-1);
				for( int i=0; i<ret._b.length; i++ )
					ret._b[i]*=(-1);
				ret.addConstant(((IntIdentifier) l).getValue());
			}
			else // atomic case
			{
				//change everything to plus
				if( l instanceof IntIdentifier )
					ret = new LinearFunction(((IntIdentifier) l).getValue(),-1,((DataIdentifier)r)._name);	
				else if(r instanceof IntIdentifier)
					ret = new LinearFunction(((IntIdentifier) r).getValue()*(-1),1,((DataIdentifier)l)._name);
				else
					return null; //let dependency analysis fail
			}
		}
		else if( be.getOpCode() == BinaryOp.MULT )
		{
			//NOTE: no recursion for MULT expressions
			
			//atomic case
			if( l instanceof IntIdentifier )
				ret = new LinearFunction(0, (int)((IntIdentifier) l).getValue(),((DataIdentifier)r)._name);	
			else if(r instanceof IntIdentifier)
				ret = new LinearFunction(0, (int)((IntIdentifier) r).getValue(),((DataIdentifier)l)._name);
			else
				return null; //let dependency analysis fail
		}
		else
			return null; //let dependency analysis fail
			
		return ret;
	}

	/**
	 * Helper class for representing a single candidate.
	 *
	 */
	private class Candidate 
	{ 
		String _var;          // variable name
		//Integer _pos;         // statement position in parfor (can be used for distinguishing anti/data dep)
		DataIdentifier _dat;  // _var data identifier
	} 
	
	/**
	 * Helper class for representing all lower, upper bounds of (potentially nested)
	 * loop constructs. 
	 *
	 */
	private class Bounds
	{
		HashMap<String, Long> _lower     = new HashMap<String, Long>();
		HashMap<String, Long> _upper     = new HashMap<String, Long>();
		HashMap<String, Long> _increment = new HashMap<String, Long>();
	}
	
	/**
	 * Helper class for representing linear functions of matrix subscripts.
	 * The allowed form is 'y = a + b1x1 + ... = bnxn', which is required by
	 * the applied GCD and Banerjee tests.
	 *
	 */
	private class LinearFunction
	{		
		long     _a;     // intercept
		long[]   _b;     // slopes 
		String[] _vars; // b variable names
		
		LinearFunction( long a, long b, String name )
		{
			_a       = a;
			_b       = new long[1];
			_b[0]    = b;
			_vars    = new String[1];
			_vars[0] = name;
		}

		public void addConstant(long value)
		{
			_a += value;	
		}

		public void addFunction( LinearFunction f2)
		{
			_a = _a + f2._a;
			
			long[] tmpb = new long[_b.length+f2._b.length];
			System.arraycopy( _b,    0, tmpb, 0,         _b.length    );
			System.arraycopy( f2._b, 0, tmpb, _b.length, f2._b.length );
			_b = tmpb;
			
			String[] tmpvars = new String[_vars.length+f2._vars.length];
			System.arraycopy( _vars,    0, tmpvars, 0,            _vars.length    );
			System.arraycopy( f2._vars, 0, tmpvars, _vars.length, f2._vars.length );
			_vars = tmpvars;
		}

		public void removeVar( int i )
		{
			long[] tmpb = new long[_b.length-1];
			
			System.arraycopy( _b, 0, tmpb, 0, i );
			System.arraycopy( _b, i+1, tmpb, i, _b.length-i-1 );
			_b = tmpb;
			
			String[] tmpvars = new String[_vars.length-1];
			System.arraycopy( _vars, 0, tmpvars, 0, i );
			System.arraycopy( _vars, i+1, tmpvars, i, _vars.length-i-1 );
			_vars = tmpvars;			
		}
		
		public void scale( long scale )
		{				
			_a *= scale; //-1 because indexing starts at 1 
			
			for( int i=0; i<_b.length; i++ )
				if( _b[i] != 0 )
					_b[i] *= scale;
		}
		
		public void normalize(int index, long lower, long increment) 
		{
			_a -= (_b[index] * lower);
			_b[index] *= increment;
		}
		
		@Override
		public String toString()
		{
			StringBuffer sb = new StringBuffer();
			sb.append("(");
			sb.append(_a);
			sb.append(") + ");
			sb.append("(");			
			for( int i=0; i<_b.length; i++ )
			{
				if( i>0 )
					sb.append("+");
				sb.append("(");
				sb.append(_b[i]);
				sb.append(" * ");
				sb.append(_vars[i]);
				sb.append(")");
			}
			sb.append(")");
			
			return sb.toString();
		}
		
		@Override
		public boolean equals( Object o2 )
		{
			boolean ret = true;
			LinearFunction f2;
			
			if( o2 == null)
			{
				ret = false;
			}
			else
			{
				f2 = (LinearFunction)o2;
				ret &= ( _a == f2._a );
				ret &= ( _b.length == f2._b.length );
				
				if( ret )
				{
					for( int i=0; i<_b.length; i++ )
					{
						ret &= (_b[i] == f2._b[i] );
						ret &= (_vars[i].equals(f2._vars[i]));
					}
				}
			}
			
			return ret;
		}
	}
}
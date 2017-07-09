/*******************************************************************************
 * Copyright (c) 2015 EmbSysRegView
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     edubec - initial API and implementation
 *******************************************************************************/
package org.eclipse.cdt.embsysregview.views;

import java.math.BigInteger;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

//import org.eclipse.cdt.debug.core.cdi.ICDISession;
//import org.eclipse.cdt.debug.core.cdi.model.ICDITarget;
//import org.eclipse.cdt.debug.internal.core.model.CDebugElement;
//import org.eclipse.cdt.debug.internal.core.model.CDebugTarget;
import org.eclipse.cdt.dsf.mi.service.MIFormat;
import org.eclipse.cdt.dsf.concurrent.DataRequestMonitor;
import org.eclipse.cdt.dsf.concurrent.ImmediateExecutor;
import org.eclipse.cdt.dsf.concurrent.Query;
import org.eclipse.cdt.dsf.datamodel.IDMContext;
import org.eclipse.cdt.dsf.debug.service.command.ICommand;
import org.eclipse.cdt.dsf.gdb.internal.GdbPlugin;
import org.eclipse.cdt.dsf.gdb.launching.GdbLaunch;
import org.eclipse.cdt.dsf.gdb.service.command.IGDBControl;
import org.eclipse.cdt.dsf.mi.service.command.commands.CLICommand;
import org.eclipse.cdt.dsf.mi.service.command.output.MIDataWriteMemoryInfo;
import org.eclipse.cdt.dsf.mi.service.command.output.MIInfo;
import org.eclipse.cdt.dsf.service.DsfServicesTracker;
import org.eclipse.cdt.dsf.service.DsfSession;
import org.eclipse.cdt.dsf.ui.viewmodel.datamodel.IDMVMContext;
import org.eclipse.cdt.launch.AbstractCLaunchDelegate.CLaunch;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.debug.core.model.MemoryByte;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.cdt.dsf.mi.service.MIFormat;


public class GDBSessionTranslator {
	/**
	 * Get Session Object from current debug context
	 * @return Session Object or null on error
	 */
	static public Object getSession(){
		return getSession(DebugUITools.getDebugContext());
	}
	/**
	 * Get Session Object from debug context
	 * @return Session Object or null on error
	 */
	static public Object getSession(Object context){
		Object session = null;
		if(null != context){
			if(context instanceof DsfSession)
				session = context;
			if(context instanceof IProcess){
				//gets ILaunch out of processes (RuntimeProcess, GDBProcess)
				context = ((IProcess)context).getLaunch();
			}
			if (context instanceof GdbLaunch){
				//GdbLaunch -> DsfSession
				session = ((GdbLaunch)context).getSession();
			}else if (context instanceof IDMVMContext) {
				//DsfSession
				IDMContext dmc =  ((IDMVMContext)context).getDMContext();
				if(null == dmc) return null;
				session = DsfSession.getSession(dmc.getSessionId());
			}
		}
		if(null != session && isSessionTerminated(session)) return null; //do not return terminated sessions
		return session;
	}
	
	static public boolean isSessionTerminated(Object session){
		ILaunch[] launches = DebugPlugin.getDefault().getLaunchManager().getLaunches();
		for(int i = 0; i < launches.length; i++){
			ILaunch l = launches[i];
			if(session == getSession(l)){
				return l.isTerminated();
			}
		}
		return true;
	}
	
	/**
	 * Get Session Object out of ILaunch
	 * @param launch
	 * @return Session Object or null on error
	 */
	static public Object getSession(ILaunch launch){
		if(null != launch){
			if (launch instanceof GdbLaunch){
				//GdbLaunch -> DsfSession
				return ((GdbLaunch)launch).getSession();
			}
		}
		return null;
	}
	
	
	public static int maxWaitTimeInInMilliseconds = 5000; //5 seconds
	
	/**
	 * Write a value to memory address.
	 * @param session Debug session.
	 * @param address Destination memory address.
	 * @param value value to write.
	 * @return Return iByteCount on success, -1 on failure.
	 * @throws TimeoutException 
	 */
	static public int writeMemory(Object session, String address, String value, int iByteCount) throws TimeoutException {
		if(null == session) return -1;
		if (session instanceof DsfSession){
			return writeMemory((DsfSession) session, address, value, iByteCount, maxWaitTimeInInMilliseconds); 
        }
		return -1;
	}
	
	static public int writeMemory(String address, String value, int iByteCount) throws TimeoutException {
		return writeMemory(getSession(), address, value, iByteCount);
	}
	
	static public int writeMemory(Object session, long address, long value, int iByteCount) throws TimeoutException {
		return writeMemory(session, Long.toString(address), "0x" + Long.toHexString(value), iByteCount);
	}
	
	static public int writeMemory(long address, long value, int iByteCount) throws TimeoutException {
		return writeMemory(getSession(), address, value, iByteCount);
	}
	
	/**
	 * Read value from the target memory.
	 * @param address Source address to read from.
	 * @return The read value, or -1 on error.
	 * @throws TimeoutException 
	 */
	static public long readMemory(Object session, String address, int iByteCount) throws TimeoutException {
		if(null == session) return -1;
		if (session instanceof DsfSession){
            return readMemory((DsfSession) session, address, iByteCount, maxWaitTimeInInMilliseconds);
        }
		return -1;
	}
	
	static public long readMemory(String address, int iByteCount) throws TimeoutException {
		return readMemory(getSession(), address, iByteCount);
	}
	
	static public long readMemory(Object session, long address, int iByteCount) throws TimeoutException {
		return readMemory(session, Long.toString(address), iByteCount);
	}
	
	static public long readMemory(long address, int iByteCount) throws TimeoutException {
		return readMemory(getSession(), address, iByteCount);
	}
	
	/**
	 * @throws TimeoutException 
	 * @see GDbSessionTranslator#writeMemory(Object, String, String, int)
	 */
	public static int writeMemory(final DsfSession session, final String address, final String value, int iByteCount, int maximumTimeToWait) throws TimeoutException {
		if(!session.isActive()) return -1;
		int ret = -1;
		DsfServicesTracker tracker = new DsfServicesTracker(GdbPlugin.getBundleContext(), session.getId());
		final IGDBControl fGdb = tracker.getService(IGDBControl.class);
		if(null == fGdb) return -1;
		org.eclipse.cdt.dsf.mi.service.command.CommandFactory factory = fGdb.getCommandFactory();
		int format;
		if(value.contains("x")) {
			format = MIFormat.HEXADECIMAL;
		} else {
			format = MIFormat.DECIMAL;
		}
		final ICommand<MIDataWriteMemoryInfo> info_wm = factory.createMIDataWriteMemory(fGdb.getContext(), 0, address, format, iByteCount, value);
		Query<MIDataWriteMemoryInfo> query= new Query<MIDataWriteMemoryInfo>() {
			@Override
			protected void execute(final DataRequestMonitor<MIDataWriteMemoryInfo> rm)  {
				fGdb.queueCommand(info_wm, new DataRequestMonitor<MIDataWriteMemoryInfo>(fGdb.getExecutor(), null){
					@Override
					protected void handleCompleted(){
						rm.setData(getData());
						rm.done();
					}
				});
			}};
		
		ImmediateExecutor.getInstance().execute(query);
		MIDataWriteMemoryInfo data= null;
		try {
			// The Query.get() method is a synchronous call which blocks until the query completes.  
			data= query.get(maximumTimeToWait, TimeUnit.MILLISECONDS);
			if(data.isError()){
				data = null;
			}
			ret = (data != null)? iByteCount : (-1);

		} catch (InterruptedException exc) {
		} catch (ExecutionException exc) {
		} catch (TimeoutException e) {
			String message = "writeMemory - failed, ErrorMessage: waiting time of "+maximumTimeToWait+" ms passed";
			throw new TimeoutException(message);
		} finally{
			tracker.dispose();
		}
		return ret;
	}
	
	/**
	 * @throws TimeoutException 
	 * @see GDbSessionTranslator#readMemory(String)
	 */
	public static long readMemory(final DsfSession session, final String address, int iByteCount, int maximumTimeToWait) throws TimeoutException {
		if(!session.isActive()) return -1;
		long ret = -1;
		DsfServicesTracker tracker = new DsfServicesTracker(GdbPlugin.getBundleContext(), session.getId());

		final IGDBControl fGdb = tracker.getService(IGDBControl.class);
		if(null == fGdb) return -1;
		org.eclipse.cdt.dsf.mi.service.command.CommandFactory factory = fGdb.getCommandFactory();		
		final ICommand<org.eclipse.cdt.dsf.mi.service.command.output.MIDataReadMemoryInfo> info_rm = factory.createMIDataReadMemory(fGdb.getContext(), 0, address, MIFormat.HEXADECIMAL, 1, 1, iByteCount, null);
		
		Query<org.eclipse.cdt.dsf.mi.service.command.output.MIDataReadMemoryInfo> query= new Query<org.eclipse.cdt.dsf.mi.service.command.output.MIDataReadMemoryInfo>() {
			@Override
			protected void execute(final DataRequestMonitor<org.eclipse.cdt.dsf.mi.service.command.output.MIDataReadMemoryInfo> rm)  {
				fGdb.queueCommand(info_rm, new DataRequestMonitor<org.eclipse.cdt.dsf.mi.service.command.output.MIDataReadMemoryInfo>(fGdb.getExecutor(), null){
					@Override
					protected void handleCompleted(){
						rm.setData(getData());
						rm.done();
					}
				});
			}};
		
		ImmediateExecutor.getInstance().execute(query);
		org.eclipse.cdt.dsf.mi.service.command.output.MIDataReadMemoryInfo data= null;
		try {
			// The Query.get() method is a synchronous call which blocks until the query completes.  
			data= query.get(maximumTimeToWait, TimeUnit.MILLISECONDS);
			if(data.isError()){
				//System.out.println(data.getErrorMsg());
				data = null;
			}
			if(data != null){
				MIInfo info = postCLICommand(session, "show endian", maximumTimeToWait);
				if(info == null) return -1;
				boolean endianessknown = true;
				boolean isBigEndian = info.getMIOutput().toString().contains("big endian");
				boolean isLittleEndian = info.getMIOutput().toString().contains("little endian");
				if(!isBigEndian && !isLittleEndian || isBigEndian && isLittleEndian) endianessknown = false;

				
				MemoryByte[] bytes = data.getMIMemoryBlock(); 

				//TODO: Find better solution to create Long Variable
				if(endianessknown){
					byte[] arraybytes = new byte[bytes.length];
					if(!isBigEndian){
						for(int i = 0; i < bytes.length; i++){
							arraybytes[i] = bytes[bytes.length - 1 - i].getValue();
						}
					}else{
						for(int i = 0; i < bytes.length; i++){
							arraybytes[i] = bytes[i].getValue();
						}
					}
					BigInteger big = new BigInteger(arraybytes);
					//TODO assumes 32 Bit value
					big = Utils.makeUnsigned(big, Utils.Arch32Bit);
					ret = new Long(big.toString());


				}else{
					//System.out.println("Unknown endianess: "+info.getMIOutput().toString());
					ret = -1;
				}
			}

		} catch (InterruptedException exc) {
		} catch (ExecutionException exc) {
		} catch (TimeoutException e) {
			String message = "readMemory - failed, ErrorMessage: waiting time of "+maximumTimeToWait+" ms passed";
			throw new TimeoutException(message);
		} finally{
			tracker.dispose();
		}
		return ret;
		
	}
	
	/**
	 * @throws TimeoutException 
	 * @see CommandWrapperPlugin#postCLICommand(String, Object, String)
	 */
	public static MIInfo postCLICommand(DsfSession session, String cLICommand, int maximumTimeToWait) throws TimeoutException {
		if(!session.isActive()) throw new TimeoutException("Session is terminated");
		DsfServicesTracker tracker = new DsfServicesTracker(GdbPlugin.getBundleContext(), session.getId());
		final IGDBControl fGdb = tracker.getService(IGDBControl.class);
		if(null == fGdb) return null;
		CLICommand<MIInfo> info = new CLICommand<MIInfo>(fGdb.getContext(), cLICommand);
		
		return executeQuery(info, "postCLICommand", "cLICommand="+cLICommand, session, tracker, fGdb, maximumTimeToWait);

	}
	
	/**
	 * Executes a dsf command and waits synchronous for the result
	 * @param command to be executed
	 * @param functionName of the function that calls executeQuery (for log purposes)
	 * @param details of the function that calls executeQuery (for log purposes)
	 * @param session the DsfSession in wich the command is called
	 * @param requestMonitor created with the sessions executor
	 * @param fGdb the IGDBControl executing the command
	 * @return the MIInfo result
	 * @throws TimeoutException 
	 */
	static private MIInfo executeQuery(final ICommand<MIInfo> command, final String functionName, final String details, final DsfSession session, DsfServicesTracker tracker, final IGDBControl fGdb, int maximumTimeToWait) throws TimeoutException {
		Query<MIInfo> query= new Query<MIInfo>() {
			@Override
			protected void execute(final DataRequestMonitor<MIInfo> rm)  {
				fGdb.queueCommand(command, new DataRequestMonitor<MIInfo>(fGdb.getExecutor(), null){
					@Override
			    	protected void handleCompleted() {
						rm.setData(getData());
						rm.done();
					}
				});
			}};
		ImmediateExecutor.getInstance().execute(query);
		MIInfo data= null;
		try {
			// The Query.get() method is a synchronous call which blocks until the query completes.  
			data= query.get(maximumTimeToWait, TimeUnit.MILLISECONDS);
			if(data.isError()){
				return null;
				//throw new MIException(data.getErrorMsg());
			}

		} catch (InterruptedException exc) {
		} catch (ExecutionException exc) {
		} catch (TimeoutException e) {
			String message = functionName + " - failed, ErrorMessage: waiting time of "+maximumTimeToWait+" ms passed";
			throw new TimeoutException(message);
		} finally{
			tracker.dispose();
		}
		return data;
	}

}

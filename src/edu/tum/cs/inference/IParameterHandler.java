/*
 * Created on Nov 17, 2009
 *
 * TODO To change the template for this generated file go to
 * Window - Preferences - Java - Code Style - Code Templates
 */
package edu.tum.cs.inference;

public interface IParameterHandler {
	public void handleParams(java.util.Map<String,String> params) throws Exception;
	public ParameterHandler getParameterHandler();
}
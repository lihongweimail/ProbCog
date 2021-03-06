/*******************************************************************************
 * Copyright (C) 2009-2012 Dominik Jain.
 * 
 * This file is part of ProbCog.
 * 
 * ProbCog is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * ProbCog is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with ProbCog. If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package probcog.srl.mln.inference;

import java.util.ArrayList;

import probcog.logic.GroundAtom;
import probcog.logic.PossibleWorld;
import probcog.logic.sat.weighted.IMaxSAT;
import probcog.logic.sat.weighted.WeightedClausalKB;
import probcog.srl.mln.MarkovRandomField;

/**
 * MaxWalkSAT MAP inference for MLNs.
 * @author Dominik Jain
 */
public class MaxWalkSAT extends MAPInferenceAlgorithm {
	
	protected IMaxSAT sat;
	
	public MaxWalkSAT(MarkovRandomField mrf) throws Exception {
		this(mrf, probcog.logic.sat.weighted.MaxWalkSAT.class);
	}
	
	public MaxWalkSAT(MarkovRandomField mrf, Class<? extends IMaxSAT> mwsClass) throws Exception {
		super(mrf);
        WeightedClausalKB wckb = new WeightedClausalKB(mrf, false);
        PossibleWorld state = new PossibleWorld(mrf.getWorldVariables());
        sat = mwsClass.getConstructor(WeightedClausalKB.class, PossibleWorld.class, probcog.logic.WorldVariables.class, probcog.srl.Database.class).newInstance(wckb, state, mrf.getWorldVariables(), mrf.getDb());
        //sat = new edu.tum.cs.logic.sat.weighted.MaxWalkSAT(wckb, state, mrf.getWorldVariables(), mrf.getDb());
	}
	
	@Override
	public double getResult(GroundAtom ga) {
		return sat.getBestState().get(ga.index) ? 1.0 : 0.0;
	}

	@Override
	public ArrayList<InferenceResult> infer(Iterable<String> queries) throws Exception {
        sat.setMaxSteps(maxSteps);
        sat.run();	        
		return getResults(queries);
	}

	public PossibleWorld getSolution() {
		return sat.getBestState();
	}

	@Override
	public String getAlgorithmName() {
		return String.format("MAP:%s", sat.getAlgorithmName());
	}
}

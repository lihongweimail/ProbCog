/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package edu.tum.cs.logic.sat.weighted;

import java.io.PrintStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Random;
import java.util.Vector;

import edu.tum.cs.logic.Formula;
import edu.tum.cs.logic.GroundAtom;
import edu.tum.cs.logic.GroundLiteral;
import edu.tum.cs.logic.PossibleWorld;
import edu.tum.cs.logic.WorldVariables;
import edu.tum.cs.logic.WorldVariables.Block;
import edu.tum.cs.logic.sat.EvidenceHandler;
import edu.tum.cs.srl.Database;
import edu.tum.cs.util.StringTool;

/**
 * Implementation of the MaxWalkSAT algorithm with some custom changes/extensions
 * 
 * NOTE: This implementation most certainly has a few bugs. It is recommended to use MaxWalkSAT instead.
 * 
 * @author wernicke
 */
public class MaxWalkSATEx implements IMaxSAT {

    protected HashMap<Integer, Vector<Constraint>> bottlenecks;
    protected HashMap<Integer, Vector<Constraint>> GAOccurrences;
    protected PossibleWorld state;
    protected Vector<Constraint> unsatisfiedConstraints;
    protected Vector<Integer> nonEvidenceGndAtomIndices;
    protected Random rand;
    protected WorldVariables vars;
    protected HashMap<Integer, Boolean> evidence;
    protected EvidenceHandler evidenceHandler;
    protected HashMap<edu.tum.cs.logic.sat.weighted.WeightedClause, Formula> clFormula;
    protected HashMap<WeightedClause, Formula> cl2Formula;
    protected HashMap<Formula, Double> formula2weight;
    protected HashMap<Formula, HashSet<WeightedClause>> formula2clauses;
    protected HashMap<Formula, HashSet<WeightedClause>> formula2satClause;
    protected int countUnsCon;
    protected int lastMinStep;
    protected double unsSum;
    protected double unsSumBeta;
    public int step;
    protected final boolean verbose = false;
    protected PossibleWorld bestState;
    protected int greedyMoves;
    protected int SAMoves;
    protected int flips;
    double minSum;
    public static enum DeltaCostMethod { ClauseWeights, FormulaWeights, Mixed};
    protected DeltaCostMethod deltaCostCalcMethod = DeltaCostMethod.ClauseWeights; //(1 - Calculate always 1/Count of constraints; 2 - Calculate only if value of formula was changed (then complete weight of the formula); 3 - see 2, if no change were made then see 1)
    protected int maxSteps = 1000;
    /**
     * probability of a greedy move
     */
    protected double p = 0.8;

    /**
     *  Constructor to instantiate an object of MAPMaxWalkSAT
     * @param kb a knowledge base of weighted clauses, which can be instantiated by an Markov Random Field (MRF)
     * @param state a possible world containing all variables of the Markov Logic Network (MLN)
     * @param vars world variables of the MLN which is needed by the class
     * @param evidence an evidence database
     * @throws java.lang.Exception
     */
    public MaxWalkSATEx(WeightedClausalKB kb, PossibleWorld state, WorldVariables vars, Database evidence) throws Exception {

        this.state = state;
        this.vars = vars;
        flips = 0;
        this.unsatisfiedConstraints = new Vector<Constraint>();
        cl2Formula = new HashMap<WeightedClause, Formula>();
        formula2weight = new HashMap<Formula, Double>();
        formula2clauses = new HashMap<Formula, HashSet<WeightedClause>>();
        clFormula = kb.getClause2Formula();
        nonEvidenceGndAtomIndices = new Vector<Integer>();
        bottlenecks = new HashMap<Integer, Vector<Constraint>>();
        GAOccurrences = new HashMap<Integer, Vector<Constraint>>();
        formula2satClause = new HashMap<Formula, HashSet<WeightedClause>>();
        rand = new Random();

        evidenceHandler = new EvidenceHandler(vars, evidence.getEntries());
        this.evidence = evidenceHandler.getEvidence();
        evidenceHandler.setEvidenceInState(state);

        //initial list of constraints which are flipable by the algorithm (not set in evidence)
        for (int c = 0; c < vars.size(); c++) {
            if (!this.evidence.containsKey(c)) {
                nonEvidenceGndAtomIndices.add(c);
            }
        }

        // instantiate constraints
        for (edu.tum.cs.logic.sat.weighted.WeightedClause c : kb) {
            WeightedClause wcl = new WeightedClause(c.lits, c.isHard);
            Formula f = clFormula.get(c);
            //mapping of weighted clauses to the formula they are part of
            cl2Formula.put(wcl, f);
            //mapping of formulas to their weight
            formula2weight.put(f, c.weight);
            //mapping of formulas to containing weighted clauses
            if (formula2clauses.get(f) != null) {
                formula2clauses.get(f).add(wcl);
            } else {
                HashSet<WeightedClause> addWC = new HashSet<WeightedClause>();
                addWC.add(wcl);
                formula2clauses.put(f, addWC);
            }
        }
    }

    public void setMaxSteps(int steps) {
    	this.maxSteps = steps;
    }
    
    /**
     * Method adds a constraint into unsatisfiedConstraints (Vector<Constraint>)
     * @param c constraint which should be added
     */
    protected void addUnsatisfiedConstraint(Constraint c) {
        unsatisfiedConstraints.add(c);
    }

    /**
     * Method adds a groundatom into a vector of bottlenecks for a given constraint (bottlenecks(=groundatoms) are responsible for the negative change of value of a constraint)
     * @param ga groundatom to be added to the bottlenecks
     * @param c constraint for which the groundatom a is a bottleneck
     */
    protected void addBottleneck(GroundAtom ga, Constraint c) {
        Vector<Constraint> v = bottlenecks.get(ga.index);
        if (v == null) {
            v = new Vector<Constraint>();
            bottlenecks.put(ga.index, v);
        }
        v.add(c);
    }

    /**
     * Method adds a groundatom into a vector of containing groundatom of a constraint
     * @param ga  groundatom to be added to the occurence of a constraint
     * @param c constraint in which the groundatom occurs
     */
    protected void addGAOccurrence(GroundAtom ga, Constraint c) {
        Vector<Constraint> v = GAOccurrences.get(ga.index);
        if (v == null) {
            v = new Vector<Constraint>();
            GAOccurrences.put(ga.index, v);
        }
        v.add(c);
    }

    /**
     * Initial calculation of the sum of weights of unsatisfied formulas and count of unsatisfied hard constraints
     */
    protected void unsatisfiedSum() {
        HashSet<Formula> unsForm = new HashSet<Formula>();
        double sum = 0;
        countUnsCon = 0;
        for (Constraint wcl : unsatisfiedConstraints) {
            if (wcl.isHard()) {
                countUnsCon += 1;
            }
            Formula f = cl2Formula.get(wcl);
            unsForm.add(f);
        }
        for (Formula f : unsForm) {
            sum += formula2weight.get(f);
        }
        unsSum = sum;
    }

    /**
     * Initial mapping of formulas to HashSet of containing satisfied clauses
     */
    protected void initFormulaState() {
        for (WeightedClause wcl : cl2Formula.keySet()) {
            Formula parent = cl2Formula.get(wcl);
            HashSet<WeightedClause> wclSet = formula2satClause.get(parent);
            if (wclSet == null) {
                wclSet = new HashSet<WeightedClause>();
                formula2satClause.put(parent, wclSet);
            }
            if (!unsatisfiedConstraints.contains(wcl)) {
                wclSet.add(wcl);
            }
        }
    }

    /**
     * Method starts and controls the activities of the algorithm. In this case there will be executed a walkSatMove (greedyMove) with a property of 95% else the SAMove (random Move) will be executed.
     */
    public void run() {
        // init
        bottlenecks.clear();
        unsatisfiedConstraints.clear();
        setState();
        minSum = Integer.MAX_VALUE;
        step = 1;
        lastMinStep = 0;
        double diffSum = 0;
        int minSteps = 0;

        // initialize trueOnes and bottlenecks of constraints
        for (Constraint c : cl2Formula.keySet()) {
            c.initState();
        }

        // initialize mapping of formulas to containing satisfied clauses
        initFormulaState();

        // initial calculation of the unsatisfied sum of weights
        unsatisfiedSum();

        // run of the algorithm until condition of termination is reached
        bestState = state.clone();
        while (step <= maxSteps && unsatisfiedConstraints.size() > 0) {
            // choose between walkSATMove (greedy flip) and SAMove(random flip)
            String move;
            if (rand.nextDouble() < p) {
                greedyMove();
                move = "greedy";                
            } 
            else {
                randomMove();
                move = "random";
            }
            
            // calculation of the difference between actually found (unsSum) and globally found minimal unsatisfied sum (minSum) -> acually unused
            diffSum = unsSum - minSum;
            boolean newBest = false;
            // if there is another new minimal unsatisfied value
            if (unsSum <= minSum) {            	
                if (unsSum < minSum) {
                	newBest = true;
                    // saves new minimum, resets the steps which shows how often the algorithm hits the minimum (minSteps)
                    minSum = unsSum;
                    minSteps = 0;
                    // saves current best state
                    bestState = state.clone();
                    // count of step in which the new minimum where found is saved (lastMinStep)
                    lastMinStep = step;
                }
                // if the actual minimum is hit again minSteps where set +1
                if (unsSum == minSum)
                    minSteps++;
            }

            // print progress   
            boolean printStepCounter = false;
            boolean printProgress = newBest || step % 100 == 0;            
            if(printProgress) {
            	System.out.printf("  step %d: %s move, %d hard constraints unsatisfied, sum of unsatisfied weights: %f, best: %f  %s\n", step, move, countUnsCon, unsSum, minSum, newBest ? "[NEW BEST]" : "");
            }	
            else if(printStepCounter)
            	System.out.printf("  step %d\r", step);
            
            step++;
        }
    }

    /**
     * Returns the actual state of the algorithm
     * @return PossibleWorld (actual state)
     */
    public PossibleWorld getState() {
        return state;
    }

    /**
     * prints the best state found by the algorithm
     */
    public void printBestState(PrintStream fr) {
    	boolean[] s = bestState.getState();
        for (int c = 0; c < s.length; c++) {
            // for every variable prints out the value -> not => "!" in front of the groundatom
            String temp = "";
            if (!s[c]) {
                temp = "!";
            }
            // prints out every groundatom
            fr.println(temp += vars.get(c).toString());
        }
        fr.println("Unsatisfied Sum: " + minSum);
    }

    /**
     * sets the initial state randomly 
     */
    protected void setState() {
        evidenceHandler.setRandomState(state);
    }

    protected void greedyMove() {
    	//greedyMoveFormulaBased();
    	greedyMoveClauseBased();
    }
    
    /**
     * this is the standard greedy move as defined in the paper
     */
    protected void greedyMoveClauseBased() {
    	Constraint c = randomlyChosen();
    	Vector<?> v = c.greedySatisfy();
        for (Object o : v) {
            if (o instanceof GroundAtom) {
                flipGndAtom((GroundAtom) o);
            }
        }
    }

    /**
     * wernicke's old implementation, which makes some problematic assumptions (see below)
     */
    protected void greedyMoveFormulaBased() {
        // chooses randomly a unsatisfied constraint
    	Constraint c = randomlyChosen();
        Vector<Object> bestGAinFormula = new Vector<Object>();
        double formulaDelta = 0;
        // get the formula from which the clause originated
        Formula parent = cl2Formula.get(c);
        do {
        	// check all the clauses of the parent formula
            for (WeightedClause con : formula2clauses.get(parent)) {
                if(unsatisfiedConstraints.contains(con)) {
                    // gets all unsatisfied constraints of the formula and calculates the best groundatom to flip (related to the deltacosts) -> for details see greedySat
                    bestGAinFormula.addAll(con.greedySatisfy());
                }
            }
            for (Object o : bestGAinFormula) {
                if (o instanceof Double) {
                    // calculates deltacosts of the whole formula
                    formulaDelta += ((Double) o).doubleValue();
                }
            }
            if (formulaDelta < 0) {
                // if the formula enhances the state, flip all groundatoms
                for (Object o : bestGAinFormula) {
                    if (o instanceof GroundAtom) {
                        flipGndAtom((GroundAtom) o);
                    }
                }
            } 
            else {
                break;
            }
        } while(!parent.isTrue(state)); // TODO there is a bug here, this can be an infinite loop
    }

    /**
     * Randomly chooses and flips a possible ground atom
     * 
     * TODO this implementation is not faithful to the original algorithm: It flips any ground atom rather than one in an unsatisfied clause
     */
    protected void randomMove() {
        boolean done = false;
        // ensure that one flip will be done
        while (!done) {
            // randomly pick a ground atom to flip (only from valid constraints which were not set in evidence)
            int idxGA = nonEvidenceGndAtomIndices.get(rand.nextInt(nonEvidenceGndAtomIndices.size()));
            GroundAtom gndAtom = vars.get(idxGA), gndAtom2 = null;
            // if it's in a block, must choose a second to flip
            Block block = vars.getBlock(gndAtom.index);
            if (block != null) {
                GroundAtom trueOne = block.getTrueOne(state);
                // if we are flipping the true one, pick the second at random among the others
                if (gndAtom == trueOne) {
                    Vector<GroundAtom> others = new Vector<GroundAtom>();
                    for (GroundAtom ga : block) {
                        if (ga != trueOne && !evidence.containsKey(ga.index)) {
                            others.add(ga);
                        }
                    }
                    if (others.isEmpty()) {
                        continue;
                    }
                    // randomly choose the second one to flip
                    gndAtom2 = others.get(rand.nextInt(others.size()));
                } else { // second to flip must be true one
                    gndAtom2 = trueOne;
                }
            }
            // flip the groundatom(s)
            flipGndAtom(gndAtom);
            if (gndAtom2 != null) {
                flipGndAtom(gndAtom2);
            }
            done = true;
        }
    }

    /**
     * Chooses the "best" groundatom among the given candidates (in relation to the overall unsatisfied sum)
     * @param candidates a collection of groundatoms from which one should be flipped
     * @return returns a vector of one or two (in case of a block) groundatoms in combination with a double value as representation of the effects to the state
     */
    @SuppressWarnings("empty-statement")
    protected Vector<Object> pickAndFlipVar(Collection<GroundAtom> candidates) {
        GroundAtom bestGA = null, bestGASecond = null;
        double bestDelta = Integer.MAX_VALUE;

        for (GroundAtom gndAtom : candidates) {
            // calculate deltacost for each groundatom
            double delta = 0;
            // deltacost calculation methods are described in line 61
            switch (deltaCostCalcMethod) {
                case ClauseWeights:
                    delta = deltaCost(gndAtom);
                    break;
                case FormulaWeights:
                    delta = deltaCostFormula(gndAtom);
                    break;
                case Mixed:
                    delta = deltaCostConAndForm(gndAtom);
            }
            // if the atom is in a block, we must consider the cost of flipping the second atom
            Block block = vars.getBlock(gndAtom.index);
            GroundAtom secondGA = null;
            if (block != null) {
                GroundAtom trueOne = block.getTrueOne(state);
                double delta2 = Integer.MAX_VALUE;
                if (gndAtom != trueOne) {
                    // the second one to flip must be the true one
                    secondGA = trueOne;
                    delta2 = deltaCost(secondGA);
                } else { // as the second flip any one of the others
                    for (GroundAtom ga2 : block) {
                        if (ga2 == gndAtom) {
                            continue;
                        }
                        double d = 0;
                        // deltacost calculation methods are described in line 61
                        switch (deltaCostCalcMethod) {
                            case ClauseWeights:
                                d = deltaCost(ga2);
                                break;
                            case FormulaWeights:
                                d = deltaCostFormula(ga2);
                                break;
                            case Mixed:
                                d = deltaCostConAndForm(ga2);
                        }
                        // if the deltacosts enhances the state we found a better one to flip among the block
                        if (d < delta2) {
                            delta2 = d;
                            secondGA = ga2;
                        }
                    }
                }
                // add the deltacostrs of both best groundatoms
                delta += delta2;
            }
            // check whether the candidate is better
            boolean newBest = false;
            if (delta < bestDelta) {
                // if the deltacosts enhances the state we found a new best candidate
                newBest = true;
            } else if (delta == bestDelta && rand.nextInt(2) == 1) {
                newBest = true;
            }
            if (newBest) {
                bestGA = gndAtom;
                bestGASecond = secondGA;
                bestDelta = delta;
            }
        }
        // return a vector with the best groundatom(s) and the double value representing the change in the actual state
        // TODO this is bad style and should be changed
        Vector<Object> sol = new Vector();
        sol.add(bestGA);
        sol.add(bestGASecond);
        sol.add(bestDelta);
        return sol;
    }

    /**
     * Method flips the given groundatom and refreshs the unsatisfied sum, the state of the constraint and the satisfied clauses of the according formula
     * @param gndAtom groundatom to flip
     */
    protected void flipGndAtom(GroundAtom gndAtom) {
        // modify state
        boolean value = state.isTrue(gndAtom);
        state.set(gndAtom, !value);

        // the constraints where the literal was a bottleneck are now unsatisfied
        Vector<Constraint> bn = this.bottlenecks.get(gndAtom.index);
        if (bn != null) {
            for (Constraint wcl : bn) {
                Formula parent = cl2Formula.get((WeightedClause) wcl);
                int satConFormula = formula2satClause.get(parent).size();
                // if the according formula was satisfied (count of satisfied clauses is equal to the count of all clauses of the formula), it is now unsatisfied and the weight of the formula is added to the overall unsatisfied sum
                if (satConFormula == formula2clauses.get(parent).size()) {
                    unsSum += formula2weight.get(parent);                    
                }
                if(wcl.isHard())
                	this.countUnsCon++;
                // the clause is removed from the set of satisfied clauses of the formula
                formula2satClause.get(parent).remove(wcl);
            }
            // all constraints of the bottleneck are now unsatisfied
            this.unsatisfiedConstraints.addAll(bn);
            bn.clear();
        }
        // other processes are handled by the constraints themselves
        Vector<Constraint> occ = this.GAOccurrences.get(gndAtom.index);
        if (occ != null) {
            for (Constraint c : occ) {
                c.handleFlip(gndAtom);
            }
        }
    }

    /**
     * Calculation of the deltacosts per groundatom depending on the weight of the formula devided by its count of constraints.
     * Returns a double value.
     * @param gndAtom groundatom to calculate
     * @return returns a double value of added weights
     */
    protected double deltaCost(GroundAtom gndAtom) {
        double delta = 0;
        // consider newly unsatisfied constraints (positive because calculation of the unsatified sum)
        Vector<Constraint> bn = this.bottlenecks.get(gndAtom.index);
        if (bn != null) {
            for (Constraint con : bn) {
                delta += con.getDelta();
            }
        }
        // consider newly satisfied constraints (negative)
        Vector<Constraint> v = this.GAOccurrences.get(gndAtom.index);
        if(v != null)
	        for (Constraint c : v) {
	            if (c.flipSatisfies(gndAtom)) {
	                delta -= c.getDelta();
	            }
	        }
        //System.out.println("GA :" + gndAtom.toString() + " with weight " + delta);
        return delta;
    }

    /**
     * Calcualtion of deltacosts per groundatom. Only if the value of the complete formula would be changed through flipping.
     * the groundatom the complete weight of the formula is the deltacost of the groundatom. Returns a double value.
     * @param gndAtom groundatom to calculate
     * @return returns a double value of added weights
     */
    protected double deltaCostFormula(GroundAtom gndAtom) {
        double delta = 0;
        //consider newly unsatisfied constraints / formulas (positive because of calculation of the unsatisfied sum)
        Vector<Constraint> bn = this.bottlenecks.get(gndAtom.index);
        if (bn != null) {
            HashSet<Formula> checkedFormulas = new HashSet<Formula>();
            for (Constraint con : bn) {
                //check whether the weight of the formula was still added through calculation of other constraints
                if (!checkedFormulas.contains(cl2Formula.get(con))) {
                    delta += con.getDeltaFormula(false);
                    checkedFormulas.add(cl2Formula.get(con));
                }
            }
        }
        //consider newly satisfied constraints / formulas (negative)
        for (Constraint c : this.GAOccurrences.get(gndAtom.index)) {
            if (c.flipSatisfies(gndAtom)) {
                delta -= c.getDeltaFormula(true);
            }
        }
        return delta;
    }

    /**
     * Calculation of deltacosts per groundatom. If the value of the formula would changed through flipping the groundatom then the
     * complete weight are the deltacosts else the weight is devided by the count of the constraints of the formula and returned as
     * the deltacosts. Returns a double value.
     * @param gndAtom groundatom to calculate
     * @return returns a double value of added weights
     */
    protected double deltaCostConAndForm(GroundAtom gndAtom) {
        //calculating the deltacosts depending on a change of a value of a formula
        double delta = deltaCostFormula(gndAtom);
        //if no costs were considered calculate the costs on basis of the constraints
        if (delta == 0) {
            delta += deltaCost(gndAtom);
        }
        return delta;
    }

    /**
     * Method randomly chooses a constraint among the unsatisfied constraints and returns it
     * @return randomly chosen unsatisfied constraint
     */
    protected Constraint randomlyChosen() {
        return unsatisfiedConstraints.get(rand.nextInt(unsatisfiedConstraints.size()));
    }

    /**
     * Method prints all unsatisfied constraints in combination with an according formula and afterwards the unsatisfied sum on the console
     */
    public void printunsCons() {
        for (Constraint c : unsatisfiedConstraints) {
            System.out.println("F: " + cl2Formula.get(c));
            System.out.println("unsCon: " + c.toString());
        }
        System.out.println("summe: " + unsSum);
    }

    /**
     * Method returns an actual state of the algorithm in form of a boolean[].
     * @return boolean[] (part of a possible world)
     */
    public PossibleWorld getBestState() {
        return bestState;
    }

    /**
     * Method returns an actual count of steps
     * @return count of steps as integer value
     */
    public int getStep() {
        return step;
    }
    
    /**
     * sets the probability of a greedy move
     * @param p
     */
    public void setP(double p) {
    	this.p = p;
    }
    
    /**
     * gets the probability of a greedy move
     * @return parameter p
     */
    public double getP() {
    	return p;
    }
    
    /**
     * With this method one can set the method the daltacosts of a goriundatom are calculated. Possible values are:
     * 1 - Calculation of deltacosts: Always 1/Count of constraints of the according formula
     * 2 - Calculation of deltacosts: Only if value of formula was changed (then complete weight of the formula are the deltacosts of the groundatom)
     * 3 - see 2, if no change of the value of the according formula was made then see 1
     * @param deltaCostCalcMethod 1, 2 or 3 (see above for details)
     */
    public void setDeltaCostCalcMethod(DeltaCostMethod method) {
        this.deltaCostCalcMethod = method;
    }

    protected abstract class Constraint {

        public abstract Vector<Object> greedySatisfy();

        public abstract boolean flipSatisfies(GroundAtom gndAtom);

        public abstract void handleFlip(GroundAtom gndAtom);

        public abstract void initState();

        public abstract double getDelta();

        public abstract boolean isHard();

        public abstract double getDeltaFormula(boolean trueFlip);

        public abstract Vector<GroundAtom> getGAsOfConstraint();
    }

    /**
     * A class used by MaxWalkSAT to administrate the weighted clauses of the Markov Logic Network.
     */
    protected class WeightedClause extends Constraint {

        protected GroundLiteral[] lits;
        protected Vector<GroundAtom> gndAtoms;
        protected HashSet<GroundAtom> trueOnes;
        protected double weight;
        public boolean hard;

        /**
         * Constructor to instantiate an weighted clause.
         * @param lits Groundliterals contained by the weighted clause.
         * @param hard Represents whether the clause is part of a hard formula, so it must be satisfied
         */
        public WeightedClause(GroundLiteral[] lits, boolean hard) {
            this.lits = lits;
            this.hard = hard;

            // collect ground atom occurrences
            gndAtoms = new Vector<GroundAtom>();
            trueOnes = new HashSet<GroundAtom>();
            for (GroundLiteral lit : lits) {
                GroundAtom gndAtom = lit.gndAtom;
                gndAtoms.add(gndAtom);
                addGAOccurrence(gndAtom, this);
            }
        }

        /**
         * Method calls the method pickAndFlipVar of MaxWaltSAT-class with the containing groundatoms as candidates (see there for details).
         * @return returns the best groundatom(s) to satisfy the clause with related added weights
         */
        @Override
        public Vector<Object> greedySatisfy() {
            return (pickAndFlipVar(gndAtoms));
        }

        /**
         * Method returns if a flip of the groundatom would satisfies the weighted clause. Else it is still satisfied.
         * @param gndAtom groundatom to be checked
         * @return boolean value, true if the groundatom satisfies the clause, false if the clause ist still satisfied
         */
        @Override
        public boolean flipSatisfies(GroundAtom gndAtom) {
            return trueOnes.size() == 0;
        }

        /**
         * Method refreshs all states of the relating Objects (trueOnes, unsatisfiedConstraints, formula2satClause, unsatisfied sum and bottlenecks).
         * @param gndAtom fliped groundatom
         */
        @Override
        public void handleFlip(GroundAtom gndAtom) {
            int numTrueLits = trueOnes.size();
            Formula parent = cl2Formula.get(this);
            // the lit was true and is now false, remove it from the clause's list of true lits
            if (trueOnes.contains(gndAtom)) {
                trueOnes.remove(gndAtom);
                numTrueLits--;
                // if no more true lits are left, the clause is now unsatisfied; this is handled in flipGndAtom            
            } 
            else { // the lit was false and is now true, add it to the clause's list of true lits
                if (numTrueLits == 0) { // if the clause was previously unsatisfied, it is now satisfied
                    // remove the clause from unsatisfied constraints, add it to the set or satisfied cluases of the according formula
                    unsatisfiedConstraints.remove(this);
                    formula2satClause.get(parent).add(this);
                    // if the according formula is now satisfied subtract it's weight from the unsatisfied sum
                    if(formula2satClause.get(parent).size() == formula2clauses.get(parent).size()) {
                        unsSum -= formula2weight.get(parent);
                    }                
                    if(hard)
                    	countUnsCon--;
                } 
                else if (numTrueLits == 1) { // we are adding a second true lit, so the first one is no longer a bottleneck of this clause
                    bottlenecks.get(trueOnes.iterator().next().index).remove(this);
                }
                trueOnes.add(gndAtom);
                numTrueLits++;
            }
            // if there's only one true lit left in the clause it'a bottleneck of it
            if (numTrueLits == 1) {
                addBottleneck(trueOnes.iterator().next(), this);
            }
        }

        /**
         * Method returns a string-representation of the weighted clause
         * @return string-representation of the weighted clause
         */
        @Override
        public String toString() {
            return StringTool.join(" v ", lits);
        }

        /**
         * Sets the initial state of the weighted clause (trueOnes, bottelnecks and if necessary unsatisfied constraints).
         */
        @Override
        public void initState() {
            trueOnes.clear();
            // find out which lits are true
            for (GroundLiteral lit : lits) {
                if (lit.isTrue(state)) {
                    trueOnes.add(lit.gndAtom);
                }
            }
            // if there are no true ones, this constraint is unsatisfied
            if (trueOnes.size() == 0) {
                addUnsatisfiedConstraint(this);
            } // if there is exactly one true literal, it is a bottleneck
            else if (trueOnes.size() == 1) {
                addBottleneck(trueOnes.iterator().next(), this);
            }
        }

        /**
         * Method returns if a constraint is part of a hard formula (must be satisfied)
         * @return true, if constraint is part of a hard formula, else not
         */
        @Override
        public boolean isHard() {
            return hard;
        }

        /**
         * Gets the delta cost of satisfying the clause as weight/N, 
         * where N is the number of clauses in the formula the clause originated from
         * @return delta costs of the clause as double value
         */
        @Override
        public double getDelta() {
            double delta = 0;
            Formula relatedFormula = cl2Formula.get(this);
            //sum up of the unsatisfied formulas flipping the groundatom divided by total count of clauses of the formula
            delta += formula2weight.get(relatedFormula) / formula2clauses.get(relatedFormula).size();
            return delta;
        }

        /**
         * Method returns the weight of the according formula as the weight of the clause if it's flip changes the value of the formula (negative if satisfied, positive if changed to unsatisfied)
         * @param trueFlip boolean value: true, if the flip changes the value of the clause true, else false
         * @return deltacosts of the clause as double value
         */
        @Override
        public double getDeltaFormula(boolean trueFlip) {
            double delta = 0;
            Formula relatedFormula = cl2Formula.get(this);
            if (trueFlip) {
                if (formula2clauses.get(relatedFormula).size() - formula2satClause.get(relatedFormula).size() == 1) {
                    delta += formula2weight.get(relatedFormula);
                }
            } else {
                if (formula2clauses.get(relatedFormula).size() == formula2satClause.get(relatedFormula).size()) {  //check whether the formula is true
                    delta += formula2weight.get(relatedFormula);
                }
            }
            return delta;
        }

        /**
         * Methot returns all groundatoms of the according constraint.
         * @return containing groundatoms
         */
        @Override
        public Vector<GroundAtom> getGAsOfConstraint() {
            return gndAtoms;
        }
    }

	@Override
	public String getAlgorithmName() {
		return String.format("%s[p=%f]", this.getClass().getSimpleName(), p);
	}
}

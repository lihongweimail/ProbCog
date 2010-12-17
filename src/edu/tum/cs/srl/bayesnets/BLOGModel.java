package edu.tum.cs.srl.bayesnets;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import edu.ksu.cis.bnj.ver3.core.BeliefNode;
import edu.ksu.cis.bnj.ver3.core.CPF;
import edu.ksu.cis.bnj.ver3.core.Discrete;
import edu.ksu.cis.bnj.ver3.core.values.ValueDouble;
import edu.tum.cs.bayesnets.core.BeliefNetworkEx;
import edu.tum.cs.srl.Database;
import edu.tum.cs.srl.RelationKey;
import edu.tum.cs.srl.Signature;
import edu.tum.cs.srl.bayesnets.learning.CPTLearner;
import edu.tum.cs.srl.bayesnets.learning.DomainLearner;
import edu.tum.cs.srl.taxonomy.Concept;
import edu.tum.cs.srl.taxonomy.Taxonomy;
import edu.tum.cs.util.FileUtil;
import edu.tum.cs.util.StringTool;

public class BLOGModel extends RelationalBeliefNetwork {

	/**
	 * constructs a BLOG model by obtaining the node data from a fragment
	 * network and declarations from one or more BLOG files.
	 * 
	 * @param blogFiles
	 * @param networkFile
	 *            a Bayesian network file
	 * @throws Exception
	 */
	public BLOGModel(String[] blogFiles, String networkFile) throws Exception {
		super(networkFile);

		// read the blog files
		String blog = readBlogContent(blogFiles);

		// remove comments
		Pattern comments = Pattern.compile("//.*?$|/\\*.*?\\*/",
				Pattern.MULTILINE | Pattern.DOTALL);
		Matcher matcher = comments.matcher(blog);
		blog = matcher.replaceAll("");

		// read line by line
		String[] lines = blog.split("\n");
		for (String line : lines) {
			line = line.trim();
			if (line.length() == 0)
				continue;
			if (!readDeclaration(line))
				if (!line.contains("~"))
					throw new Exception("Could not interpret the line '" + line
							+ "'");
		}

		checkSignatures();
	}

	protected boolean readDeclaration(String line) throws Exception {
		// function signature
		// TODO: logical Boolean required - split this into random / logical w/o
		// Boolean
		if (line.startsWith("random") || line.startsWith("logical")) {
			Pattern pat = Pattern.compile(
					"(random|logical)\\s+(\\w+)\\s+(\\w+)\\s*\\((.*)\\)\\s*;?",
					Pattern.CASE_INSENSITIVE);
			Matcher matcher = pat.matcher(line);
			if (matcher.matches()) {
				boolean isLogical = matcher.group(1).equals("logical");
				String retType = matcher.group(2);
				String[] argTypes = matcher.group(4).trim().split("\\s*,\\s*");
				Signature sig = new Signature(matcher.group(3), retType,
						argTypes, isLogical);
				addSignature(matcher.group(3), sig);
				return true;
			}
			return false;
		}
		// obtain guaranteed domain elements
		if (line.startsWith("guaranteed")) {
			Pattern pat = Pattern
					.compile("guaranteed\\s+(\\w+)\\s+(.*?)\\s*;?");
			Matcher matcher = pat.matcher(line);
			if (matcher.matches()) {
				String domName = matcher.group(1);
				String[] elems = matcher.group(2).split("\\s*,\\s*");
				guaranteedDomElements.put(domName, elems);
				return true;
			}
			return false;
		}
		// read functional dependencies among relation arguments
		if (line.startsWith("relationKey") || line.startsWith("RelationKey")) {
			Pattern pat = Pattern
					.compile("[Rr]elationKey\\s+(\\w+)\\s*\\((.*)\\)\\s*;?");
			Matcher matcher = pat.matcher(line);
			if (matcher.matches()) {
				String relation = matcher.group(1);
				String[] arguments = matcher.group(2).trim().split("\\s*,\\s*");
				addRelationKey(new RelationKey(relation, arguments));
				return true;
			}
			return false;
		}
		// read type information
		if (line.startsWith("type") || line.startsWith("Type")) {
			if (taxonomy == null)
				taxonomy = new Taxonomy();
			Pattern pat = Pattern.compile("[Tt]ype\\s+(.*?);?");
			Matcher matcher = pat.matcher(line);
			Pattern typeDecl = Pattern.compile("(\\w+)(?:\\s+isa\\s+(\\w+))?");
			if (matcher.matches()) {
				String[] decls = matcher.group(1).split("\\s*,\\s*");
				for (String d : decls) {
					Matcher m = typeDecl.matcher(d);
					if (m.matches()) {
						Concept c = new Concept(m.group(1));
						taxonomy.addConcept(c);
						if (m.group(2) != null) {
							Concept parent = taxonomy.getConcept(m.group(2));
							if (parent == null)
								throw new Exception(
										"Error in declaration of type '"
												+ m.group(1)
												+ "': The parent type '"
												+ m.group(2)
												+ "' is undeclared.");
							c.setParent(parent);
						}
						return true;
					} else
						throw new Exception("The type declaration '" + d
								+ "' is invalid");
				}
			}
			return false;
		}
		// prolog rule
		if (line.startsWith("prolog")) {
			String rule = line.substring(6).trim();
			if (!rule.endsWith("."))
				rule += ".";
			prologRules.add(rule);
			return true;
		}
		// combining rule
		if(line.startsWith("combining-rule")) {
			Pattern pat = Pattern.compile("combining-rule\\s+(\\w+)\\s+([-\\w]+)\\s*;?");
			Matcher matcher = pat.matcher(line);
			if(matcher.matches()) {
				String function = matcher.group(1);
				String strRule = matcher.group(2);
				Signature sig = getSignature(function);
				CombiningRule rule;
				if(sig == null) 
					throw new Exception("Defined combining rule for unknown function '" + function + "'");
				try {
					rule = CombiningRule.fromString(strRule);
				}
				catch(IllegalArgumentException e) {
					Vector<String> v = new Vector<String>();
					for(CombiningRule cr : CombiningRule.values()) 
						v.add(cr.stringRepresention);
					throw new Exception("Invalid combining rule '" + strRule + "'; valid options: " + StringTool.join(", ", v));
				}
				this.combiningRules.put(function, rule);
				return true;
			}
		}
		return false;
	}

	/**
	 * read the contents of one or more (BLOG) files into a single string
	 * 
	 * @param files
	 * @return
	 * @throws FileNotFoundException
	 * @throws IOException
	 */
	protected String readBlogContent(String[] files)
			throws FileNotFoundException, IOException {
		// read the blog files
		StringBuffer buf = new StringBuffer();
		for (String blogFile : files) {
			buf.append(FileUtil.readTextFile(blogFile));
			buf.append('\n');
		}
		return buf.toString();
	}

	/**
	 * constructs a BLOG model by obtaining the node data from a Bayesian
	 * network template and function signatures from a BLOG file.
	 * 
	 * @param blogFile
	 * @param xmlbifFile
	 * @throws Exception
	 */
	public BLOGModel(String blogFile, String networkFile) throws Exception {
		this(new String[] { blogFile }, networkFile);
	}

	/**
	 * constructs a BLOG model from a Bayesian network template. The function
	 * signatures are derived from node/parameter names.
	 * 
	 * @param xmlbifFile
	 * @throws Exception
	 */
	public BLOGModel(String xmlbifFile) throws Exception {
		super(xmlbifFile);
		this.guessSignatures();
	}

	/**
	 * generates the ground Bayesian network for the template network that this
	 * model represents, instantiating it with the guaranteed domain elements
	 * 
	 * @return
	 * @throws Exception
	 */
	public BeliefNetworkEx getGroundBN() throws Exception {
		// create a new Bayesian network
		BeliefNetworkEx gbn = new BeliefNetworkEx();
		// add nodes in topological order
		int[] order = this.getTopologicalOrder();
		for (int i = 0; i < order.length; i++) { // for each template node (in
			// topological order)
			RelationalNode node = getRelationalNode(order[i]);
			// get all possible argument groundings
			Signature sig = getSignature(node.functionName);
			if (sig == null)
				throw new Exception("Could not retrieve signature for node "
						+ node.functionName);
			Vector<String[]> argGroundings = groundParams(sig);
			// create a new node for each grounding with the same domain and CPT
			// as the template node
			for (String[] args : argGroundings) {
				String newName = Signature.formatVarName(node.functionName,
						args);
				BeliefNode newNode = new BeliefNode(newName, node.node
						.getDomain());
				gbn.addNode(newNode);
				// link from all the parent nodes
				String[] parentNames = getParentVariableNames(node, args);
				for (String parentName : parentNames) {
					BeliefNode parent = gbn.getNode(parentName);
					gbn.bn.connect(parent, newNode);
				}
				// transfer the CPT (the entries for the new node may not be in
				// the same order so determine the appropriate mapping)
				// TODO this assumes that a function name occurs only in one
				// parent
				CPF newCPF = newNode.getCPF(), oldCPF = node.node.getCPF();
				BeliefNode[] oldProd = oldCPF.getDomainProduct();
				BeliefNode[] newProd = newCPF.getDomainProduct();
				int[] old2newindex = new int[oldProd.length];
				for (int j = 0; j < oldProd.length; j++) {
					for (int k = 0; k < newProd.length; k++)
						if (RelationalNode.extractFunctionName(
								newProd[k].getName()).equals(
								RelationalNode.extractFunctionName(oldProd[j]
										.getName())))
							old2newindex[j] = k;
				}
				for (int j = 0; j < oldCPF.size(); j++) {
					int[] oldAddr = oldCPF.realaddr2addr(j);
					int[] newAddr = new int[oldAddr.length];
					for (int k = 0; k < oldAddr.length; k++)
						newAddr[old2newindex[k]] = oldAddr[k];
					newCPF.put(newCPF.addr2realaddr(newAddr), oldCPF.get(j));
				}
			}
		}
		return gbn;
	}

	/**
	 * gets a list of lists of constants representing all possible combinations
	 * of elements of the given domains (domNames)
	 * 
	 * @param domNames
	 *            a list of domain names
	 * @param setting
	 *            the current setting (initially empty) - same length as
	 *            domNames
	 * @param idx
	 *            the index of the domain from which to choose next
	 * @param ret
	 *            the vector in which all settings shall be stored
	 * @throws Exception
	 */
	protected void groundParams(String[] domNames, String[] setting, int idx,
			Vector<String[]> ret) throws Exception {
		if (idx == domNames.length) {
			ret.add(setting.clone());
			return;
		}
		String[] elems = guaranteedDomElements.get(domNames[idx]);
		if (elems == null) {
			throw new Exception("No guaranteed domain elements for "
					+ domNames[idx]);
		}
		for (String elem : elems) {
			setting[idx] = elem;
			groundParams(domNames, setting, idx + 1, ret);
		}
	}

	protected Vector<String[]> groundParams(Signature sig) throws Exception {
		Vector<String[]> ret = new Vector<String[]>();
		groundParams(sig.argTypes, new String[sig.argTypes.length], 0, ret);
		return ret;
	}

	public void write(PrintStream out) throws Exception {
		BeliefNode[] nodes = bn.getNodes();

		// write declarations for types, guaranteed domain elements and
		// functions
		writeDeclarations(out);

		// CPTs
		// TODO handle decision parents properly by using if-then-else
		for (RelationalNode relNode : getRelationalNodes()) {
			if (relNode.isAuxiliary)
				continue;
			CPF cpf = nodes[relNode.index].getCPF();
			BeliefNode[] deps = cpf.getDomainProduct();
			Discrete[] domains = new Discrete[deps.length];
			StringBuffer args = new StringBuffer();
			int[] addr = new int[deps.length];
			for (int j = 0; j < deps.length; j++) {
				if (deps[j].getType() == BeliefNode.NODE_DECISION) // ignore
					// decision
					// nodes
					// (they are
					// not
					// dependencies
					// because
					// they are
					// assumed
					// to be
					// true)
					continue;
				if (j > 0) {
					if (j > 1)
						args.append(", ");
					args.append(getRelationalNode(deps[j]).getCleanName());
				}
				domains[j] = (Discrete) deps[j].getDomain();
			}
			Vector<String> lists = new Vector<String>();
			getCPD(lists, cpf, domains, addr, 1);
			out.printf("%s ~ TabularCPD[%s](%s);\n", relNode.getCleanName(),
					StringTool.join(",", lists.toArray(new String[0])), args
							.toString());
		}
	}

	protected void writeDeclarations(PrintStream out) {
		// write type decls
		Set<String> types = new HashSet<String>();
		for (RelationalNode node : this.getRelationalNodes()) {
			if (node.isBuiltInPred())
				continue;
			Signature sig = this.getSignature(node.functionName);
			Discrete domain = (Discrete) node.node.getDomain();
			if (!types.contains(sig.returnType)
					&& !sig.returnType.equals("Boolean")) {
				if (!isBooleanDomain(domain)) {
					types.add(sig.returnType);
					out.printf("Type %s;\n", sig.returnType);
				} else
					sig.returnType = "Boolean";
			}
			for (String t : sig.argTypes) {
				if (!types.contains(t)) {
					types.add(t);
					out.printf("Type %s;\n", t);
				}
			}
		}
		out.println();

		// write domains
		Set<String> handledDomains = new HashSet<String>();
		for (RelationalNode node : this.getRelationalNodes()) {
			if (node.isBuiltInPred())
				continue;
			Discrete domain = (Discrete) node.node.getDomain();
			Signature sig = getSignature(node.functionName);
			if (!sig.returnType.equals("Boolean")) {
				String t = sig.returnType;
				if (!handledDomains.contains(t)) {
					handledDomains.add(t);
					out.print("guaranteed " + t + " ");
					for (int j = 0; j < domain.getOrder(); j++) {
						if (j > 0)
							out.print(", ");
						out.print(domain.getName(j));
					}
					out.println(";");
				}
			}
		}
		out.println();

		// functions
		for (RelationalNode node : this.getRelationalNodes()) {
			if (node.isBuiltInPred())
				continue;
			Signature sig = getSignature(node.functionName);
			out.printf("random %s %s(%s);\n", sig.returnType,
					node.functionName, StringTool.join(", ", sig.argTypes));
		}
		out.println();
		
		// relation keys
		for(Collection<RelationKey> c : this.relationKeys.values())
			for(RelationKey relKey : c)
				out.println(relKey.toString());
		out.println();
	}

	protected void getCPD(Vector<String> lists, CPF cpf, Discrete[] domains,
			int[] addr, int i) {
		if (i == addr.length) {
			StringBuffer sb = new StringBuffer();
			sb.append('[');
			for (int j = 0; j < domains[0].getOrder(); j++) {
				addr[0] = j;
				int realAddr = cpf.addr2realaddr(addr);
				double value = ((ValueDouble) cpf.get(realAddr)).getValue();
				if (j > 0)
					sb.append(',');
				sb.append(value);
			}
			sb.append(']');
			lists.add(sb.toString());
		} else {
			// go through all possible parent-child configurations
			BeliefNode[] domProd = cpf.getDomainProduct();
			if (domProd[i].getType() == BeliefNode.NODE_DECISION) // for
				// decision
				// nodes,
				// always
				// assume
				// true
				addr[i] = 0;
			else {
				for (int j = 0; j < domains[i].getOrder(); j++) {
					addr[i] = j;
					getCPD(lists, cpf, domains, addr, i + 1);
				}
			}
		}
	}
	
	public static void main(String[] args) {
		try {
			String bifFile = "abl/kitchen-places/actseq.xml";
			ABL bn = new ABL(new String[] { "abl/kitchen-places/actseq.abl" },
					bifFile);
			String dbFile = "abl/kitchen-places/train.blogdb";
			// read the training database
			System.out.println("Reading data...");
			Database db = new Database(bn);
			db.readBLOGDB(dbFile);
			System.out.println("  " + db.getEntries().size()
					+ " variables read.");
			// learn domains
			if (true) {
				System.out.println("Learning domains...");
				DomainLearner domLearner = new DomainLearner(bn);
				domLearner.learn(db);
				domLearner.finish();
			}
			// learn parameters
			System.out.println("Learning parameters...");
			CPTLearner cptLearner = new CPTLearner(bn);
			cptLearner.learnTyped(db, true, true);
			cptLearner.finish();
			System.out.println("Writing XML-BIF output...");
			bn.saveXMLBIF(bifFile);
			if (true) {
				System.out.println("Showing Bayesian network...");
				bn.show();
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}

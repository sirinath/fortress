/*******************************************************************************
    Copyright 2007 Sun Microsystems, Inc.,
    4150 Network Circle, Santa Clara, California 95054, U.S.A.
    All rights reserved.

    U.S. Government Rights - Commercial software.
    Government users are subject to the Sun Microsystems, Inc. standard
    license agreement and applicable provisions of the FAR and its supplements.

    Use is subject to license terms.

    This distribution may include materials developed by third parties.

    Sun, Sun Microsystems, the Sun logo and Java are trademarks or registered
    trademarks of Sun Microsystems, Inc. in the U.S. and other countries.
 ******************************************************************************/

package com.sun.fortress.syntax_abstractions.phases;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sun.fortress.compiler.StaticError;
import com.sun.fortress.compiler.StaticPhaseResult;
import com.sun.fortress.nodes.Id;
import com.sun.fortress.nodes.IdName;
import com.sun.fortress.nodes.Modifier;
import com.sun.fortress.nodes.Node;
import com.sun.fortress.nodes.NodeUpdateVisitor;
import com.sun.fortress.nodes.ProductionDef;
import com.sun.fortress.nodes.QualifiedIdName;
import com.sun.fortress.nodes.QualifiedName;
import com.sun.fortress.nodes.SyntaxDef;
import com.sun.fortress.nodes.TraitType;
import com.sun.fortress.syntax_abstractions.GrammarEnv;
import com.sun.fortress.syntax_abstractions.GrammarIndex;
import com.sun.fortress.syntax_abstractions.intermediate.CoreModule;
import com.sun.fortress.syntax_abstractions.intermediate.Module;
import com.sun.fortress.syntax_abstractions.intermediate.UserModule;
import com.sun.fortress.syntax_abstractions.rats.util.FreshName;
import com.sun.fortress.syntax_abstractions.rats.util.ModuleInfo;

import edu.rice.cs.plt.tuple.Option;

/*
 * If a grammar contains a production which extends a production defined 
 * in the core fortress grammar either directly or indirectly then it should be
 * moved to a special extending module which imports the original module from the
 * original grammar. TODO: Optimization, if the grammar contains only one such 
 * production there is no need to separate the two.
 */

public class ModuleTranslator {

	public class Result extends StaticPhaseResult {
		Collection<Module> modules;

		public Result(Collection<Module> modules) {
			super();
			this.modules = modules;
		}

		public Result(Collection<Module> modules,
				Iterable<? extends StaticError> errors) {
			super(errors);
			this.modules = modules;
		}


		public Collection<Module> modules() { return modules; }
	}

	public static Result resolve(Collection<GrammarEnv> environments) {
//		Map<GrammarIndex, Module> grammarToModules = new HashMap<GrammarIndex, Module>();
//
//		for (GrammarEnv env: environments) {
//			for (GrammarIndex g: env.getToplevelGrammars()) {
//				Module m = new UserModule();
//				if (grammarToModules.containsKey(g)) {
//					m = grammarToModules.get(g);
//				}
//
//				if (g.isCoreGrammar()) {
//					m = new CoreModule();
//					m.initialize(g);
//				}
//				else {
//					m = new UserModule(g.ast().getName, g.ast().getProductions());
//
//					// Add all extended modules
//					for (GrammarIndex extendedGrammar: env.getExtendedGrammars(g)) {
//						if (!grammarToModules.containsKey(g)) {
//							if (g.isCoreGrammar()) {
//								grammarToModules.put(g, new UserModule());
//							}
//							else {
//								grammarToModules.put(g, new UserModule());
//							}
//						}
//						m.addExtendedModule(grammarToModules.get(g));
//					}
//				}
//
//				if (g.isToplevel()) {
//					m.setToplevel(true);
//				}
//
//				if (!grammarToModules.containsKey(g)) {
//					grammarToModules.put(g, m);
//				}
//			}
//		}

//		return new ModuleTranslator().new Result(expand(grammarToModules.values()));
		return null;
	}

	private static Collection<Module> expand(Collection<Module> modules) {
		// Move extending toplevel productions to their own module
		Collection<Module> ls = new LinkedList<Module>();
		for (Module module: modules) {
			Map<Module, Set<ProductionDef>> newModules = new HashMap<Module, Set<ProductionDef>>();
			Collection<ProductionDef> removeProductions = new LinkedList<ProductionDef>();
			for (ProductionDef p: module.getDefinedProductions()) {
				if (p.getExtends().isSome()) {
					removeProductions.add(p);
					Module m = lookupModule(modules, Option.unwrap(p.getExtends()));
					if (m == null) {
						throw new RuntimeException("Did not find module: "+Option.unwrap(p.getExtends()).getApi());
					}
					if (newModules.containsKey(m)) {
						newModules.get(m).add(p);
					}
					else {
						Set<ProductionDef> set = new HashSet<ProductionDef>();
						set.add(p);
						newModules.put(m, set );
					}
				}
			}
			module.getDefinedProductions().removeAll(removeProductions);
			ls.addAll(generateNewModules(newModules, module));
		}

		return ls;
	}

	private static Collection<? extends Module> generateNewModules(
			Map<Module, Set<ProductionDef>> newModules, Module orginalModule) {
		Collection<Module> ms = new LinkedList<Module>();
		for (Module module: newModules.keySet()) {
			Module m = new UserModule();
			m.setName(FreshName.getFreshName(orginalModule.getName()));
			m.setModify(module);
			m.addProductions(newModules.get(module));
			m.setImports(orginalModule.getImports());
			ms.add(m);
		}
		return ms;
	}

	private static Module lookupModule(Collection<Module> modules,
			QualifiedName name) {
		for (Module m: modules) {
			if (m.getName().equals(name.getApi())) {
				return m;
			}
		}
		return null;
	}

//	public static ProductionDef renameProduction(final ProductionDef production, final String newName) {
//		return (ProductionDef) production.accept(new NodeUpdateVisitor() {						
//			@Override
//			public Node forProductionDefOnly(ProductionDef that, Option<? extends Modifier> modifier_result, QualifiedIdName name_result, TraitType type_result, Option<? extends QualifiedIdName> extends_result, List<SyntaxDef> syntaxDefs_result) {
//				name_result = new QualifiedIdName(new IdName(new Id(newName)));
//				return new ProductionDef(that.getSpan(), modifier_result, name_result, type_result, extends_result, syntaxDefs_result);
//			}		
//		});
//	}

}

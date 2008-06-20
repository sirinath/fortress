/*******************************************************************************
    Copyright 2008 Sun Microsystems, Inc.,
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

package com.sun.fortress.compiler;

import java.util.*;
import com.sun.fortress.compiler.typechecker.*;
import com.sun.fortress.compiler.index.ApiIndex;
import com.sun.fortress.compiler.index.ComponentIndex;
import com.sun.fortress.exceptions.StaticError;
import com.sun.fortress.exceptions.TypeError;
import com.sun.fortress.interpreter.drivers.ProjectProperties;
import com.sun.fortress.nodes.AbstractNode;
import com.sun.fortress.nodes.Component;
import com.sun.fortress.nodes.APIName;
import com.sun.fortress.nodes.Node;
import com.sun.fortress.useful.Debug;

import edu.rice.cs.plt.iter.IterUtil;

/**
 * Verifies all static properties of a valid Fortress program that require
 * interpreting types.  Assumes all names referring to APIs are fully-qualified,
 * and that the other transformations handled by the {@link Disambiguator} have
 * been performed.  In addition to checking the program, performs the following
 * transformations:
 * <ul>
 * <li>All unknown placeholder types are provided explicit (inferred) values.</li>
 * <li>Explicit coercions are added where needed.</li>
 * <li>Juxtapositions are given a binary structure.</li>
 * <li>FieldRefs that refer to methods and that are followed by an argument expression
 *     become MethodInvocations.</li>
 * </li>
 */
public class StaticChecker {
    
    /** 
     * This field is a temporary switch used for testing. 
     * When typecheck is true, the TypeChecker is called during static checking. 
     * It's false by default to allow the static checker to be used at the command
     * line before the type checker is fully functional.
     * StaticTest sets typecheck to true before running type checking tests.
     */
    public static boolean typecheck = ProjectProperties.getBoolean("fortress.test.typecheck", false);
    
    
    public static class ApiResult extends StaticPhaseResult {
        private Map<APIName, ApiIndex> _apis;
        public ApiResult(Iterable<? extends StaticError> errors, Map<APIName, ApiIndex> apis) { 
            super(errors); 
            _apis = apis;
        }
        public Map<APIName, ApiIndex> apis() { return _apis; }
    }
    
    /**
     * Check the given apis. To support circular references, the apis should appear 
     * in the given environment.
     */
    public static ApiResult checkApis(Map<APIName, ApiIndex> apis,
                                      GlobalEnvironment env) {
        // TODO: implement
        return new ApiResult(IterUtil.<StaticError>empty(), apis);
    }
    
    
    public static class ComponentResult extends StaticPhaseResult {
        private final Map<APIName, ComponentIndex> _components;
        public ComponentResult(Map<APIName, ComponentIndex> components,
                               Iterable<? extends StaticError> errors) {
            super(errors);
            _components = components;
        }
        public Map<APIName, ComponentIndex> components() { return _components; }
    }
    
    /** Statically check the given components. */
    public static ComponentResult
        checkComponents(Map<APIName, ComponentIndex> components,
                        GlobalEnvironment env) 
    {
        HashSet<Component> checkedComponents = new HashSet<Component>();
        Iterable<? extends StaticError> errors = new HashSet<StaticError>();
        
        for (APIName componentName : components.keySet()) {
            try{
                com.sun.fortress.interpreter.drivers.ASTIO.writeJavaAst(components.get(componentName).ast(), "/tmp/x" + componentName );
            } catch ( Exception e ){
                e.printStackTrace();
            }

            TypeCheckerResult checked = checkComponent(components.get(componentName), env);
            checkedComponents.add((Component)checked.ast());
            errors = IterUtil.compose(checked.errors(), errors);
        }
        return new ComponentResult
            (IndexBuilder.buildComponents(checkedComponents, 
                                          System.currentTimeMillis()).
                 components(),
                                   errors);
    }
    
    public static TypeCheckerResult checkComponent(ComponentIndex component, 
                                                   GlobalEnvironment env) 
    {
        if (typecheck) {
            TypeEnv typeEnv = TypeEnv.make(component);
            
            // Add all top-level function names to the component-level environment.
            typeEnv = typeEnv.extendWithFunctions(component.functions());
            
            // Iterate over top-level variables, adding each to the component-level environment.
            typeEnv = typeEnv.extend(component.variables());
            
            // Add all top-level object names declared in the component-level environment.
            typeEnv = typeEnv.extendWithTypeConses(component.typeConses());
            
            TypeChecker typeChecker = new TypeChecker(new TraitTable(component, env), 
                                                      StaticParamEnv.make(),
                                                      typeEnv,
                                                      component);
           
            TypeCheckerResult result =  component.ast().accept(typeChecker);
            
            // We need to make sure type inference succeeded.
            if( !result.getNodeConstraints().isSatisfiable() ) {
            	// Oh no! Type inference failed. Our error message will suck.
            	String err = "Type inference failed.";
            	result = TypeCheckerResult.addError(result, TypeError.make(err, component.ast()));
            	return result;
            }
            else{
            	InferenceVarReplacer rep=new InferenceVarReplacer(result.getMap());
            	Node replaced = result.ast().accept(rep);
            	
            	
            	return TypeCheckerResult.replaceAST(result, replaced);
            }
        } else {
            return new TypeCheckerResult(component.ast(), IterUtil.<StaticError>empty());
        }
    }
    
}

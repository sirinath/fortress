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
package com.sun.fortress.shell;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import com.sun.fortress.compiler.FortressRepository;
import com.sun.fortress.compiler.IndexBuilder;
import com.sun.fortress.compiler.IndexBuilder.ComponentResult;
import com.sun.fortress.compiler.index.ApiIndex;
import com.sun.fortress.compiler.index.ComponentIndex;
import com.sun.fortress.interpreter.drivers.ASTIO;
import com.sun.fortress.interpreter.drivers.ProjectProperties;
import com.sun.fortress.nodes.Api;
import com.sun.fortress.nodes.CompilationUnit;
import com.sun.fortress.nodes.Component;
import com.sun.fortress.nodes.APIName;
import com.sun.fortress.nodes.Id;
import com.sun.fortress.useful.Path;

import edu.rice.cs.plt.tuple.Option;

public class PathBasedRepository implements FortressRepository {

    /**
     * Any components/APIs found on path are "regular" -- not native.
     * path is searched before nativePath.
     */
    final Path path;
    /**
     * Any components found on nativePath are "native" -- special semantics
     * for objects.
     */
    final Path nativePath;
    /**
     * If true, allows matching "a.b" against file containing "b", at path a/b.
     */
    boolean relaxedSearch;
    IndexBuilder builder = new IndexBuilder();
    FortressRepository writer;
    
    private final Map<APIName, ApiIndex> apis = 
        new HashMap<APIName, ApiIndex>();
    
    private final Map<APIName, ComponentIndex> components = 
        new HashMap<APIName, ComponentIndex>();
   
    public PathBasedRepository(Path p, Path native_p) {
        this(p, native_p, new StubRepository());
    }
    
    public PathBasedRepository(Path p, Path native_p, FortressRepository writer) {
        this.path = p;
        this.nativePath = native_p;
        this.writer = writer;
    }
    
    public void addApi(APIName name, ApiIndex definition) {
        apis.put(name, definition);
        writer.addApi(name, definition);
    }

    public void addComponent(APIName name, ComponentIndex definition) {
        components.put(name, definition);
        writer.addComponent(name, definition);
    }

    public Map<APIName, ApiIndex> apis() {
        // TODO Auto-generated method stub
        return null;
    }

    public ApiIndex getApi(APIName name) throws IOException {
        if (apis.containsKey(name))
            return apis.get(name);
        
        boolean[] isNative = new boolean[1];
        
        File fdot = findFile(name, ProjectProperties.API_SOURCE_SUFFIX, isNative);
        // Attempt to parse fdot.
        
        Option<CompilationUnit> ocu = ASTIO.parseToJavaAst(fdot.getCanonicalPath(), isNative[0]);
        if (ocu.isNone()) {
            throw new Error("Parse error");
        } else {
            CompilationUnit cu = Option.unwrap(ocu);
            if (cu instanceof Api) {
                Api component = (Api) cu;
                ApiIndex ci = builder.buildApiIndex(component, fdot.lastModified());
                apis.put(name, ci);
                return ci;
            } else {
                throw new Error("Unexpected result type " +
                        cu.getClass().getSimpleName() +
                        " for api parse of " +
                        fdot.getCanonicalPath());
            }
         
        }
        
    }

    public ComponentIndex getComponent(APIName name) throws FileNotFoundException, IOException {
        if (components.containsKey(name))
            return components.get(name);
        boolean[] isNative = new boolean[1];
        
        File fdot = findFile(name, ProjectProperties.COMP_SOURCE_SUFFIX, isNative);
        // Attempt to parse fdot.
        
        Option<CompilationUnit> ocu = ASTIO.parseToJavaAst(fdot.getCanonicalPath(), isNative[0]);
        if (ocu.isNone()) {
            throw new Error("Parse error");
        } else {
            CompilationUnit cu = Option.unwrap(ocu);
            if (cu instanceof Component) {
                Component component = (Component) cu;
                ComponentIndex ci = builder.buildComponentIndex(component, fdot.lastModified());
                components.put(name, ci);
                return ci;
            } else {
                throw new Error("Unexpected result type " +
                        cu.getClass().getSimpleName() +
                        " for component parse of " +
                        fdot.getCanonicalPath());
            }
        } 
    }

    private File findFile(APIName name, String suffix, boolean[] isNative) throws FileNotFoundException {
        String dotted = name.toString();
        String slashed = dotted.replaceAll("[.]", "/");
        dotted = dotted + "." + suffix;
        slashed = slashed + "." + suffix;
        File fdot;
        if (isNative != null)
            isNative[0] = false;
        try {
            fdot = path.findFile(dotted);
        } catch (FileNotFoundException ex1) {
            try {
                fdot = path.findFile(slashed);
            } catch (FileNotFoundException ex2) {
                try {
                    fdot = nativePath.findFile(dotted);
                } catch (FileNotFoundException ex3) {
                    try {
                        fdot = nativePath.findFile(slashed);
                    } catch (FileNotFoundException ex4) {
                        throw new FileNotFoundException("Could not find " + dotted + " or " + slashed + " on path or native path");
                    }
                }
                if (isNative != null)
                    isNative[0] = true;
            }
        }
        return fdot;
    }

    public long getModifiedDateForApi(APIName name) throws FileNotFoundException {
        if (apis.containsKey(name))
            return apis.get(name).modifiedDate();
        File fdot = findFile(name, ProjectProperties.API_SOURCE_SUFFIX, null);
        return fdot.lastModified();
    }

    public long getModifiedDateForComponent(APIName name) throws FileNotFoundException {
        if (components.containsKey(name))
            return components.get(name).modifiedDate();
        File fdot = findFile(name, ProjectProperties.COMP_SOURCE_SUFFIX, null);
        return fdot.lastModified();
    }

}
